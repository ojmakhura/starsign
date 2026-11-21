package bw.co.starsign.branding;

import org.springframework.core.io.ByteArrayResource;

/**
 * A branding file as served by {@link BrandingService#getPlatformAsset} and
 * {@link BrandingService#getOrganisationAsset}: the content with its type, its version (the ?v= value) and
 * whether the request named the current version, which may then be cached for a year.
 */
public class BrandingAssetResource
    extends ByteArrayResource
{
    private final String contentType;
    private final String assetVersion;
    private final boolean currentVersionRequested;
    private final String fileName;

    public BrandingAssetResource(byte[] content, String contentType, String assetVersion,
            boolean currentVersionRequested, String fileName)
    {
        super(content, "branding asset " + fileName);
        this.contentType = contentType;
        this.assetVersion = assetVersion;
        this.currentVersionRequested = currentVersionRequested;
        this.fileName = fileName;
    }

    public String getContentType()
    {
        return contentType;
    }

    public String getAssetVersion()
    {
        return assetVersion;
    }

    public boolean isCurrentVersionRequested()
    {
        return currentVersionRequested;
    }

    /** SVGs can carry scripts, so they are served sandboxed. */
    public boolean isSvg()
    {
        return BrandingServiceImpl.SVG.equals(contentType);
    }

    @Override
    public String getFilename()
    {
        return fileName;
    }
}
