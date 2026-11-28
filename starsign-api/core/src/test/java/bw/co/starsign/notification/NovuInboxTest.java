package bw.co.starsign.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.novu.Novu;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class NovuInboxTest
{
    private final NovuInbox inbox = new NovuInbox(new NovuNotifier(
            Novu.builder().serverURL("http://localhost").secretKey("x").build(), new NovuProperties()));

    private static JsonNode json(String text) throws Exception
    {
        return new ObjectMapper().readTree(text);
    }

    @Test
    void mapsAnInboxMessage() throws Exception
    {
        JsonNode message = json("""
                {"id": "6b0ac50a9ac600b88014ae25", "subject": "Signed by everyone", "body": "Hello",
                 "isRead": true, "readAt": "2026-11-28T12:12:01.600Z", "createdAt": "2026-11-28T12:11:54.234Z",
                 "data": {"notificationId": "01a1fec1-5aa9-7444-a911-d48faedcd093", "eventType": "WORKFLOW_COMPLETED",
                          "linkPath": ""}}
                """);
        NotificationDTO dto = inbox.toDto(message);

        assertEquals("6b0ac50a9ac600b88014ae25", dto.getId());
        assertEquals(NotificationEventType.WORKFLOW_COMPLETED, dto.getEventType());
        assertEquals(NotificationChannel.IN_APP, dto.getChannel());
        assertEquals("Signed by everyone", dto.getSubject());
        assertNull(dto.getLinkPath());
        assertNotNull(dto.getCreatedAt());
        assertNotNull(dto.getReadAt());
        assertEquals("01a1fec1-5aa9-7444-a911-d48faedcd093", NovuInbox.notificationId(message));
    }

    @Test
    void mapsASocketMessageThatIsUnread() throws Exception
    {
        // notification_received carries the raw message: _id, content, read
        NotificationDTO dto = inbox.toDto(json("""
                {"_id": "6b0ac50a9ac600b88014ae26", "content": "A document is waiting", "read": false,
                 "createdAt": "2026-11-28T12:11:54.234Z",
                 "data": {"eventType": "NOT_A_STARSIGN_EVENT", "linkPath": "/signing/workflows/1"}}
                """));

        assertEquals("6b0ac50a9ac600b88014ae26", dto.getId());
        assertEquals("A document is waiting", dto.getSubject());
        assertNull(dto.getEventType());
        assertNull(dto.getReadAt());
        assertEquals("/signing/workflows/1", dto.getLinkPath());
    }

    @Test
    void tellsNovuMessageIdsFromStarSignIds()
    {
        assertTrue(NovuInbox.isMessageId("6b0ac50a9ac600b88014ae25"));
        assertFalse(NovuInbox.isMessageId("01a1fec1-5aa9-7444-a911-d48faedcd093"));
        assertFalse(NovuInbox.isMessageId(null));
    }
}
