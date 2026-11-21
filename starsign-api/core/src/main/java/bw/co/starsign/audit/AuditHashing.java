package bw.co.starsign.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;

/**
 * Canonical serialisation and hashing of audit events for the tamper-evident chain.
 * <p>
 * The canonical form is a JSON object with sorted keys, no whitespace and only the fields that are
 * stored, so it can be recomputed from the database row at any time. The details column is jsonb,
 * which rewrites key order and spacing; details therefore enter the hash in their own canonical form
 * (sorted keys, numbers without trailing zeros), never as the raw string.
 */
final class AuditHashing
{
    /** previousHash of the first event of every chain. */
    static final String GENESIS_HASH = "0".repeat(64);

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS");

    /** Keys whose values must never reach the audit log (tokens, OTPs, secrets). */
    private static final Pattern SECRET_KEY = Pattern.compile(
            "(?i).*(token|otp|password|passcode|secret|credential|api[-_]?key|authorization).*");

    private AuditHashing()
    {
    }

    /**
     * PostgreSQL keeps microseconds; anything finer would be lost on reload and break the hash.
     */
    static LocalDateTime truncate(LocalDateTime timestamp)
    {
        return timestamp == null ? null : timestamp.truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * Turns the caller's details into the JSON stored in the jsonb column: valid JSON with secret
     * values redacted. Free text is wrapped as {"text": ...} because jsonb rejects anything else.
     */
    static String normaliseDetails(String details)
    {
        if (StringUtils.isBlank(details))
        {
            return null;
        }
        JsonNode node;
        try
        {
            node = JSON.readTree(details);
        }
        catch (JsonProcessingException notJson)
        {
            node = JSON.createObjectNode().put("text", details);
        }
        if (node == null || node.isMissingNode() || node.isNull())
        {
            return null;
        }
        return canonical(redact(node));
    }

    private static JsonNode redact(JsonNode node)
    {
        if (node.isObject())
        {
            ObjectNode copy = JSON.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext())
            {
                Map.Entry<String, JsonNode> field = fields.next();
                copy.set(field.getKey(), SECRET_KEY.matcher(field.getKey()).matches()
                        ? TextNode.valueOf("[REDACTED]")
                        : redact(field.getValue()));
            }
            return copy;
        }
        if (node.isArray())
        {
            List<JsonNode> items = new ArrayList<>();
            node.forEach(item -> items.add(redact(item)));
            return JSON.createArrayNode().addAll(items);
        }
        return node;
    }

    /**
     * SHA-256(previousHash || canonical JSON of the event), as documented on AuditEvent.eventHash.
     */
    static String eventHash(AuditEvent event)
    {
        return DigestUtils.sha256Hex(event.getPreviousHash() + canonicalEvent(event));
    }

    static String canonicalEvent(AuditEvent event)
    {
        Map<String, Object> fields = new TreeMap<>();
        fields.put("chainKey", event.getChainKey());
        fields.put("sequenceNumber", event.getSequenceNumber());
        fields.put("eventType", name(event.getEventType()));
        fields.put("occurredAt", event.getOccurredAt() == null ? null : TIMESTAMP.format(truncate(event.getOccurredAt())));
        fields.put("organisationId", event.getOrganisationId());
        fields.put("actorType", name(event.getActorType()));
        fields.put("actorId", event.getActorId());
        fields.put("actorDisplayName", event.getActorDisplayName());
        fields.put("authenticationMethod", name(event.getAuthenticationMethod()));
        fields.put("ipAddress", event.getIpAddress());
        fields.put("userAgent", event.getUserAgent());
        fields.put("outcome", name(event.getOutcome()));
        fields.put("documentId", event.getDocumentId());
        fields.put("workflowId", event.getWorkflowId());
        fields.put("signatoryId", event.getSignatoryId());
        fields.put("entityType", event.getEntityType());
        fields.put("entityId", event.getEntityId());
        fields.put("documentSha256", event.getDocumentSha256());
        fields.put("correlationId", event.getCorrelationId());

        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> field : fields.entrySet())
        {
            first = comma(out, first);
            out.append(TextNode.valueOf(field.getKey())).append(':');
            Object value = field.getValue();
            if (value == null)
            {
                out.append("null");
            }
            else if (value instanceof Number)
            {
                out.append(value);
            }
            else
            {
                out.append(TextNode.valueOf(value.toString()));
            }
        }
        comma(out, first);
        out.append("\"details\":").append(canonicalDetails(event.getDetails()));
        return out.append('}').toString();
    }

    private static String canonicalDetails(String details)
    {
        if (StringUtils.isBlank(details))
        {
            return "null";
        }
        try
        {
            return canonical(JSON.readTree(details));
        }
        catch (JsonProcessingException e)
        {
            // Stored details are always JSON (normaliseDetails); hash anything else verbatim
            return TextNode.valueOf(details).toString();
        }
    }

    /** JSON with sorted keys and no whitespace; numbers without trailing zeros. */
    static String canonical(JsonNode node)
    {
        StringBuilder out = new StringBuilder();
        write(node, out);
        return out.toString();
    }

    private static void write(JsonNode node, StringBuilder out)
    {
        if (node == null || node.isNull() || node.isMissingNode())
        {
            out.append("null");
        }
        else if (node.isObject())
        {
            Map<String, JsonNode> sorted = new TreeMap<>();
            node.fields().forEachRemaining(field -> sorted.put(field.getKey(), field.getValue()));
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonNode> field : sorted.entrySet())
            {
                first = comma(out, first);
                out.append(TextNode.valueOf(field.getKey())).append(':');
                write(field.getValue(), out);
            }
            out.append('}');
        }
        else if (node.isArray())
        {
            out.append('[');
            boolean first = true;
            for (JsonNode item : node)
            {
                first = comma(out, first);
                write(item, out);
            }
            out.append(']');
        }
        else if (node.isNumber())
        {
            out.append(node.decimalValue().stripTrailingZeros().toPlainString());
        }
        else if (node.isBoolean())
        {
            out.append(node.booleanValue());
        }
        else
        {
            out.append(TextNode.valueOf(node.asText()));
        }
    }

    private static boolean comma(StringBuilder out, boolean first)
    {
        if (!first)
        {
            out.append(',');
        }
        return false;
    }

    private static String name(Enum<?> value)
    {
        return value == null ? null : value.name();
    }

    static String sha256Hex(byte[] data)
    {
        return DigestUtils.sha256Hex(data);
    }

    static String sha256Hex(String data)
    {
        return DigestUtils.sha256Hex(data.getBytes(StandardCharsets.UTF_8));
    }
}
