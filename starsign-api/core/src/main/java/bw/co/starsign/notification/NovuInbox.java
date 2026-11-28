package bw.co.starsign.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * A portal user's Novu in-app inbox, read server-side: the browser only talks to StarSign. StarSign opens an
 * inbox session as the user (their Keycloak id, with the HMAC hash of the secret key), so it sees exactly what
 * that user's inbox holds, and uses the same session token for the live socket (NotificationStream).
 */
@Component
public class NovuInbox
{
    private static final Logger LOGGER = LoggerFactory.getLogger(NovuInbox.class);

    /** Novu message ids are Mongo ObjectIds; StarSign's own notification ids are UUIDs. */
    private static final Pattern MESSAGE_ID = Pattern.compile("^[0-9a-f]{24}$");

    /** Kept well under Novu's session lifetime (SUBSCRIBER_WIDGET_JWT_EXPIRATION_TIME); a 401 renews it anyway. */
    private static final Duration SESSION_REUSE = Duration.ofHours(1);

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private record Session(String token, Instant renewAfter)
    {
    }

    private final NovuNotifier novuNotifier;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final Map<String, Session> sessions = new ConcurrentHashMap<String, Session>();

    public NovuInbox(NovuNotifier novuNotifier)
    {
        this.novuNotifier = novuNotifier;
    }

    /** In-app notifications are delivered to Novu inboxes (NovuNotifier#isInboxEnabled). */
    public boolean isEnabled()
    {
        return novuNotifier.isInboxEnabled();
    }

    public static boolean isMessageId(String id)
    {
        return id != null && MESSAGE_ID.matcher(id).matches();
    }

    /** The Novu socket.io address StarSign connects to (app.novu.socket-url). */
    public String socketUrl()
    {
        return StringUtils.trimToNull(novuNotifier.properties().getSocketUrl());
    }

    /** The newest in-app messages of the subscriber, unread ones only when asked. */
    public List<NotificationDTO> list(String subscriberId, boolean unreadOnly, int limit)
    {
        String query = "limit=" + limit + "&archived=false" + (unreadOnly ? "&read=false" : "");
        JsonNode body = send(subscriberId, "GET", "/inbox/notifications?" + query, null);
        List<NotificationDTO> list = new ArrayList<NotificationDTO>();
        for (JsonNode message : body.path("data"))
        {
            list.add(toDto(message));
        }
        return list;
    }

    public int unreadCount(String subscriberId)
    {
        String filters = URLEncoder.encode("[{\"read\":false,\"archived\":false}]", StandardCharsets.UTF_8);
        JsonNode body = send(subscriberId, "GET", "/inbox/notifications/count?filters=" + filters, null);
        return body.path("data").path(0).path("count").asInt(0);
    }

    /** Marks one message read and returns it. */
    public JsonNode read(String subscriberId, String messageId)
    {
        if (!isMessageId(messageId))
        {
            throw new IllegalArgumentException("Not a Novu message id: " + messageId);
        }
        return send(subscriberId, "PATCH", "/inbox/notifications/" + messageId + "/read", null).path("data");
    }

    public void readAll(String subscriberId)
    {
        send(subscriberId, "POST", "/inbox/notifications/read", "{}");
    }

    /** A session token for the subscriber's inbox, for the REST calls and the socket. */
    public String token(String subscriberId)
    {
        Session session = sessions.get(subscriberId);
        if (session != null && Instant.now().isBefore(session.renewAfter()))
        {
            return session.token();
        }
        NovuProperties properties = novuNotifier.properties();
        String request = json.createObjectNode()
                .put("applicationIdentifier", StringUtils.trim(properties.getApplicationIdentifier()))
                .put("subscriberId", subscriberId)
                .put("subscriberHash", novuNotifier.subscriberHash(subscriberId))
                .toString();
        JsonNode body = exchange("POST", "/inbox/session", request, null);
        String token = body.path("data").path("token").asText(null);
        if (StringUtils.isBlank(token))
        {
            throw new IllegalStateException("Novu opened no inbox session for the subscriber");
        }
        sessions.put(subscriberId, new Session(token, Instant.now().plus(SESSION_REUSE)));
        return token;
    }

