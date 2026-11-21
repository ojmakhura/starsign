package bw.co.starsign.messaging;

import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the outbox relay and housekeeping on a schedule (scheduling is enabled by
 * bw.co.starsign.notification.NotificationConfiguration).
 */
@Component
public class OutboxRelay
{
    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH_SIZE = 100;
    private static final int KEEP_PUBLISHED_DAYS = 7;

    private final OutboxService outboxService;

    public OutboxRelay(OutboxService outboxService)
    {
        this.outboxService = outboxService;
    }

    @Scheduled(initialDelayString = "${app.outbox.initial-delay-ms:10000}",
            fixedDelayString = "${app.outbox.poll-interval-ms:5000}")
    public void publishPending()
    {
        try
        {
            int published;
            // Keep going while full batches come back, so a backlog drains without waiting for the next tick
            do
            {
                published = outboxService.publishPending(BATCH_SIZE);
            }
            while (published >= BATCH_SIZE);
        }
        catch (RuntimeException e)
        {
            LOGGER.error("The outbox relay failed; it runs again at the next tick", e);
        }
    }

    @Scheduled(cron = "${app.outbox.purge-cron:0 30 3 * * *}")
    public void purgePublished()
    {
        try
        {
            int purged = outboxService.purgePublished(LocalDateTime.now().minusDays(KEEP_PUBLISHED_DAYS));
            LOGGER.info("Purged {} published outbox events", purged);
        }
        catch (RuntimeException e)
        {
            LOGGER.error("Purging published outbox events failed", e);
        }
    }
}
