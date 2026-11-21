package bw.co.starsign.ratelimit;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.HttpMethod;

/**
 * Request rate limits, enforced per client: the token subject for signed-in callers, the client IP otherwise.
 * <p>
 * Every request is checked against the default limit ({@link #requestsPerSecond} with a burst of the same size);
 * requests matching a {@link Rule} must also fit that rule's limit.
 * <p>
 * A class rather than a record so it can be proxied (records are final).
 */
@ConfigurationProperties("app.rate-limiting")
public class RateLimitProperties {

    /** Turns rate limiting off entirely. */
    private boolean enabled = true;

    /** Sustained requests per second per client; also the burst size. */
    private long requestsPerSecond = 20;

    /** Stricter limits for particular endpoints, applied on top of the default. */
    private List<Rule> rules = new ArrayList<>();

    /**
     * @param name     identifies the rule in metrics and in the Redis key
     * @param method   HTTP method, or null for any
     * @param pattern  path pattern, e.g. {@code /organisations} or {@code /signing/invitations/**}
     * @param capacity requests allowed per {@code period}
     * @param period   window over which {@code capacity} refills
     */
    public record Rule(String name, HttpMethod method, String pattern, long capacity, Duration period) {
    }

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long requestsPerSecond() {
        return requestsPerSecond;
    }

    public void setRequestsPerSecond(long requestsPerSecond) {
        this.requestsPerSecond = requestsPerSecond;
    }

    public List<Rule> rules() {
        return rules;
    }

    public void setRules(List<Rule> rules) {
        this.rules = rules;
    }
}
