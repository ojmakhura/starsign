package bw.co.starsign.messaging;

import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Runs the outbox relay and housekeeping (scheduling is enabled by
 * bw.co.starsign.notification.NotificationConfiguration).
 * <p>
 * The relay is woken as soon as a transaction that enqueued an event commits. The timed poll is only a
 * fallback: it picks up retries whose back-off has elapsed, events whose wake-up was lost (for example
 * a crash between the commit and the relay run), and events left behind by another instance.
 * <p>
 * Every run happens on one relay thread, and wake-ups that arrive while a run is queued are merged
 * into it, so a burst of events costs one drain rather than one per event.
 */
@Component
public class OutboxRelay
{
    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH_SIZE = 100;
    private static final int KEEP_PUBLISHED_DAYS = 7;

    private final OutboxService outboxService;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "outbox-relay");
        thread.setDaemon(true);
        return thread;
    });
    /** True while a run is queued but not yet started; further wake-ups until then are merged into it. */
    private final AtomicBoolean runQueued = new AtomicBoolean();

    public OutboxRelay(OutboxService outboxService)
    {
        this.outboxService = outboxService;
    }

    /**
     * Wakes the relay once the enqueuing transaction commits; outside a transaction it runs straight away.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onEnqueued(OutboxEventEnqueued event)
    {
        wake();
    }

    /** The fallback poll for retries and missed wake-ups. */
    @Scheduled(initialDelayString = "${app.outbox.initial-delay-ms:10000}",
            fixedDelayString = "${app.outbox.poll-interval-ms:60000}")
    public void poll()
    {
        wake();
    }

    /** Queues a relay run unless one is already queued. */
    public void wake()
    {
        if (!runQueued.compareAndSet(false, true))
        {
            return;
        }
        try
        {
            executor.execute(() -> {
                // Cleared before draining, so an event enqueued during this run queues another one
                runQueued.set(false);
                publishPending();
            });
        }
        catch (RejectedExecutionException e)
        {
            // Shutting down; the next start's poll publishes whatever is left
            runQueued.set(false);
        }
    }

    void publishPending()
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
            LOGGER.error("The outbox relay failed; it runs again on the next wake-up or poll", e);
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

    @PreDestroy
    void shutdown() throws InterruptedException
    {
        executor.shutdown();
        if (!executor.awaitTermination(10, TimeUnit.SECONDS))
        {
            executor.shutdownNow();
        }
    }
}
