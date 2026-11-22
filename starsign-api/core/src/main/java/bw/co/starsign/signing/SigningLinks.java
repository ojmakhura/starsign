package bw.co.starsign.signing;

import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * {@code app.signing-portal-url} and {@code app.portal-url}: where the links in signing invitations point.
 */
@Component
@ConfigurationProperties(prefix = "app")
public class SigningLinks
{
    private String signingPortalUrl = "http://localhost:4700/sign";

    private String portalUrl = "http://localhost:4700";

    public String getSigningPortalUrl()
    {
        return signingPortalUrl;
    }

    public void setSigningPortalUrl(String signingPortalUrl)
    {
        this.signingPortalUrl = signingPortalUrl;
    }

    public String getPortalUrl()
    {
        return portalUrl;
    }

    public void setPortalUrl(String portalUrl)
    {
        this.portalUrl = portalUrl;
    }

    /** The external signing portal link carrying the invitation token. */
    public String invitationLink(String token)
    {
        return StringUtils.removeEnd(signingPortalUrl, "/") + "/" + token;
    }

    /**
     * Where browsers reach the API (app.public-api-url). Blank means behind the portal at {@code <portalUrl>/api},
     * which is how the portal's nginx (and the development proxy) serve it.
     */
    private String publicApiUrl;

    public String getPublicApiUrl()
    {
        return publicApiUrl;
    }

    public void setPublicApiUrl(String publicApiUrl)
    {
        this.publicApiUrl = publicApiUrl;
    }

    /** Direct download of the completed document with a download-only token (see CompletedCopyLinks). */
    public String completedCopyLink(String token)
    {
        String api = StringUtils.isNotBlank(publicApiUrl) ? publicApiUrl : StringUtils.removeEnd(portalUrl, "/") + "/api";
        return StringUtils.removeEnd(api, "/") + "/signing/invitations/" + token + "/completed";
    }

    /** Internal signatories sign in the portal with their own account. */
    public String internalSigningLink(String workflowId, String signatoryId)
    {
        return StringUtils.removeEnd(portalUrl, "/") + "/signing/workflows/" + workflowId + "/signatories/" + signatoryId;
    }
}
