package bw.co.starsign.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The {@code app.novu.*} settings: Novu delivers every email and SMS notification, and the
 * in-app ones when an application identifier is set (the portal's inbox reads them from Novu).
 */
@ConfigurationProperties(prefix = "app.novu")
public class NovuProperties
{
    /** When false nothing is sent to Novu: notifications are logged and recorded as SUPPRESSED. */
    private boolean enabled;

    private String secretKey;

    private String baseUrl = "https://api.novu.co";

    /** Workflow id = prefix + the event type in kebab case, e.g. starsign-signing-invitation. */
    private String workflowPrefix = "starsign-";

    /**
     * The Novu environment's application identifier (dashboard: API Keys). The portal's inbox connects
     * with it; in-app notifications go through Novu only when it is set.
     */
    private String applicationIdentifier;

    /** The Novu API address the browser uses (the portal's inbox); defaults to base-url. */
    private String publicApiUrl;

    /** The Novu WebSocket address the browser uses for live updates; Novu's default when blank. */
    private String publicSocketUrl;

    /**
     * Whether text messages are sent. Off until Novu has an SMS integration: SMS notifications are recorded as
     * SUPPRESSED, and invitations, reminders and verification codes go by email instead.
     */
    private boolean smsEnabled;

    public boolean isEnabled()
    {
        return enabled;
    }

    public void setEnabled(boolean enabled)
    {
        this.enabled = enabled;
    }

    public String getSecretKey()
    {
        return secretKey;
    }

    public void setSecretKey(String secretKey)
    {
        this.secretKey = secretKey;
    }

    public String getBaseUrl()
    {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl)
    {
        this.baseUrl = baseUrl;
    }

    public String getWorkflowPrefix()
    {
        return workflowPrefix;
    }

    public void setWorkflowPrefix(String workflowPrefix)
    {
        this.workflowPrefix = workflowPrefix;
    }

    public String getApplicationIdentifier()
    {
        return applicationIdentifier;
    }

    public void setApplicationIdentifier(String applicationIdentifier)
    {
        this.applicationIdentifier = applicationIdentifier;
    }

    public String getPublicApiUrl()
    {
        return publicApiUrl;
    }

    public void setPublicApiUrl(String publicApiUrl)
    {
        this.publicApiUrl = publicApiUrl;
    }

    public String getPublicSocketUrl()
    {
        return publicSocketUrl;
    }

    public void setPublicSocketUrl(String publicSocketUrl)
    {
        this.publicSocketUrl = publicSocketUrl;
    }

    public boolean isSmsEnabled()
    {
        return smsEnabled;
    }

    public void setSmsEnabled(boolean smsEnabled)
    {
        this.smsEnabled = smsEnabled;
    }
}
