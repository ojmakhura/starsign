package bw.co.starsign.messaging;

/**
 * A consumer of outbox events. The relay ({@link OutboxService#publishPending}) hands each due event to
 * every handler that accepts its routing key, at most once per handler (tracked as a ProcessedMessage).
 * A handler that throws makes the relay retry the event later with backoff.
 */
public interface OutboxEventHandler
{
    /** Stable name, recorded with each processed event; changing it makes old events look new. */
    String consumerName();

    boolean accepts(String routingKey);

    void handle(OutboxEventDTO event);
}
