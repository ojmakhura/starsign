package bw.co.starsign.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The {@code app.novu.*} settings: Novu delivers every email and SMS notification.
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
}
