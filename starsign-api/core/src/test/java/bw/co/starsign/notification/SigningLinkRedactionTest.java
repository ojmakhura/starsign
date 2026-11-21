package bw.co.starsign.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class SigningLinkRedactionTest
{
    private static final String PORTAL = "http://localhost:4700/sign";
    private static final String TOKEN = "Zp3K9x_-AbCdEfGhIjKlMnOpQrStUvWxYz0123456789";

    @Test
    void removesTheTokenFromLinksOnTheSigningPortal()
    {
        String body = "<p>Open " + PORTAL + "/" + TOKEN + " to review and sign it.</p>";

        String redacted = NotificationServiceImpl.redactSigningLinks(body, PORTAL);

        assertFalse(redacted.contains(TOKEN));
        assertEquals("<p>Open " + PORTAL + "/" + NotificationServiceImpl.REDACTED_TOKEN + " to review and sign it.</p>",
                redacted);
    }

    @Test
    void removesTokensFromSignLinksOnAnotherHost()
    {
        String body = "Open https://sign.example.com/sign/" + TOKEN + "\nOr https://sign.example.com/sign/" + TOKEN;

        String redacted = NotificationServiceImpl.redactSigningLinks(body, PORTAL);

        assertFalse(redacted.contains(TOKEN));
    }

    @Test
    void leavesOtherLinksAndEmptyBodiesAlone()
    {
        String body = "Open http://localhost:4700/signing/workflows/abc/signatories/def to sign.";

        assertEquals(body, NotificationServiceImpl.redactSigningLinks(body, PORTAL));
        assertNull(NotificationServiceImpl.redactSigningLinks(null, PORTAL));
    }
}
