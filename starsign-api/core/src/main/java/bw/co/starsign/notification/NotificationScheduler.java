package bw.co.starsign.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sends due signing reminders and escalations, and requeues notifications whose retry is due.
 */
@Component
public class NotificationScheduler
{
    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationScheduler.class);

    private final NotificationService notificationService;

    public NotificationScheduler(NotificationService notificationService)
    {
        this.notificationService = notificationService;
    }

    @Scheduled(initialDelayString = "${app.notifications.initial-delay-ms:30000}",
            fixedDelayString = "${app.notifications.reminder-interval-ms:300000}")
    public void processDueReminders()
    {
        try
        {
            int sent = notificationService.processDueReminders();
            if (sent > 0)
            {
                LOGGER.info("Sent {} signing reminders", sent);
            }
        }
        catch (RuntimeException e)
        {
            LOGGER.error("Processing due signing reminders failed; it runs again at the next tick", e);
        }
    }

    @Scheduled(initialDelayString = "${app.notifications.initial-delay-ms:30000}",
            fixedDelayString = "${app.notifications.retry-interval-ms:30000}")
    public void retryFailed()
    {
        try
        {
            int requeued = notificationService.retryFailed();
            if (requeued > 0)
            {
                LOGGER.info("Requeued {} notifications for another attempt", requeued);
            }
        }
        catch (RuntimeException e)
        {
            LOGGER.error("Requeuing notifications for retry failed; it runs again at the next tick", e);
        }
    }
}
