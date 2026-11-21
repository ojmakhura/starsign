// license-header java merge-point
/**
 * This is only generated once! It will never be overwritten.
 * You can (and have to!) safely modify it by hand.
 * TEMPLATE:    SpringServiceImpl.vsl in andromda-spring cartridge
 * MODEL CLASS: AndroMDAModel::backend::bw.co.starsign::storage::ObjectStorageAdapter
 * STEREOTYPE:  Service
 */
package bw.co.starsign.storage;

import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * SeaweedFS through its S3 gateway, with the S3Client Spring Cloud AWS builds from spring.cloud.aws.*
 * (path-style access, see {@link ObjectStorageConfig}).
 *
 * @see bw.co.starsign.storage.ObjectStorageAdapter
 */
@Service("objectStorageAdapter")
public class ObjectStorageAdapterImpl
    extends ObjectStorageAdapterBase
{
    /** headObject keys. An object that does not exist gives an empty map. */
    public static final String CONTENT_LENGTH = "contentLength";
    public static final String CONTENT_TYPE = "contentType";
    public static final String E_TAG = "eTag";
    public static final String VERSION_ID = "versionId";
    public static final String LAST_MODIFIED = "lastModified";
    public static final String METADATA = "metadata";

    private static final int MAX_KEYS_PER_PAGE = 1000;

    private final S3Client s3Client;
    private final StorageProperties properties;

    /** Buckets known to exist, so uploads do not check them every time. */
    private final Set<String> readyBuckets = ConcurrentHashMap.newKeySet();

    public ObjectStorageAdapterImpl(
        S3Client s3Client,
        StorageProperties properties) {

        super(
            );
        this.s3Client = s3Client;
        this.properties = properties;
    }

    /**
     * @see bw.co.starsign.storage.ObjectStorageAdapter#putObject(String, String, String, long, InputStream, Map)
     */
    @Override
    protected String handlePutObject(String bucket, String objectKey, String contentType, long contentLength, InputStream content, Map metadata)
        throws Exception
    {
        if (contentLength < 0)
        {
            throw new IllegalArgumentException("The size of the object must be known before it is stored");
        }
        ensureReady(bucket);

        PutObjectResponse response = s3Client.putObject(request -> request
                .bucket(bucket)
                .key(objectKey)
                .contentType(contentType)
                .contentLength(contentLength)
                .metadata(userMetadata(metadata)),
                RequestBody.fromInputStream(content, contentLength));
        return StringUtils.defaultIfBlank(response.versionId(), unquote(response.eTag()));
    }

    /**
     * The object's content; the caller closes the stream.
     *
     * @see bw.co.starsign.storage.ObjectStorageAdapter#getObject(String, String)
     */
    @Override
    protected InputStream handleGetObject(String bucket, String objectKey)
        throws Exception
    {
        return s3Client.getObject(request -> request.bucket(bucket).key(objectKey));
    }

    /**
     * The object's size, type, ETag, version, last modification and user metadata; an empty map when the
     * object does not exist.
     *
     * @see bw.co.starsign.storage.ObjectStorageAdapter#headObject(String, String)
     */
    @Override
    protected Map handleHeadObject(String bucket, String objectKey)
        throws Exception
    {
        HeadObjectResponse response;
        try
        {
            response = s3Client.headObject(request -> request.bucket(bucket).key(objectKey));
        }
        catch (NoSuchKeyException e)
        {
            return Map.of();
        }
        catch (S3Exception e)
        {
            // HEAD responses have no body, so some servers answer a missing key with a bare 404
            if (e.statusCode() == 404)
            {
                return Map.of();
            }
            throw e;
        }

        Map<String, Object> head = new LinkedHashMap<>();
        head.put(CONTENT_LENGTH, response.contentLength());
        head.put(CONTENT_TYPE, response.contentType());
        head.put(E_TAG, unquote(response.eTag()));
        head.put(VERSION_ID, response.versionId());
        head.put(LAST_MODIFIED, response.lastModified());
        head.put(METADATA, response.metadata() == null ? Map.of() : new HashMap<>(response.metadata()));
        return head;
    }

    /**
     * Server-side copy within the bucket; returns the copy's version id or ETag.
     *
     * @see bw.co.starsign.storage.ObjectStorageAdapter#copyObject(String, String, String)
     */
    @Override
    protected String handleCopyObject(String bucket, String sourceKey, String targetKey)
        throws Exception
    {
        ensureReady(bucket);
        CopyObjectResponse response = s3Client.copyObject(request -> request
                .sourceBucket(bucket)
                .sourceKey(sourceKey)
                .destinationBucket(bucket)
                .destinationKey(targetKey));
        String eTag = response.copyObjectResult() == null ? null : unquote(response.copyObjectResult().eTag());
        return StringUtils.defaultIfBlank(response.versionId(), eTag);
    }

    /**
     * Deleting an object that does not exist succeeds (S3 semantics).
     *
     * @see bw.co.starsign.storage.ObjectStorageAdapter#deleteObject(String, String)
     */
    @Override
    protected void handleDeleteObject(String bucket, String objectKey)
        throws Exception
    {
        s3Client.deleteObject(request -> request.bucket(bucket).key(objectKey));
    }

    /**
     * One page of the keys under {@code prefix}, in key order. The page starts after the key given as
     * {@code continuationToken} (S3 StartAfter): pass the prefix itself for the first page and the last
     * key received for the next one. An empty list is the end of the listing.
     *
     * @see bw.co.starsign.storage.ObjectStorageAdapter#listObjectKeys(String, String, String, int)
     */
    @Override
    protected List<String> handleListObjectKeys(String bucket, String prefix, String continuationToken, int maxKeys)
        throws Exception
    {
        int pageSize = maxKeys <= 0 ? MAX_KEYS_PER_PAGE : Math.min(maxKeys, MAX_KEYS_PER_PAGE);
        ListObjectsV2Response response;
        try
        {
            response = s3Client.listObjectsV2(request -> request
                    .bucket(bucket)
                    .prefix(prefix)
                    .startAfter(continuationToken)
                    .maxKeys(pageSize));
        }
        catch (NoSuchBucketException e)
        {
            // Nothing was ever stored
            return List.of();
        }
        return response.contents().stream().map(S3Object::key).toList();
    }

    /**
     * Creates the bucket when it is missing and app.storage.create-buckets is on.
     *
     * @see bw.co.starsign.storage.ObjectStorageAdapter#ensureBucket(String)
     */
    @Override
    protected void handleEnsureBucket(String bucket)
        throws Exception
    {
        try
        {
            s3Client.headBucket(request -> request.bucket(bucket));
        }
        catch (S3Exception e)
        {
            // NoSuchBucket from AWS; S3-compatible servers often answer a bare 404
            if (!(e instanceof NoSuchBucketException) && e.statusCode() != 404)
            {
                throw e;
            }
            if (!properties.isCreateBuckets())
            {
                throw new IllegalStateException("Storage bucket '" + bucket + "' does not exist "
                        + "(app.storage.create-buckets is off: create it on the storage server)", e);
            }
            try
            {
                s3Client.createBucket(request -> request.bucket(bucket));
                logger.info("Created storage bucket '{}'", bucket);
            }
            catch (S3Exception created)
            {
                // Another instance created it meanwhile
                String code = created.awsErrorDetails() == null ? null : created.awsErrorDetails().errorCode();
                if (!"BucketAlreadyOwnedByYou".equals(code) && !"BucketAlreadyExists".equals(code))
                {
                    throw created;
                }
            }
        }
        readyBuckets.add(bucket);
    }

    private void ensureReady(String bucket) throws Exception
    {
        if (!readyBuckets.contains(bucket))
        {
            handleEnsureBucket(bucket);
        }
    }

    /** S3 user metadata is text only; null entries are left out. */
    private static Map<String, String> userMetadata(Map<?, ?> metadata)
    {
        Map<String, String> values = new HashMap<>();
        if (metadata != null)
        {
            metadata.forEach((name, value) -> {
                if (name != null && value != null)
                {
                    values.put(name.toString(), value.toString());
                }
            });
        }
        return values;
    }

    private static String unquote(String eTag)
    {
        return eTag == null ? null : StringUtils.strip(eTag, "\"");
    }
}
