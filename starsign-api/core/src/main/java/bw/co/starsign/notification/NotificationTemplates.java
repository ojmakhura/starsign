package bw.co.starsign.notification;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * Finds the template for a message (the organisation's own, else the platform default, best locale
 * first) and renders {@code {{name}}} placeholders. Values put into HTML are escaped; dotted names
 * ({@code {{signatory.name}}}) read nested maps; unknown names render as nothing.
 */
@Component
public class NotificationTemplates
{
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*\\}\\}");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final String DEFAULT_LOCALE = "en";

    /** A subject and body, ready to send. */
    public record Rendered(String subject, String body, String text)
    {
    }

    /** Built-in English wording, used when neither the organisation nor the platform has a template. */
    private static final Map<NotificationEventType, String[]> FALLBACK =
            new EnumMap<NotificationEventType, String[]>(NotificationEventType.class);
    static
    {
        FALLBACK.put(NotificationEventType.SIGNING_INVITATION, new String[] {
            "{{organisationName}} has sent you {{workflowTitle}} to sign",
            "Hello {{recipientName}},\n\n{{organisationName}} has sent you {{workflowTitle}} to sign.\n"
                + "Open {{link}} to review and sign it.\n\n{{message}}" });
        FALLBACK.put(NotificationEventType.SIGNING_REMINDER, new String[] {
            "Reminder: {{workflowTitle}} is waiting for your signature",
            "Hello {{recipientName}},\n\n{{workflowTitle}} from {{organisationName}} is still waiting for your "
                + "signature. Please use the link in your invitation to sign it.\n\n{{link}}" });
        FALLBACK.put(NotificationEventType.SIGNATURE_COMPLETED, new String[] {
            "{{workflowTitle}} has been signed by {{signatoryName}}",
            "Hello {{recipientName}},\n\n{{signatoryName}} has signed {{workflowTitle}}." });
        FALLBACK.put(NotificationEventType.NEXT_SIGNATORY_ACTIVATED, new String[] {
            "It is your turn to sign {{workflowTitle}}",
            "Hello {{recipientName}},\n\nIt is now your turn to sign {{workflowTitle}} from {{organisationName}}.\n"
                + "Open {{link}} to sign it." });
        FALLBACK.put(NotificationEventType.DOCUMENT_DECLINED, new String[] {
            "{{workflowTitle}} was declined",
            "Hello {{recipientName}},\n\n{{signatoryName}} declined to sign {{workflowTitle}}.\n\n{{reason}}" });
        FALLBACK.put(NotificationEventType.WORKFLOW_COMPLETED, new String[] {
            "{{workflowTitle}} has been signed by everyone",
            "Hello {{recipientName}},\n\nEveryone has signed {{workflowTitle}}.\n\n{{link}}" });
        FALLBACK.put(NotificationEventType.WORKFLOW_CANCELLED, new String[] {
            "{{workflowTitle}} was cancelled",
            "Hello {{recipientName}},\n\n{{organisationName}} has cancelled {{workflowTitle}}. "
                + "You no longer need to sign it.\n\n{{reason}}" });
        FALLBACK.put(NotificationEventType.WORKFLOW_EXPIRING, new String[] {
            "{{workflowTitle}} expires soon",
            "Hello {{recipientName}},\n\n{{workflowTitle}} expires on {{expiresAt}}. Please sign it before then.\n\n"
                + "{{link}}" });
        FALLBACK.put(NotificationEventType.WORKFLOW_EXPIRED, new String[] {
            "{{workflowTitle}} has expired",
            "Hello {{recipientName}},\n\n{{workflowTitle}} has expired before everyone signed it." });
        FALLBACK.put(NotificationEventType.USER_INVITATION, new String[] {
            "You are invited to {{appName}}",
            "Hello {{recipientName}},\n\n{{organisationName}} has invited you to {{appName}}.\n"
                + "Accept the invitation at {{link}}." });
        FALLBACK.put(NotificationEventType.VERIFICATION_CODE, new String[] {
            "Your verification code",
            "Your {{appName}} verification code is {{code}}. Do not share it with anyone." });
        FALLBACK.put(NotificationEventType.ESCALATION, new String[] {
            "{{workflowTitle}} is still waiting for {{signatoryName}}",
            "Hello {{recipientName}},\n\n{{signatoryName}} has not signed {{workflowTitle}} after {{reminderCount}} "
                + "reminders.\n\n{{link}}" });
        FALLBACK.put(NotificationEventType.COMPLETED_COPY, new String[] {
            "Your signed copy of {{workflowTitle}}",
            "Hello {{recipientName}},\n\nEveryone has signed {{workflowTitle}}. Download your copy at {{link}}." });
        FALLBACK.put(NotificationEventType.ORGANISATION_STATUS, new String[] {
            "{{organisationName}} is now {{status}}",
            "Hello {{recipientName}},\n\n{{organisationName}} is now {{status}}.\n\n{{reason}}" });
    }

    private final NotificationTemplateRepository notificationTemplateRepository;

    public NotificationTemplates(NotificationTemplateRepository notificationTemplateRepository)
    {
        this.notificationTemplateRepository = notificationTemplateRepository;
    }

    /** The active template with this code. */
    public Optional<NotificationTemplate> byCode(String code, NotificationChannel channel, String locale,
            UUID organisationId)
    {
        String wanted = code.trim();
        return best(template -> wanted.equalsIgnoreCase(template.getCode()), channel, locale, organisationId);
    }

    /** The active template for this event. */
    public Optional<NotificationTemplate> byEvent(NotificationEventType eventType, NotificationChannel channel,
            String locale, UUID organisationId)
    {
        return best(template -> template.getEventType() == eventType, channel, locale, organisationId);
    }

    /**
     * Subject and body for an event: its template if there is one, else the built-in wording. Email
     * bodies are HTML (the built-in wording is turned into paragraphs); SMS and in-app bodies are text.
     */
    public Rendered renderEvent(NotificationEventType eventType, NotificationChannel channel, String locale,
            UUID organisationId, Map<?, ?> model)
    {
        Optional<NotificationTemplate> template = byEvent(eventType, channel, locale, organisationId);
        boolean html = channel == NotificationChannel.EMAIL;
        if (template.isPresent())
        {
            String body = render(template.get().getBodyTemplate(), model, html);
            String subject = StringUtils.trimToNull(render(template.get().getSubjectTemplate(), model, false));
            return new Rendered(subject, body, html ? toText(body) : body);
        }

        String[] fallback = FALLBACK.get(eventType);
        String subject = StringUtils.trimToNull(render(fallback[0], model, false));
        String text = render(fallback[1], model, false).trim();
        if (!html)
        {
            return new Rendered(subject, text, text);
        }
        String body = render(fallback[1], model, true).trim();
        StringBuilder paragraphs = new StringBuilder();
        for (String paragraph : body.split("\\n\\s*\\n"))
        {
            if (StringUtils.isNotBlank(paragraph))
            {
                paragraphs.append("<p>").append(paragraph.trim().replace("\n", "<br>")).append("</p>");
            }
        }
        return new Rendered(subject, paragraphs.toString(), text);
    }

    /** Replaces the placeholders of {@code template}; values are HTML-escaped when {@code html}. */
    public static String render(String template, Map<?, ?> model, boolean html)
    {
        if (template == null)
        {
            return null;
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find())
        {
            Object value = lookup(model, matcher.group(1));
            String text = value == null ? "" : value.toString();
            matcher.appendReplacement(out, Matcher.quoteReplacement(html ? escapeHtml(text) : text));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** The plain-text version of an HTML body. */
    public static String toText(String html)
    {
        if (html == null)
        {
            return null;
        }
        String text = html.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("(?i)</p>", "\n\n");
        text = TAG.matcher(text).replaceAll("");
        text = text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&amp;", "&");
        return text.replaceAll("\\n{3,}", "\n\n").trim();
    }

    public static String escapeHtml(String text)
    {
        StringBuilder out = new StringBuilder(text.length());
        for (char c : text.toCharArray())
        {
            switch (c)
            {
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '&' -> out.append("&amp;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static Object lookup(Map<?, ?> model, String name)
    {
        if (model == null)
        {
            return null;
        }
        if (model.containsKey(name))
        {
            return model.get(name);
        }
        Object current = model;
        for (String part : name.split("\\."))
        {
            if (!(current instanceof Map<?, ?> map))
            {
                return null;
            }
            current = map.get(part);
        }
        return current;
    }

    /**
     * The organisation's template beats the platform default; then the exact locale, the same
     * language, English, anything.
     */
    private Optional<NotificationTemplate> best(Predicate<NotificationTemplate> matches, NotificationChannel channel,
            String locale, UUID organisationId)
    {
        List<NotificationTemplate> candidates = notificationTemplateRepository.findAvailable(organisationId, channel);
        String wanted = StringUtils.defaultIfBlank(locale, DEFAULT_LOCALE).trim().replace('_', '-');
        String language = wanted.split("-")[0];
        return candidates.stream()
                .filter(NotificationTemplate::isActive)
                .filter(matches)
                .min(Comparator
                        .comparingInt((NotificationTemplate template) -> template.getOrganisation() == null ? 1 : 0)
                        .thenComparingInt(template -> localeRank(template.getLocale(), wanted, language)));
    }

    private static int localeRank(String locale, String wanted, String language)
    {
        String value = StringUtils.defaultString(locale).trim().replace('_', '-');
        if (value.equalsIgnoreCase(wanted))
        {
            return 0;
        }
        if (value.split("-")[0].equalsIgnoreCase(language))
        {
            return 1;
        }
        if (value.toLowerCase(Locale.ROOT).startsWith(DEFAULT_LOCALE))
        {
            return 2;
        }
        return 3;
    }
}
