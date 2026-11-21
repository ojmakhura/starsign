package bw.co.starsign.storage;

import io.awspring.cloud.autoconfigure.s3.S3ClientCustomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;

/**
 * Adjusts the S3Client Spring Cloud AWS builds from spring.cloud.aws.* for SeaweedFS, and prepares the
 * bucket on startup (as knowvera's ObjectStorageConfig).
 */
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class ObjectStorageConfig
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ObjectStorageConfig.class);

    /**
     * Only the checksums S3 requires: the SDK's newer default checksums (CRC32 headers, chunked
     * trailers) are rejected by older SeaweedFS releases. SHA-256 is computed by StorageService itself.
     */
    @Bean
    public S3ClientCustomizer seaweedFsCompatibleClient()
    {
        return builder -> builder
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED);
    }

    /**
     * Creates the bucket when app.storage.create-buckets is on. The API must start without the store
     * (it is created again lazily before the first upload), so a failure here is only logged.
     */
    @Bean
    public ApplicationRunner storageBucketInitializer(ObjectStorageAdapter objectStorageAdapter, StorageProperties properties)
    {
        return arguments -> {
            try
            {
                objectStorageAdapter.ensureBucket(properties.getBucket());
            }
            catch (RuntimeException e)
            {
                LOGGER.warn("Object storage is not reachable at startup; bucket '{}' will be checked again before the first upload: {}",
                        properties.getBucket(), e.getMessage());
            }
        };
    }
}
