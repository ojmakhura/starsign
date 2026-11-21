package bw.co.starsign.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Object storage settings (app.storage.*). The S3 connection itself (endpoint, keys, region, path-style
 * access) is configured under spring.cloud.aws.*.
 */
@ConfigurationProperties("app.storage")
public class StorageProperties
{
    /** Recorded on every storage reference. */
    private StorageProvider provider = StorageProvider.SEAWEEDFS_S3;

    /** The bucket every namespace is stored in; namespaces and tenants are key prefixes. */
    private String bucket = "starsign-documents";

    /** Create the bucket when it is missing (local development); otherwise it must exist on the server. */
    private boolean createBuckets = true;

    public StorageProvider getProvider()
    {
        return provider;
    }

    public void setProvider(StorageProvider provider)
    {
        this.provider = provider;
    }

    public String getBucket()
    {
        return bucket;
    }

    public void setBucket(String bucket)
    {
        this.bucket = bucket;
    }

    public boolean isCreateBuckets()
    {
        return createBuckets;
    }

    public void setCreateBuckets(boolean createBuckets)
    {
        this.createBuckets = createBuckets;
    }
}
