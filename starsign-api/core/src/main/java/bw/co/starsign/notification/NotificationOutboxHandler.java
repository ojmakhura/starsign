package bw.co.starsign.notification;

import bw.co.starsign.messaging.OutboxEventDTO;
import bw.co.starsign.messaging.OutboxEventHandler;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Sends the notifications queued through the outbox. Gateway failures do not throw: the
 * notification schedules its own retry ({@link NotificationService#retryFailed}).
 */
@Component
public class NotificationOutboxHandler
    implements OutboxEventHandler
{
    private final NotificationService notificationService;

    public NotificationOutboxHandler(@Lazy NotificationService notificationService)
    {
        this.notificationService = notificationService;
    }

    @Override
    public String consumerName()
    {
        return "notification-dispatcher";
    }

    @Override
    public boolean accepts(String routingKey)
    {
        return NotificationServiceImpl.DISPATCH_ROUTING_KEY.equals(routingKey);
    }

    @Override
    public void handle(OutboxEventDTO event)
    {
        notificationService.dispatch(event.getAggregateId());
    }
}
