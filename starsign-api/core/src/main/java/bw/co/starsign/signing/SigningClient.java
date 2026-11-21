package bw.co.starsign.signing;

/**
 * The browser behind the current signing request, bound by the signing controllers for the duration of a call.
 * <p>
 * The signing service operations do not take the client's address and user agent as parameters, but the
 * evidence of a signature must record them; the signing session cookie (an {@link AccessToken} issued after
 * verification) travels the same way, in both directions. Outside a signing request (scheduled jobs, other
 * services) nothing is bound and every value is null.
 */
public final class SigningClient implements AutoCloseable
{
    private static final ThreadLocal<SigningClient> CURRENT = new ThreadLocal<>();
    private static final SigningClient NONE = new SigningClient(null, null, null);

    private final String ipAddress;
    private final String userAgent;
    private final String sessionToken;
    private String issuedSessionToken;
    private java.time.LocalDateTime issuedSessionExpiresAt;

    private SigningClient(String ipAddress, String userAgent, String sessionToken)
    {
        this.ipAddress = ipAddress;
        this.userAgent = userAgent == null || userAgent.length() <= 500 ? userAgent : userAgent.substring(0, 500);
        this.sessionToken = sessionToken;
    }

    /**
     * Binds the client to the current thread; close it (try-with-resources) when the request is done.
     *
     * @param sessionToken the signing session cookie sent by the browser, if any
     */
    public static SigningClient bind(String ipAddress, String userAgent, String sessionToken)
    {
        SigningClient client = new SigningClient(ipAddress, userAgent, sessionToken);
        CURRENT.set(client);
        return client;
    }

    /** The client bound to this thread, or an empty one. */
    public static SigningClient current()
    {
        SigningClient client = CURRENT.get();
        return client == null ? NONE : client;
    }

    public String ipAddress()
    {
        return ipAddress;
    }

    public String userAgent()
    {
        return userAgent;
    }

    /** The signing session token the browser presented (raw value, never stored or logged). */
    public String sessionToken()
    {
        return sessionToken;
    }

    /** A signing session token issued during this request, for the controller to set as a cookie. */
    public String issuedSessionToken()
    {
        return issuedSessionToken;
    }

    public java.time.LocalDateTime issuedSessionExpiresAt()
    {
        return issuedSessionExpiresAt;
    }

    void issueSession(String token, java.time.LocalDateTime expiresAt)
    {
        // The shared empty client must never carry a token from one caller to the next
        if (this != NONE)
        {
            this.issuedSessionToken = token;
            this.issuedSessionExpiresAt = expiresAt;
        }
    }

    @Override
    public void close()
    {
        CURRENT.remove();
    }
}
