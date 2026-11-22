package bw.co.starsign.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EmailLayoutTest
{
    private static final EmailLayout.Brand STARSIGN = new EmailLayout.Brand("StarSign", "Rogue Systems",
            "https://portal.example.com/brand/email-logo.png", true, "Rogue Systems (Pty) Ltd\nGaborone",
            "help@example.com", "https://help.example.com");

    @Test
    void putsTheMessageBetweenTheLogoHeaderAndTheFooter()
    {
        String html = EmailLayout.wrap("<p>Hello Neo</p>", "Please sign", STARSIGN);

        assertTrue(html.startsWith("<!doctype html>"));
        assertTrue(html.contains("<title>Please sign</title>"));
        assertTrue(html.contains("<img src=\"https://portal.example.com/brand/email-logo.png\" width=\"200\" height=\"56\""));
        assertTrue(html.indexOf("email-logo.png") < html.indexOf("<p>Hello Neo</p>"));
        assertTrue(html.contains("Rogue Systems (Pty) Ltd<br>Gaborone"));
        assertTrue(html.contains("href=\"mailto:help@example.com\""));
        assertTrue(html.contains("href=\"https://help.example.com\""));
        assertTrue(html.contains("Sent by StarSign on behalf of Rogue Systems"));
    }

    @Test
    void leavesWholeDocumentsAlone()
    {
        String document = "<!doctype html><HTML><body><p>Own layout</p></body></HTML>";
        assertTrue(EmailLayout.isDocument(document));
        assertEquals(document, EmailLayout.wrap(document, "Subject", STARSIGN));
        assertFalse(EmailLayout.isDocument("<p>The <b>body</b> text</p>"));
    }

    @Test
    void escapesBrandingAndDropsUnsafeLinks()
    {
        EmailLayout.Brand brand = new EmailLayout.Brand("<Acme>", "A & B", "javascript:alert(1)", false,
                "<script>x</script>", null, "javascript:alert(1)");
        String html = EmailLayout.wrap("<p>Hi</p>", "<Subject>", brand);

        assertFalse(html.contains("javascript:"));
        assertFalse(html.contains("<script>"));
        assertTrue(html.contains("&lt;Acme&gt;"));
        assertTrue(html.contains("on behalf of A &amp; B"));
        assertTrue(html.contains("<title>&lt;Subject&gt;</title>"));
    }

    @Test
    void sizesAnUploadedLogoByHeightOnly()
    {
        EmailLayout.Brand brand = new EmailLayout.Brand("Acme", null, "https://cdn.example.com/acme.png", false,
                null, null, null);
        String html = EmailLayout.wrap("<p>Hi</p>", "Hi", brand);

        assertTrue(html.contains("<img src=\"https://cdn.example.com/acme.png\" height=\"56\" alt=\"Acme\""));
        assertTrue(html.contains("Sent by Acme</p>"));
    }

    @Test
    void textFooterMatchesTheHtmlOne()
    {
        assertEquals("Rogue Systems (Pty) Ltd\nGaborone\n\nSupport: help@example.com\nHelp: https://help.example.com\n"
                + "Sent by StarSign on behalf of Rogue Systems", EmailLayout.textFooter(STARSIGN));
    }
}
