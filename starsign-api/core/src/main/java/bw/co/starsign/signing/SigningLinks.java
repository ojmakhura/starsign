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

    /** Internal signatories sign in the portal with their own account. */
    public String internalSigningLink(String workflowId, String signatoryId)
    {
        return StringUtils.removeEnd(portalUrl, "/") + "/signing/workflows/" + workflowId + "/signatories/" + signatoryId;
    }
}
