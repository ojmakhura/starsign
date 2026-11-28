// license-header java merge-point
//
package bw.co.starsign.notification;

import java.io.Serializable;
import org.jspecify.annotations.Nullable;

/**
 * What the portal needs to open the signed-in user's Novu inbox. When enabled is false, in-app notifications are
 * not in Novu and the portal lists them from StarSign (GET /notifications/me).
 */
public class NotificationInboxDTO
    implements Serializable
{
    /** The serial version UID of this class. Needed for serialization. */
    private static final long serialVersionUID = 4183402212766591034L;

    // Class attributes
    /**
     * Whether in-app notifications are delivered through Novu.
     */
    @Nullable
    protected Boolean enabled;
    /**
     * The Novu environment's application identifier.
     */
    @Nullable
    protected String applicationIdentifier;
    /**
     * The user's Novu subscriber id (their Keycloak user id).
     */
    @Nullable
    protected String subscriberId;
    /**
     * HMAC-SHA256 of the subscriber id, keyed with the Novu secret key.
     */
    @Nullable
    protected String subscriberHash;
    /**
     * The Novu API address the browser uses; Novu Cloud when blank.
     */
    @Nullable
    protected String backendUrl;
    /**
     * The Novu WebSocket address the browser uses; Novu Cloud when blank.
     */
    @Nullable
    protected String socketUrl;

    /**
     * Constructor taking only required properties
     */
    public NotificationInboxDTO()
    {
        // Documented empty block - avoid compiler warning - no super constructor
    }

    public Boolean getEnabled()
    {
        return this.enabled;
    }

    public void setEnabled(Boolean value)
    {
        this.enabled = value;
    }

    public String getApplicationIdentifier()
    {
        return this.applicationIdentifier;
    }

    public void setApplicationIdentifier(String value)
    {
        this.applicationIdentifier = value;
    }

    public String getSubscriberId()
    {
        return this.subscriberId;
    }

    public void setSubscriberId(String value)
    {
        this.subscriberId = value;
    }

    public String getSubscriberHash()
    {
        return this.subscriberHash;
    }

    public void setSubscriberHash(String value)
    {
        this.subscriberHash = value;
    }

    public String getBackendUrl()
    {
        return this.backendUrl;
    }

    public void setBackendUrl(String value)
    {
        this.backendUrl = value;
    }

    public String getSocketUrl()
    {
        return this.socketUrl;
    }

    public void setSocketUrl(String value)
    {
        this.socketUrl = value;
    }

    @Override
    public String toString()
    {
        // The hash is a credential for the inbox: not logged
        return "NotificationInboxDTO[enabled=" + enabled + ", subscriberId=" + subscriberId + "]";
    }
}
