package bw.co.starsign.messaging;

/**
 * Published (as a Spring application event) when a new outbox event is written. {@link OutboxRelay}
 * listens for it after the enqueuing transaction commits and runs straight away, so delivery does
 * not wait for the fallback poll.
 *
 * @param eventId the id of the new outbox event
 */
public record OutboxEventEnqueued(String eventId)
{
}