    /** Drops the cached session, e.g. after Novu refused its token. */
    public void forget(String subscriberId)
    {
        sessions.remove(subscriberId);
    }

    /**
     * A Novu in-app message as a StarSign notification. Its id is the Novu message id (what marking it read
     * needs); the step's data carries StarSign's notification id, event type and portal path.
     */
    public NotificationDTO toDto(JsonNode message)
    {
        JsonNode data = message.path("data");
        NotificationDTO dto = new NotificationDTO();
        dto.setId(text(message, "id", "_id"));
        dto.setChannel(NotificationChannel.IN_APP);
        dto.setStatus(NotificationStatus.DELIVERED);
        dto.setEventType(eventType(text(data, "eventType")));
        String subject = StringUtils.firstNonBlank(text(message, "subject"), text(message, "body", "content"));
        dto.setSubject(StringUtils.abbreviate(subject, 255));
        dto.setLinkPath(StringUtils.trimToNull(text(data, "linkPath")));
        dto.setWorkflowId(StringUtils.trimToNull(text(message.path("payload"), "workflowId")));
        dto.setCreatedAt(time(text(message, "createdAt")));
        dto.setDeliveredAt(time(text(message, "deliveredAt")));
        boolean read = message.path("isRead").asBoolean(message.path("read").asBoolean(false));
        if (read)
        {
            LocalDateTime readAt = time(text(message, "readAt", "lastReadDate"));
            dto.setReadAt(readAt != null ? readAt : dto.getCreatedAt());
        }
        return dto;
    }

    /** StarSign's notification id carried in a message's data, if any. */
    public static String notificationId(JsonNode message)
    {
        return StringUtils.trimToNull(text(message.path("data"), "notificationId"));
    }

    // ---------------------------------------------------------------------------------------------

    /** A call as the subscriber; a refused (expired) session is renewed once. */
    private JsonNode send(String subscriberId, String method, String path, String body)
    {
        try
        {
            return exchange(method, path, body, token(subscriberId));
        }
        catch (SessionRefused e)
        {
            forget(subscriberId);
            return exchange(method, path, body, token(subscriberId));
        }
    }

    private JsonNode exchange(String method, String path, String body, String token)
    {
        String base = StringUtils.removeEnd(StringUtils.defaultIfBlank(novuNotifier.properties().getBaseUrl(),
                "https://api.novu.co"), "/");
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + "/v1" + path))
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (body != null)
        {
            request.header("Content-Type", "application/json");
        }
        if (token != null)
        {
            request.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response;
        try
        {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
        catch (IOException e)
        {
            throw new IllegalStateException("Novu inbox " + method + " " + path + " failed: " + e, e);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted calling the Novu inbox", e);
        }
        if (response.statusCode() == 401 && token != null)
        {
            throw new SessionRefused();
        }
        if (response.statusCode() >= 300)
        {
            LOGGER.warn("Novu inbox {} {} answered {}: {}", method, path, response.statusCode(),
                    StringUtils.abbreviate(response.body(), 300));
            throw new IllegalStateException("Novu inbox " + method + " " + path + " answered " + response.statusCode());
        }
        try
        {
            return StringUtils.isBlank(response.body()) ? json.createObjectNode() : json.readTree(response.body());
        }
        catch (IOException e)
        {
            throw new IllegalStateException("Novu inbox answered " + method + " " + path + " with invalid JSON", e);
        }
    }

    private static final class SessionRefused extends RuntimeException
    {
        private static final long serialVersionUID = 1L;

        SessionRefused()
        {
            super("Novu refused the inbox session", null, false, false);
        }
    }

    private static String text(JsonNode node, String... names)
    {
        for (String name : names)
        {
            JsonNode value = node.path(name);
            if (value.isValueNode() && !value.isNull() && StringUtils.isNotBlank(value.asText()))
            {
                return value.asText();
            }
        }
        return null;
    }

    private static NotificationEventType eventType(String name)
    {
        if (StringUtils.isBlank(name))
        {
            return null;
        }
        try
        {
            return NotificationEventType.valueOf(name.trim());
        }
        catch (IllegalArgumentException e)
        {
            return null;
        }
    }

    private static LocalDateTime time(String iso)
    {
        if (StringUtils.isBlank(iso))
        {
            return null;
        }
        try
        {
            return OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }
}
