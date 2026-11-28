package bw.co.starsign.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.novu.Novu;
import org.junit.jupiter.api.Test;

class NovuNotifierTest
{
    private static NovuNotifier notifier(boolean enabled, String secretKey, String applicationIdentifier)
    {
        NovuProperties properties = new NovuProperties();
        properties.setEnabled(enabled);
        properties.setSecretKey(secretKey);
        properties.setApplicationIdentifier(applicationIdentifier);
        return new NovuNotifier(Novu.builder().serverURL("http://localhost").secretKey("x").build(), properties);
    }

    @Test
    void subscriberHashIsHexHmacSha256OfTheSubscriberId()
    {
        // RFC 4231 test case 2: key "Jefe", data "what do ya want for nothing?"
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
                notifier(true, "Jefe", "app").subscriberHash("what do ya want for nothing?"));
    }

    @Test
    void everyChannelOfAnEventSharesItsWorkflow()
    {
        assertEquals("starsign-workflow-completed",
                notifier(true, "k", "app").workflowId(NotificationEventType.WORKFLOW_COMPLETED));
    }

    @Test
    void inboxNeedsNovuTheApplicationIdentifierAndTheSecretKey()
    {
        assertTrue(notifier(true, "k", "app").isInboxEnabled());
        assertFalse(notifier(false, "k", "app").isInboxEnabled());
        assertFalse(notifier(true, "k", " ").isInboxEnabled());
        assertFalse(notifier(true, "", "app").isInboxEnabled());
    }
}
