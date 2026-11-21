package bw.co.starsign.ratelimit;

import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.stereotype.Component;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;

/**
 * Token buckets kept in Redis, so a limit holds across every instance of the API.
 * <p>
 * If Redis cannot be reached the request is allowed (and a warning logged): an outage of the cache must not
 * take the API down with it.
 */
@Component
public class RateLimiter {

    private static final Logger logger = LoggerFactory.getLogger(RateLimiter.class);

    private static final String KEY_PREFIX = "starsign:rate-limit:";

    /** Outcome of a check; {@code retryAfter} is set when the request was refused. */
    public record Decision(boolean allowed, long remaining, Duration retryAfter) {

        static Decision allow(long remaining) {
            return new Decision(true, remaining, Duration.ZERO);
        }
    }

    private final RedisConnectionFactory connectionFactory;

    private volatile ProxyManager<String> buckets;

    public RateLimiter(RedisConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    /**
     * Takes one request from the client's bucket for this limit.
     *
     * @param limit    name of the limit (part of the Redis key)
     * @param client   who is calling: {@code user:<subject>} or {@code ip:<address>}
     * @param capacity requests allowed per {@code period}, which is also the burst size
     */
    public Decision tryConsume(String limit, String client, long capacity, Duration period) {

        Optional<ProxyManager<String>> manager = buckets();
        if (manager.isEmpty()) {
            return Decision.allow(capacity);
        }

        BucketConfiguration configuration = BucketConfiguration.builder()
                .addLimit(Bandwidth.builder().capacity(capacity).refillGreedy(capacity, period).build())
                .build();

        try {
            ConsumptionProbe probe = manager.get().builder()
                    .build(KEY_PREFIX + limit + ":" + client, () -> configuration)
                    .tryConsumeAndReturnRemaining(1);

            return probe.isConsumed()
                    ? Decision.allow(probe.getRemainingTokens())
                    : new Decision(false, 0, Duration.ofNanos(probe.getNanosToWaitForRefill()));

        } catch (RuntimeException e) {
            logger.warn("Rate limit check for {} failed; allowing the request", limit, e);
            return Decision.allow(capacity);
        }
    }

    private Optional<ProxyManager<String>> buckets() {
        ProxyManager<String> current = buckets;
        if (current != null) {
            return Optional.of(current);
        }
        synchronized (this) {
            if (buckets == null) {
                try {
                    buckets = createProxyManager();
                } catch (RuntimeException e) {
                    logger.warn("Rate limiting unavailable: cannot connect to Redis", e);
                    return Optional.empty();
                }
            }
            return Optional.of(buckets);
        }
    }

    private ProxyManager<String> createProxyManager() {
        if (!(connectionFactory instanceof LettuceConnectionFactory lettuce)
                || !(lettuce.getNativeClient() instanceof RedisClient client)) {
            throw new IllegalStateException("Rate limiting needs a standalone Lettuce Redis connection");
        }
        StatefulRedisConnection<String, byte[]> connection =
                client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
        return Bucket4jLettuce.casBasedBuilder(connection)
                // Drop a client's bucket from Redis once it would be full again anyway
                .expirationAfterWrite(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(
                        Duration.ofSeconds(10)))
                .build();
    }
}
