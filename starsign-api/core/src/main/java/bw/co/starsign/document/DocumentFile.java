package bw.co.starsign.document;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import org.springframework.core.io.InputStreamResource;

/**
 * A document file streamed to the client, with what the controller needs for the response headers.
 * Readable once, like every {@link InputStreamResource}.
 */
public class DocumentFile
    extends InputStreamResource
{
    private final String filename;
    private final String contentType;
    private final long length;

    public DocumentFile(InputStream content, String filename, String contentType, long length)
    {
        super(content, filename);
        this.filename = filename;
        this.contentType = contentType;
        this.length = length;
    }

    public DocumentFile(byte[] content, String filename, String contentType)
    {
        this(new ByteArrayInputStream(content), filename, contentType, content.length);
    }

    @Override
    public String getFilename()
    {
        return filename;
    }

    public String getContentType()
    {
        return contentType;
    }

    /** The size in bytes, or -1 when unknown (never reads the stream). */
    @Override
    public long contentLength()
    {
        return length;
    }
}
