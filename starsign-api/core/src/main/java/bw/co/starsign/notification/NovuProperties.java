package bw.co.starsign.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The {@code app.novu.*} settings: Novu delivers every email and SMS notification, and the
 * in-app ones when an application identifier is set (the portal reads them from Novu through StarSign).
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
     * The Novu environment's application identifier (dashboard: API Keys). StarSign opens the users' inbox
     * sessions with it; in-app notifications go through Novu only when it is set.
     */
    private String applicationIdentifier;

    /**
     * Novu's socket.io address (the ws service), which StarSign connects to as each user with the portal open
     * and relays to the portal. Without it the portal's unread count is polled instead.
     */
    private String socketUrl;

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

    public String getSocketUrl()
    {
        return socketUrl;
    }

    public void setSocketUrl(String socketUrl)
    {
        this.socketUrl = socketUrl;
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
