package bw.co.starsign.notification;

import co.novu.Novu;
import co.novu.models.components.SubscriberPayloadDto;
import co.novu.models.components.TriggerEventRequestDto;
import co.novu.models.components.TriggerEventRequestDtoTo2;
import co.novu.models.components.TriggerEventResponseDto;
import co.novu.models.operations.EventsControllerTriggerResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Triggers Novu workflows for the gateways. The subscriber is sent inline with each trigger, so Novu
 * creates or updates it; the transactionId makes a retried trigger that Novu already accepted a no-op.
 */
@Component
public class NovuNotifier
{
    private static final Logger LOGGER = LoggerFactory.getLogger(NovuNotifier.class);

    /**
     * Payload key naming the channel of a trigger. Each event's workflow has an email, an SMS and an in-app step,
     * each run only when this matches its channel (setup-workflows.py), so one trigger sends one message.
     */
    static final String CHANNEL = "channel";

    private final Novu novuSdk;
    private final NovuProperties properties;

    public NovuNotifier(Novu novuSdk, NovuProperties properties)
    {
        this.novuSdk = novuSdk;
        this.properties = properties;
    }

    public boolean isEnabled()
    {
        return properties.isEnabled();
    }

    /**
     * In-app notifications go to the portal users' Novu inboxes: Novu is enabled and the portal can connect
     * to it (the application identifier is known).
     */
    public boolean isInboxEnabled()
    {
        return properties.isEnabled() && StringUtils.isNotBlank(properties.getApplicationIdentifier())
                && StringUtils.isNotBlank(properties.getSecretKey());
    }

    public NovuProperties properties()
    {
        return properties;
    }

    /**
     * Novu's HMAC for a subscriber: HMAC-SHA256 of the subscriber id keyed with the secret key, in hex. With
     * HMAC turned on for the in-app integration, the inbox only opens with it, so a user cannot read another
     * user's inbox by changing the subscriber id.
     */
    public String subscriberHash(String subscriberId)
    {
        try
        {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(StringUtils.defaultString(properties.getSecretKey())
                    .getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(subscriberId.getBytes(StandardCharsets.UTF_8)));
        }
        catch (GeneralSecurityException e)
        {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    /** {@code app.novu.workflow-prefix} + the event type in kebab case. */
    public String workflowId(NotificationEventType eventType)
    {
        return workflowId(eventType.name());
    }

    /**
     * Runs a gateway call (EmailGateway / SmsGateway send) that triggers the Novu workflow of the given event,
     * for callers outside the notification service, e.g. verification codes sent synchronously.
     */
    public <T> T withWorkflow(NotificationEventType eventType, String transactionId, String recipientName,
            java.util.function.Supplier<T> call)
    {
        return GatewayCall.with(new GatewayCall.Hint(workflowId(eventType), transactionId, recipientName,
                new java.util.HashMap<String, Object>(java.util.Map.of("eventType", eventType.name()))), call);
    }

    public String workflowId(String name)
    {
        return StringUtils.defaultString(properties.getWorkflowPrefix())
                + name.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * Triggers {@code workflowId} for one subscriber (identified by the email address or phone
     * number) and returns the Novu transaction id.
     */
    public String trigger(String workflowId, String subscriberId, String email, String phone, String name,
            Map<String, Object> payload, String transactionId)
    {
        if (!properties.isEnabled())
        {
            // The service records the notification as SUPPRESSED; this is a safety net for direct gateway calls
            LOGGER.info("Novu is disabled: workflow {} for {} was not triggered", workflowId, mask(subscriberId));
            return null;
        }

        SubscriberPayloadDto.Builder subscriber = SubscriberPayloadDto.builder().subscriberId(subscriberId);
        if (StringUtils.isNotBlank(email))
        {
            subscriber.email(email);
        }
        if (StringUtils.isNotBlank(phone))
        {
            subscriber.phone(phone);
        }
        if (StringUtils.isNotBlank(name))
        {
            String[] parts = name.trim().split("\\s+", 2);
            subscriber.firstName(parts[0]);
            if (parts.length > 1)
            {
                subscriber.lastName(parts[1]);
            }
        }

        String transaction = StringUtils.isNotBlank(transactionId) ? transactionId : UUID.randomUUID().toString();
        TriggerEventRequestDto request = TriggerEventRequestDto.builder()
                .workflowId(workflowId)
                .to(TriggerEventRequestDtoTo2.of(subscriber.build()))
                .payload(payload == null ? new HashMap<String, Object>() : new HashMap<String, Object>(payload))
                .transactionId(transaction)
                .build();

        EventsControllerTriggerResponse response;
        try
        {
            response = novuSdk.trigger().body(request).call();
        }
        catch (RuntimeException e)
        {
            // The caller retries; this is where the cause shows
            LOGGER.warn("Novu trigger of {} (transaction {}) at {} failed: {}", workflowId, transaction,
                    properties.getBaseUrl(), e.toString());
            throw e;
        }

        TriggerEventResponseDto result = response.triggerEventResponseDto().orElse(null);
        if (result != null && !result.acknowledged())
        {
            throw new IllegalStateException("Novu did not accept workflow " + workflowId + ": "
                    + result.error().map(Object::toString).orElse(String.valueOf(result.status())));
        }
        String id = result == null ? transaction : result.transactionId().orElse(transaction);
        LOGGER.debug("Triggered {} (transaction {})", workflowId, id);
        return id;
    }

    /** An address as it may appear in logs: enough to recognise it, not to harvest it. */
    static String mask(String address)
    {
        if (StringUtils.isBlank(address))
        {
            return "-";
        }
        String value = address.trim();
        int at = value.indexOf('@');
        if (at > 0)
        {
            return value.charAt(0) + "***" + value.substring(at);
        }
        return value.length() <= 4 ? "***" : "***" + value.substring(value.length() - 4);
    }
}
