package bw.co.starsign.storage;

import bw.co.starsign.organisation.Organisation;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;

/**
 * Bucket layout shared by the storage services: {@code <tenant>/<namespace>/<objectId>}, where the tenant is
 * the organisation's tenant id (its id when it has none) and {@code platform} for platform-wide files such as
 * the platform branding.
 */
final class StorageKeys
{
    static final String PLATFORM_PREFIX = "platform";

    private StorageKeys()
    {
    }

    /** The key prefix of everything stored for the organisation, ending with '/'. */
    static String tenantPrefix(Organisation organisation)
    {
        if (organisation == null)
        {
            return PLATFORM_PREFIX + "/";
        }
        return StringUtils.defaultIfBlank(organisation.getTenantId(), organisation.getId().toString()) + "/";
    }

    static String objectKey(Organisation organisation, StorageNamespace namespace, String objectId)
    {
        return tenantPrefix(organisation) + namespace.name().toLowerCase(Locale.ROOT).replace('_', '-') + "/" + objectId;
    }

    /**
     * The checksum recorded while an upload whose checksum is not known yet is pending: the column is
     * required and unique, so it is derived from the object id. It never equals a real content hash in
     * practice, and retries recognise it to accept the object by its size.
     */
    static String pendingChecksum(String objectId)
    {
        return DigestUtils.sha256Hex("pending-upload:" + objectId);
    }

    /** SHA-256 of the whole stream (lower-case hex); closes the stream. */
    static String sha256(InputStream content) throws IOException
    {
        try (InputStream in = content)
        {
            return DigestUtils.sha256Hex(in);
        }
    }
}
