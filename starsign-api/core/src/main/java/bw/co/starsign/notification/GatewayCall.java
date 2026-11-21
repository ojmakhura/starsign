package bw.co.starsign.notification;

import java.util.Map;
import java.util.function.Supplier;

/**
 * What a gateway needs to trigger the right Novu workflow but its generated signature cannot carry
 * ({@link SmsGateway#send} has no event type or headers). Set around a single gateway call on the
 * calling thread and always cleared afterwards.
 */
final class GatewayCall
{
    /**
     * @param workflowId the Novu workflow to trigger
     * @param transactionId the Novu transaction id (idempotency key of the trigger)
     * @param recipientName the subscriber's name, when known
     * @param payload extra workflow payload (never secrets that are not part of the message itself)
     */
    record Hint(String workflowId, String transactionId, String recipientName, Map<String, Object> payload)
    {
    }

    private static final ThreadLocal<Hint> CURRENT = new ThreadLocal<Hint>();

    private GatewayCall()
    {
    }

    static <T> T with(Hint hint, Supplier<T> call)
    {
        Hint previous = CURRENT.get();
        CURRENT.set(hint);
        try
        {
            return call.get();
        }
        finally
        {
            if (previous == null)
            {
                CURRENT.remove();
            }
            else
            {
                CURRENT.set(previous);
            }
        }
    }

    static Hint current()
    {
        return CURRENT.get();
    }
}
