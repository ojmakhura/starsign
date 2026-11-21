package bw.co.starsign.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Connection of {@link KeycloakAdminServiceImpl} to the Keycloak admin API ({@code app.keycloak-admin.*}): the
 * service account of the confidential {@code starsign-api} client, client-credentials grant.
 */
@Component
@ConfigurationProperties("app.keycloak-admin")
public class KeycloakAdminProperties
{
    /** Keycloak base URL, e.g. {@code http://localhost:8080} (no {@code /realms/...}). */
    private String serverUrl;

    private String realm = "starsign";

    private String clientId = "starsign-api";

    private String clientSecret;

    /** Client whose login page the Keycloak action emails return to. */
    private String portalClientId = "starsign-portal";

    /** How long the links in Keycloak action emails stay valid (seconds). */
    private int actionLifespanSeconds = 7 * 24 * 3600;

    public String getServerUrl()
    {
        return serverUrl;
    }

    public void setServerUrl(String serverUrl)
    {
        this.serverUrl = serverUrl;
    }

    public String getRealm()
    {
        return realm;
    }

    public void setRealm(String realm)
    {
        this.realm = realm;
    }

    public String getClientId()
    {
        return clientId;
    }

    public void setClientId(String clientId)
    {
        this.clientId = clientId;
    }

    public String getClientSecret()
    {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret)
    {
        this.clientSecret = clientSecret;
    }

    public String getPortalClientId()
    {
        return portalClientId;
    }

    public void setPortalClientId(String portalClientId)
    {
        this.portalClientId = portalClientId;
    }

    public int getActionLifespanSeconds()
    {
        return actionLifespanSeconds;
    }

    public void setActionLifespanSeconds(int actionLifespanSeconds)
    {
        this.actionLifespanSeconds = actionLifespanSeconds;
    }
}
