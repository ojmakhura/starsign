package bw.co.starsign.storage;

import java.io.InputStream;
import org.springframework.core.io.InputStreamResource;

/**
 * A stored object as returned by {@link StorageService#asResource(String)}: streams the content once and
 * knows its size, content type and original file name, so controllers can set Content-Length,
 * Content-Type and Content-Disposition.
 */
public class StoredObjectResource
    extends InputStreamResource
{
    private final String objectId;
    private final String contentType;
    private final long contentLength;
    private final String fileName;
    private final String sha256Checksum;

    public StoredObjectResource(InputStream content, String objectId, String contentType, long contentLength,
            String fileName, String sha256Checksum)
    {
        super(content, "stored object " + objectId);
        this.objectId = objectId;
        this.contentType = contentType;
        this.contentLength = contentLength;
        this.fileName = fileName;
        this.sha256Checksum = sha256Checksum;
    }

    public String getObjectId()
    {
        return objectId;
    }

    public String getContentType()
    {
        return contentType;
    }

    /** The recorded size, without reading the stream. */
    @Override
    public long contentLength()
    {
        return contentLength;
    }

    /** The original file name, or the object id when none was recorded. */
    @Override
    public String getFilename()
    {
        return fileName == null ? objectId : fileName;
    }

    public String getSha256Checksum()
    {
        return sha256Checksum;
    }
}
