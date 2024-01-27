/*
 * Copyright 2020 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.github.ambry.frontend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.github.ambry.account.AccountService;
import com.github.ambry.account.Container;
import com.github.ambry.account.Dataset;
import com.github.ambry.commons.BlobId;
import com.github.ambry.commons.ByteBufferReadableStreamChannel;
import com.github.ambry.commons.Callback;
import com.github.ambry.commons.RetainingAsyncWritableChannel;
import com.github.ambry.commons.RetryExecutor;
import com.github.ambry.commons.RetryPolicies;
import com.github.ambry.commons.RetryPolicy;
import com.github.ambry.config.FrontendConfig;
import com.github.ambry.messageformat.BlobInfo;
import com.github.ambry.messageformat.BlobProperties;
import com.github.ambry.named.NamedBlobDb;
import com.github.ambry.named.NamedBlobRecord;
import com.github.ambry.quota.QuotaManager;
import com.github.ambry.quota.QuotaUtils;
import com.github.ambry.rest.RequestPath;
import com.github.ambry.rest.RestRequest;
import com.github.ambry.rest.RestResponseChannel;
import com.github.ambry.rest.RestServiceErrorCode;
import com.github.ambry.rest.RestServiceException;
import com.github.ambry.rest.RestUtils;
import com.github.ambry.router.ChunkInfo;
import com.github.ambry.router.ReadableStreamChannel;
import com.github.ambry.router.Router;
import com.github.ambry.router.RouterErrorCode;
import com.github.ambry.router.RouterException;
import com.github.ambry.utils.Pair;
import com.github.ambry.utils.Utils;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.github.ambry.frontend.FrontendUtils.*;
import static com.github.ambry.rest.RestUtils.*;
import static com.github.ambry.rest.RestUtils.InternalKeys.*;
import static com.github.ambry.router.RouterErrorCode.*;

// This class is copied from NamedBlobPutHandler with some modifications to handle S3 multipart upload requests

public class NamedBlobPostHandler {
  private static final Logger LOGGER = LoggerFactory.getLogger(NamedBlobPostHandler.class);
  /**
   * Key to represent the time at which a blob will expire in ms. Used within the metadata map in signed IDs.
   */
  static final String EXPIRATION_TIME_MS_KEY = "et";
  private final SecurityService securityService;
  private final NamedBlobDb namedBlobDb;
  private final IdConverter idConverter;
  private final IdSigningService idSigningService;
  private final AccountService accountService;
  private final Router router;
  private final AccountAndContainerInjector accountAndContainerInjector;
  private final FrontendConfig frontendConfig;
  private final FrontendMetrics frontendMetrics;
  private final String clusterName;
  private final QuotaManager quotaManager;
  private final RetryPolicy retryPolicy = RetryPolicies.defaultPolicy();
  private final RetryExecutor retryExecutor = new RetryExecutor(Executors.newScheduledThreadPool(2));
  private final Set<RouterErrorCode> retriableRouterError =
      EnumSet.of(AmbryUnavailable, ChannelClosed, UnexpectedInternalError, OperationTimedOut);
  private final DeleteBlobHandler deleteBlobHandler;
  private final UrlSigningService urlSigningService;

  private static final ObjectMapper objectMapper = new ObjectMapper();

  /**
   * Constructs a handler for handling requests for uploading or stitching blobs.
   *
   * @param securityService             the {@link SecurityService} to use.
   * @param namedBlobDb                 the {@link NamedBlobDb} to use.
   * @param idConverter                 the {@link IdConverter} to use.
   * @param idSigningService            the {@link IdSigningService} to use.
   * @param router                      the {@link Router} to use.
   * @param accountAndContainerInjector helper to resolve account and container for a given request.
   * @param frontendConfig              the {@link FrontendConfig} to use.
   * @param frontendMetrics             {@link FrontendMetrics} instance where metrics should be recorded.
   * @param clusterName                 the name of the storage cluster that the router communicates with
   * @param quotaManager                The {@link QuotaManager} class to account for quota usage in serving requests.
   * @param accountService              The {@link AccountService} to get the account and container id based on names.
   * @param deleteBlobHandler
   * @param urlSigningService
   */
  NamedBlobPostHandler(SecurityService securityService, NamedBlobDb namedBlobDb, IdConverter idConverter,
      IdSigningService idSigningService, Router router, AccountAndContainerInjector accountAndContainerInjector,
      FrontendConfig frontendConfig, FrontendMetrics frontendMetrics, String clusterName, QuotaManager quotaManager,
      AccountService accountService, DeleteBlobHandler deleteBlobHandler, UrlSigningService urlSigningService) {
    this.securityService = securityService;
    this.namedBlobDb = namedBlobDb;
    this.idConverter = idConverter;
    this.idSigningService = idSigningService;
    this.router = router;
    this.accountAndContainerInjector = accountAndContainerInjector;
    this.frontendConfig = frontendConfig;
    this.frontendMetrics = frontendMetrics;
    this.clusterName = clusterName;
    this.quotaManager = quotaManager;
    this.accountService = accountService;
    this.deleteBlobHandler = deleteBlobHandler;
    this.urlSigningService = urlSigningService;
  }

  /**
   * Handles a request for post a blob
   * @param restRequest the {@link RestRequest} that contains the request parameters.
   * @param restResponseChannel the {@link RestResponseChannel} where headers should be set.
   * @param callback the {@link Callback} to invoke when the response is ready (or if there is an exception).
   */
  void handle(RestRequest restRequest, RestResponseChannel restResponseChannel,
      Callback<ReadableStreamChannel> callback) {
    restRequest.setArg(SEND_FAILURE_REASON, Boolean.TRUE);
    new NamedBlobPostHandler.CallbackChain(restRequest, restResponseChannel, callback).start();
  }

  /**
   * Represents the chain of actions to take. Keeps request context that is relevant to all callback stages.
   */
  private class CallbackChain {
    private final RestRequest restRequest;
    private final RestResponseChannel restResponseChannel;
    private final Callback<ReadableStreamChannel> finalCallback;
    private final Callback<ReadableStreamChannel> deleteDatasetCallback;
    private final String uri;

    /**
     * @param restRequest the {@link RestRequest}.
     * @param restResponseChannel the {@link RestResponseChannel}.
     * @param finalCallback the {@link Callback} to call on completion.
     */
    private CallbackChain(RestRequest restRequest, RestResponseChannel restResponseChannel,
        Callback<ReadableStreamChannel> finalCallback) {
      this.restRequest = restRequest;
      this.restResponseChannel = restResponseChannel;
      this.finalCallback = finalCallback;
      this.deleteDatasetCallback = deleteDatasetVersionIfUploadFailedCallBack(finalCallback);
      this.uri = restRequest.getUri();
    }

    /**
     * Start the chain by calling {@link SecurityService#processRequest}.
     */
    private void start() {
      restRequest.getMetricsTracker()
          .injectMetrics(frontendMetrics.putBlobMetricsGroup.getRestRequestMetrics(restRequest.isSslUsed(), false));
      try {
        // Start the callback chain by parsing blob info headers and performing request security processing.
        securityService.processRequest(restRequest, securityProcessRequestCallback());
      } catch (Exception e) {
        finalCallback.onCompletion(null, e);
      }
    }

    /**
     * After {@link SecurityService#processRequest} finishes, call {@link SecurityService#postProcessRequest} to perform
     * request time security checks that rely on the request being fully parsed and any additional arguments set.
     * @return a {@link Callback} to be used with {@link SecurityService#processRequest}.
     */
    private Callback<Void> securityProcessRequestCallback() {
      return buildCallback(frontendMetrics.putSecurityProcessRequestMetrics, securityCheckResult -> {
        BlobInfo blobInfo = getBlobInfoFromRequest();
        securityService.postProcessRequest(restRequest, securityPostProcessRequestCallback(blobInfo));
      }, uri, LOGGER, finalCallback);
    }

    /**
     * After {@link SecurityService#postProcessRequest} finishes, call {@link Router#putBlob} to persist the blob in the
     * storage layer.
     * @param blobInfo the {@link BlobInfo} to make the router call with.
     * @return a {@link Callback} to be used with {@link SecurityService#postProcessRequest}.
     */
    private Callback<Void> securityPostProcessRequestCallback(BlobInfo blobInfo) {
      return buildCallback(frontendMetrics.putSecurityPostProcessRequestMetrics, securityCheckResult -> {
        // setup the multi-part upload
        if (restRequest.getArgs().containsKey("uploads")) {
          restRequest.setArg(RestUtils.Headers.URL_TYPE, "POST");
          restRequest.setArg(RestUtils.Headers.URL_TTL, "300");
          restRequest.setArg(RestUtils.Headers.MAX_UPLOAD_SIZE, "5242880");
          restRequest.setArg(RestUtils.Headers.CHUNK_UPLOAD, "true");

          // Create signed url: http://localhost:1174/?x-ambry-ttl=2419200&x-ambry-service-id=Flink-S3-Client&x-ambry-content-type=application%2Foctet-stream&x-ambry-chunk-upload=true&x-ambry-url-type=POST&x-ambry-session=51e2439f-e74d-4765-b30d-719ae584d964&et=1704839193
          // signedUrl = uploadId
          String signedUrl = urlSigningService.getSignedUrl(restRequest);
          LOGGER.debug("NamedBlobPostHandler | Generated {} from {}", signedUrl, restRequest);

          // Create xml response
          String bucket = (String) restRequest.getArgs().get(S3_BUCKET);
          String key = (String) restRequest.getArgs().get(S3_KEY);
          LOGGER.info(
              "S3 5MB | NamedBlobPostHandler | Sending response for Multipart begin upload. Bucket = {}, Key = {}, Upload Id = {}",
              bucket, key, signedUrl);
          InitiateMultipartUploadResult initiateMultipartUploadResult = new InitiateMultipartUploadResult();
          initiateMultipartUploadResult.setBucket(bucket);
          initiateMultipartUploadResult.setKey(key);
          initiateMultipartUploadResult.setUploadId(signedUrl);
          ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
          ObjectMapper objectMapper = new XmlMapper();
          objectMapper.writeValue(outputStream, initiateMultipartUploadResult);
          ReadableStreamChannel channel =
              new ByteBufferReadableStreamChannel(ByteBuffer.wrap(outputStream.toByteArray()));
          restResponseChannel.setHeader(RestUtils.Headers.DATE, new GregorianCalendar().getTime());
          restResponseChannel.setHeader(RestUtils.Headers.SIGNED_URL, signedUrl);
          restResponseChannel.setHeader(RestUtils.Headers.CONTENT_TYPE, "application/xml");
          restResponseChannel.setHeader(RestUtils.Headers.CONTENT_LENGTH, channel.getSize());

          finalCallback.onCompletion(channel, null);
        } else if (restRequest.getArgs().containsKey("uploadId")) {
          LOGGER.info("S3 5MB | NamedBlobPostHandler | Received complete multipart upload request");
          RetainingAsyncWritableChannel channel =
              new RetainingAsyncWritableChannel(frontendConfig.maxJsonRequestSizeBytes);
          restRequest.readInto(channel, fetchStitchRequestBodyCallback(channel, blobInfo));
        }
      }, uri, LOGGER, deleteDatasetCallback);
    }

    /**
     * After reading the body of the stitch request, parse the request body,
     * and make a call to {@link Router#stitchBlob}.
     * @param channel the {@link RetainingAsyncWritableChannel} that will contain the request body.
     * @param blobInfo the {@link BlobInfo} to make the router call with.
     * @return a {@link Callback} to be used with {@link RestRequest#readInto}.
     */
    private Callback<Long> fetchStitchRequestBodyCallback(RetainingAsyncWritableChannel channel, BlobInfo blobInfo) {
      return buildCallback(frontendMetrics.putReadStitchRequestMetrics,
          bytesRead -> router.stitchBlob(getPropertiesForRouterUpload(blobInfo), blobInfo.getUserMetadata(),
              getChunksToStitch(blobInfo.getBlobProperties(), deserializeXml(channel)),
              routerStitchBlobCallback(blobInfo), QuotaUtils.buildQuotaChargeCallback(restRequest, quotaManager, true)),
          uri, LOGGER, deleteDatasetCallback);
    }

    private CompleteMultipartUpload deserializeXml(RetainingAsyncWritableChannel channel) throws RestServiceException {
      CompleteMultipartUpload completeMultipartUpload;
      try (InputStream inputStream = channel.consumeContentAsInputStream()) {
        ObjectMapper objectMapper = new XmlMapper();
        completeMultipartUpload = objectMapper.readValue(inputStream, CompleteMultipartUpload.class);
        LOGGER.info("S3 5MB | NamedBlobPostHandler | deserialized xml {}", completeMultipartUpload);
      } catch (IOException e) {
        throw new RestServiceException("Could not parse xml request body", e, RestServiceErrorCode.BadRequest);
      }
      return completeMultipartUpload;
    }

    /**
     * After {@link Router#putBlob} finishes, call {@link IdConverter#convert} to convert the returned ID into a format
     * that will be returned in the "Location" header.
     * @param blobInfo the {@link BlobInfo} to use for security checks.
     * @return a {@link Callback} to be used with {@link Router#putBlob}.
     */
    private Callback<String> routerStitchBlobCallback(BlobInfo blobInfo) {
      return buildCallback(frontendMetrics.putRouterStitchBlobMetrics,
          blobId -> idConverter.convert(restRequest, blobId, blobInfo, idConverterCallback(blobInfo, blobId)), uri,
          LOGGER, deleteDatasetCallback);
    }

    /**
     * After {@link IdConverter#convert} finishes, call {@link SecurityService#postProcessRequest} to perform
     * request time security checks that rely on the request being fully parsed and any additional arguments set.
     * @param blobInfo the {@link BlobInfo} to use for security checks.
     * @param blobId the blob ID returned by the router (without decoration or obfuscation by id converter).
     * @return a {@link Callback} to be used with {@link IdConverter#convert}.
     */
    private Callback<String> idConverterCallback(BlobInfo blobInfo, String blobId) {
      return buildCallback(frontendMetrics.putIdConversionMetrics, convertedBlobId -> {
        restResponseChannel.setHeader(Headers.LOCATION, convertedBlobId);
        if (blobInfo.getBlobProperties().getTimeToLiveInSeconds() == Utils.Infinite_Time) {
          // Do ttl update with retryExecutor. Use the blob ID returned from the router instead of the converted ID
          // since the converted ID may be changed by the ID converter.
          String serviceId = blobInfo.getBlobProperties().getServiceId();
          retryExecutor.runWithRetries(retryPolicy,
              callback -> router.updateBlobTtl(blobId, serviceId, Utils.Infinite_Time, callback,
                  QuotaUtils.buildQuotaChargeCallback(restRequest, quotaManager, false)), this::isRetriable,
              routerTtlUpdateCallback(blobInfo, blobId));
        } else {
          securityService.processResponse(restRequest, restResponseChannel, blobInfo,
              securityProcessResponseCallback());
        }
      }, uri, LOGGER, deleteDatasetCallback);
    }

    /**
     * @param throwable the error to check.
     * @return true if the router error is retriable.
     */
    private boolean isRetriable(Throwable throwable) {
      return throwable instanceof RouterException && retriableRouterError.contains(
          ((RouterException) throwable).getErrorCode());
    }

    /**
     * After TTL update finishes, call {@link SecurityService#postProcessRequest} to perform
     * request time security checks that rely on the request being fully parsed and any additional arguments set.
     * @param blobInfo the {@link BlobInfo} to use for security checks.
     * @param blobId the {@link String} to use for blob id.
     * @return a {@link Callback} to be used with {@link Router#updateBlobTtl(String, String, long)}.
     */
    private Callback<Void> routerTtlUpdateCallback(BlobInfo blobInfo, String blobId) {
      return buildCallback(frontendMetrics.updateBlobTtlRouterMetrics, convertedBlobId -> {
        // Set the named blob state to be 'READY' after the Ttl update succeed
        if (!restRequest.getArgs().containsKey(InternalKeys.NAMED_BLOB_VERSION)) {
          throw new RestServiceException(
              "Internal key " + InternalKeys.NAMED_BLOB_VERSION + " is required in Named Blob TTL update callback!",
              RestServiceErrorCode.InternalServerError);
        }
        long namedBlobVersion = (long) restRequest.getArgs().get(NAMED_BLOB_VERSION);
        String blobIdClean = RestUtils.stripSlashAndExtensionFromId(blobId);
        NamedBlobPath namedBlobPath = NamedBlobPath.parse(RestUtils.getRequestPath(restRequest), restRequest.getArgs());
        NamedBlobRecord record = new NamedBlobRecord(namedBlobPath.getAccountName(), namedBlobPath.getContainerName(),
            namedBlobPath.getBlobName(), blobIdClean, Utils.Infinite_Time, namedBlobVersion);
        namedBlobDb.updateBlobTtlAndStateToReady(record).get();
        securityService.processResponse(restRequest, restResponseChannel, blobInfo, securityProcessResponseCallback());
      }, uri, LOGGER, deleteDatasetCallback);
    }

    /**
     * After {@link SecurityService#processResponse}, call {@code finalCallback}.
     * @return a {@link Callback} to be used with {@link SecurityService#processResponse}.
     */
    private Callback<Void> securityProcessResponseCallback() {
      return buildCallback(frontendMetrics.putBlobSecurityProcessResponseMetrics, securityCheckResult -> {
        if (restRequest.getArgs().containsKey("uploadId") && restRequest.getArgs().containsKey(S3_REQUEST)) {
          // Create xml response
          String bucket = (String) restRequest.getArgs().get(S3_BUCKET);
          String key = (String) restRequest.getArgs().get(S3_KEY);
          LOGGER.info(
              "NamedBlobPostHandler | Sending response for Multipart upload complete. Bucket = {}, Key = {}, etag = {}",
              bucket, key, restResponseChannel.getHeader(Headers.LOCATION));

          CompleteMultipartUploadResult completeMultipartUploadResult = new CompleteMultipartUploadResult();
          completeMultipartUploadResult.setBucket(bucket);
          completeMultipartUploadResult.setKey(key);
          completeMultipartUploadResult.seteTag((String) restResponseChannel.getHeader(Headers.LOCATION));

          ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
          ObjectMapper objectMapper = new XmlMapper();
          objectMapper.writeValue(outputStream, completeMultipartUploadResult);
          ReadableStreamChannel channel =
              new ByteBufferReadableStreamChannel(ByteBuffer.wrap(outputStream.toByteArray()));
          restResponseChannel.setHeader(RestUtils.Headers.DATE, new GregorianCalendar().getTime());
          restResponseChannel.setHeader(RestUtils.Headers.CONTENT_TYPE, "application/xml");
          restResponseChannel.setHeader(RestUtils.Headers.CONTENT_LENGTH, channel.getSize());
          finalCallback.onCompletion(channel, null);
        } else {
          finalCallback.onCompletion(null, null);
        }
      }, restRequest.getUri(), LOGGER, finalCallback);
    }

    /**
     * Parse {@link BlobInfo} from the request arguments. This method will also ensure that the correct account and
     * container objects are attached to the request.
     * @return the {@link BlobInfo} parsed from the request arguments.
     * @throws RestServiceException if there is an error while parsing the {@link BlobInfo} arguments.
     */
    private BlobInfo getBlobInfoFromRequest() throws RestServiceException {
      long propsBuildStartTime = System.currentTimeMillis();
      accountAndContainerInjector.injectAccountContainerForNamedBlob(restRequest, frontendMetrics.putBlobMetricsGroup);
      if (RestUtils.isDatasetVersionQueryEnabled(restRequest.getArgs())) {
        accountAndContainerInjector.injectDatasetForNamedBlob(restRequest);
      }
      restRequest.setArg(Headers.SERVICE_ID, "Flink-S3-Client");
      restRequest.setArg(Headers.AMBRY_CONTENT_TYPE, restRequest.getArgs().get(Headers.CONTENT_TYPE));
      BlobProperties blobProperties = RestUtils.buildBlobProperties(restRequest.getArgs());
      Container container = RestUtils.getContainerFromArgs(restRequest.getArgs());
      if (blobProperties.getTimeToLiveInSeconds() + TimeUnit.MILLISECONDS.toSeconds(
          blobProperties.getCreationTimeInMs()) > Integer.MAX_VALUE) {
        LOGGER.debug("TTL set to very large value in PUT request with BlobProperties {}", blobProperties);
        frontendMetrics.ttlTooLargeError.inc();
      } else if (container.isTtlRequired() && (blobProperties.getTimeToLiveInSeconds() == Utils.Infinite_Time
          || blobProperties.getTimeToLiveInSeconds() > frontendConfig.maxAcceptableTtlSecsIfTtlRequired)) {
        String descriptor = RestUtils.getAccountFromArgs(restRequest.getArgs()).getName() + ":" + container.getName();
        if (frontendConfig.failIfTtlRequiredButNotProvided) {
          throw new RestServiceException(
              "TTL < " + frontendConfig.maxAcceptableTtlSecsIfTtlRequired + " is required for upload to " + descriptor,
              RestServiceErrorCode.InvalidArgs);
        } else {
          LOGGER.debug("{} attempted an upload with ttl {} to {}", blobProperties.getServiceId(),
              blobProperties.getTimeToLiveInSeconds(), descriptor);
          frontendMetrics.ttlNotCompliantError.inc();
          restResponseChannel.setHeader(Headers.NON_COMPLIANCE_WARNING,
              "TTL < " + frontendConfig.maxAcceptableTtlSecsIfTtlRequired + " will be required for future uploads");
        }
      }
      // inject encryption frontendMetrics if applicable
      if (blobProperties.isEncrypted()) {
        restRequest.getMetricsTracker()
            .injectMetrics(frontendMetrics.putBlobMetricsGroup.getRestRequestMetrics(restRequest.isSslUsed(), true));
      }
      Map<String, Object> userMetadataFromRequest = new HashMap<>(restRequest.getArgs());
      byte[] userMetadata = RestUtils.buildUserMetadata(userMetadataFromRequest);
      frontendMetrics.blobPropsBuildForNameBlobPutTimeInMs.update(System.currentTimeMillis() - propsBuildStartTime);
      LOGGER.trace("Blob properties of blob being PUT - {}", blobProperties);
      return new BlobInfo(blobProperties, userMetadata);
    }

    /**
     * Parse and verify the signed chunk IDs in the body of a stitch request.
     *
     * @param stitchedBlobProperties  the {@link BlobProperties} for the final stitched blob.
     * @param completeMultipartUpload
     * @return a list of chunks to stitch that can be provided to the router.
     * @throws RestServiceException
     */
    List<ChunkInfo> getChunksToStitch(BlobProperties stitchedBlobProperties,
        CompleteMultipartUpload completeMultipartUpload) throws RestServiceException {
      String reservedMetadataBlobId = null;
      List<String> signedChunkIds = new ArrayList<>();

      LOGGER.info("JING 5MB | stitch | received {} parts are {}", completeMultipartUpload.getPart().length, completeMultipartUpload.getPart());
      List<ChunkInfo> chunksToStitch = new ArrayList<>(signedChunkIds.size()); // JING CODE
      long totalStitchedBlobSize = 0;
      if (completeMultipartUpload.getPart().length == 0) {
        throw new RestServiceException("Must provide at least one ID in stitch request",
            RestServiceErrorCode.MissingArgs);
      }

      try {
        for (Part part : completeMultipartUpload.getPart()) {
          //LOGGER.info("JING 5MB | stitch | part is {} {}", part);
          signedChunkIds.add(part.geteTag());
          // pass the etag
          JsonNode jsonNode = objectMapper.readTree(part.geteTag());
          // session id
          jsonNode = jsonNode.get("chunks");
          if (jsonNode.isArray()) {
            for (final JsonNode objNode : jsonNode) {
              String blobId = objNode.get("blob").textValue();
              verifyChunkAccountAndContainer(blobId, stitchedBlobProperties);
              //reservedMetadataBlobId = getAndVerifyReservedMetadataBlobId(metadata, reservedMetadataBlobId, blobId);
              //long expirationTimeMs = RestUtils.getLongHeader(metadata, EXPIRATION_TIME_MS_KEY, true);

              Long chunkSizeBytes = objNode.get("size").longValue();
              totalStitchedBlobSize += chunkSizeBytes;
              chunksToStitch.add(new ChunkInfo(blobId, chunkSizeBytes, -1, reservedMetadataBlobId));
            }
          } else if (jsonNode.isObject()) {
            String blobId = jsonNode.get("blob").textValue();
            verifyChunkAccountAndContainer(blobId, stitchedBlobProperties);
            //reservedMetadataBlobId = getAndVerifyReservedMetadataBlobId(metadata, reservedMetadataBlobId, blobId);
            //long expirationTimeMs = RestUtils.getLongHeader(metadata, EXPIRATION_TIME_MS_KEY, true);

            Long chunkSizeBytes = jsonNode.get("size").longValue();
            totalStitchedBlobSize += chunkSizeBytes;
            chunksToStitch.add(new ChunkInfo(blobId, chunkSizeBytes, -1, reservedMetadataBlobId));
            LOGGER.info("JING 5MB | stitch single | {} {}", blobId, chunkSizeBytes);
          } else {
            LOGGER.error("JING 5MB | Not expected JSON content {}", jsonNode);
            throw new RestServiceException("Not expected JSON content " + jsonNode, RestServiceErrorCode.InvalidArgs);
          }
        }
      } catch (Exception e) {
        LOGGER.error("JING 5MB | stitch | Wrong argument", e);
        throw new RestServiceException("", RestServiceErrorCode.InvalidArgs);
      }
      LOGGER.info("JING 5MB | stitch final {} {} ", chunksToStitch, totalStitchedBlobSize);
      //the actual blob size for stitched blob is the sum of all the chunk sizes
      restResponseChannel.setHeader(Headers.BLOB_SIZE, totalStitchedBlobSize);
      return chunksToStitch;
    }

    /**
     * Check that the account and container IDs encoded in a chunk's blob ID matches those in the properties for the
     * stitched blob.
     * @param chunkBlobId the blob ID for the chunk.
     * @param stitchedBlobProperties the {@link BlobProperties} for the stitched blob.
     * @throws RestServiceException if the account or container ID does not match.
     */
    private void verifyChunkAccountAndContainer(String chunkBlobId, BlobProperties stitchedBlobProperties)
        throws RestServiceException {
      Pair<Short, Short> accountAndContainer;
      try {
        accountAndContainer = BlobId.getAccountAndContainerIds(chunkBlobId);
      } catch (Exception e) {
        throw new RestServiceException("Invalid blob ID in signed chunk ID", RestServiceErrorCode.BadRequest);
      }
      if (stitchedBlobProperties.getAccountId() != accountAndContainer.getFirst()
          || stitchedBlobProperties.getContainerId() != accountAndContainer.getSecond()) {
        throw new RestServiceException("Account and container for chunk: (" + accountAndContainer.getFirst() + ", "
            + accountAndContainer.getSecond() + ") does not match account and container for stitched blob: ("
            + stitchedBlobProperties.getAccountId() + ", " + stitchedBlobProperties.getContainerId() + ")",
            RestServiceErrorCode.BadRequest);
      }
    }

    /**
     * Verify that the reserved metadata id for the specified chunkId is same as seen for previous chunks.
     * Also return the chunk's reserved metadata id.
     * @param metadata {@link Map} of metadata set in the signed ids.
     * @param reservedMetadataBlobId Reserved metadata id for the chunks. Can be {@code null}.
     * @param chunkId The chunk id.
     * @return The reserved metadata id.
     * @throws RestServiceException in case of any exception.
     */
    private String getAndVerifyReservedMetadataBlobId(Map<String, String> metadata, String reservedMetadataBlobId,
        String chunkId) throws RestServiceException {
      String chunkReservedMetadataBlobId = RestUtils.getHeader(metadata, Headers.RESERVED_METADATA_ID, false);
      if (chunkReservedMetadataBlobId == null) {
        ReservedMetadataIdMetrics.getReservedMetadataIdMetrics(
            frontendMetrics.getMetricRegistry()).noReservedMetadataForChunkedUploadCount.inc();
        throwRestServiceExceptionIfEnabled(
            new RestServiceException(String.format("No reserved metadata id present in chunk %s signed url", chunkId),
                RestServiceErrorCode.BadRequest), router.getRouterConfig().routerReservedMetadataEnabled);
      }
      if (reservedMetadataBlobId != null && !reservedMetadataBlobId.equals(chunkReservedMetadataBlobId)) {
        ReservedMetadataIdMetrics.getReservedMetadataIdMetrics(
            frontendMetrics.getMetricRegistry()).mismatchedReservedMetadataForChunkedUploadCount.inc();
        throwRestServiceExceptionIfEnabled(new RestServiceException(String.format(
                "Reserved metadata id for the chunks are not same. For chunk: %s the reserved metadata id is %s. But reserved metadata id %s was found earlier.",
                chunkId, chunkReservedMetadataBlobId, reservedMetadataBlobId), RestServiceErrorCode.BadRequest),
            router.getRouterConfig().routerReservedMetadataEnabled);
      }
      return chunkReservedMetadataBlobId;
    }

    /**
     * Create a {@link BlobProperties} for the router upload (putBlob or stitchBlob) with a finite TTL such that
     * orphaned blobs will not be created if the write to the named blob metadata DB fails.
     * @param blobInfoFromRequest the {@link BlobInfo} parsed from the request.
     * @return a {@link BlobProperties} for a TTL-ed initial router call.
     */
    BlobProperties getPropertiesForRouterUpload(BlobInfo blobInfoFromRequest) {
      BlobProperties properties;
      if (blobInfoFromRequest.getBlobProperties().getTimeToLiveInSeconds() == Utils.Infinite_Time) {
        properties = new BlobProperties(blobInfoFromRequest.getBlobProperties());
        // For blob with infinite time, the procedure is putBlob with a TTL, record insert to database with
        // infinite TTL, and ttlUpdate.
        properties.setTimeToLiveInSeconds(frontendConfig.permanentNamedBlobInitialPutTtl);
      } else {
        properties = blobInfoFromRequest.getBlobProperties();
      }
      return properties;
    }

    /**
     * When upload named blob failed, we take the best effort to delete the dataset version which create before uploading.
     * @param callback the final callback which submit the response.
     */
    private <T> Callback<T> deleteDatasetVersionIfUploadFailedCallBack(Callback<T> callback) {
      return (r, e) -> {
        if (callback != null) {
          callback.onCompletion(r, e);
        }
      };
    }
  }
}
