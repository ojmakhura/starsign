// license-header java merge-point
/**
 * This is only generated once! It will never be overwritten.
 * You can (and have to!) safely modify it by hand.
 * TEMPLATE:    SpringServiceImpl.vsl in andromda-spring cartridge
 * MODEL CLASS: AndroMDAModel::backend::bw.co.starsign::notification::NotificationProviderService
 * STEREOTYPE:  Service
 */
package bw.co.starsign.notification;

import bw.co.starsign.audit.ActorType;
import bw.co.starsign.audit.AuditEventDTO;
import bw.co.starsign.audit.AuditEventType;
import bw.co.starsign.audit.AuditOutcome;
import bw.co.starsign.audit.AuditTrailService;
import bw.co.starsign.context.CallerContext;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Email and SMS provider configurations. Credentials are never stored: a configuration only names
 * where they are kept ({@code credentialsSecretRef}, e.g. {@code env:SMTP_PASSWORD} or
 * {@code vault:secret/starsign/sms}).
 *
 * @see bw.co.starsign.notification.NotificationProviderService
 */
@Service("notificationProviderService")
@Transactional(propagation = Propagation.REQUIRED, readOnly = false)
public class NotificationProviderServiceImpl
    extends NotificationProviderServiceBase
{
    /** A reference: a known scheme and a key, without spaces (a password pasted here would not match). */
    private static final Pattern SECRET_REF =
            Pattern.compile("^(env|vault|secret|k8s|aws-sm|novu):[A-Za-z0-9_./-]{1,200}$");
    private static final Pattern URL = Pattern.compile("^https?://\\S+$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern PHONE = Pattern.compile("^\\+?[0-9]{7,15}$");

    private final EmailGateway emailGateway;
    private final SmsGateway smsGateway;
    private final NovuNotifier novuNotifier;
    private final CallerContext caller;

    public NotificationProviderServiceImpl(
        @Lazy AuditTrailService auditTrailService,
        NotificationProviderConfigRepository notificationProviderConfigRepository,
        NotificationProviderConfigMapper notificationProviderConfigMapper,
        @Lazy EmailGateway emailGateway,
        @Lazy SmsGateway smsGateway,
        NovuNotifier novuNotifier,
        @Lazy CallerContext caller) {

        super(
            auditTrailService,
            notificationProviderConfigRepository,
            notificationProviderConfigMapper);
        this.emailGateway = emailGateway;
        this.smsGateway = smsGateway;
        this.novuNotifier = novuNotifier;
        this.caller = caller;
    }

    /**
     * @see bw.co.starsign.notification.NotificationProviderService#findAll()
     */
    @Override
    protected List<NotificationProviderConfigDTO> handleFindAll()
        throws Exception
    {
        return notificationProviderConfigMapper.toNotificationProviderConfigDTOCollection(
                notificationProviderConfigRepository.findAll(Sort.by("channel", "priority", "name")));
    }

    /**
     * @see bw.co.starsign.notification.NotificationProviderService#save(NotificationProviderConfigDTO)
     */
    @Override
    protected NotificationProviderConfigDTO handleSave(@Valid NotificationProviderConfigDTO config)
        throws Exception
    {
        check(config);

        LocalDateTime now = LocalDateTime.now();
        boolean creating = StringUtils.isBlank(config.getId());
        NotificationProviderConfig entity;
        if (creating)
        {
            entity = NotificationProviderConfig.Factory.newInstance();
            entity.setCreatedAt(now);
            entity.setCreatedBy(caller.userId());
        }
        else
        {
            entity = load(config.getId());
            entity.setModifiedAt(now);
            entity.setModifiedBy(caller.userId());
        }

        String name = config.getName().trim();
        final NotificationProviderConfig saving = entity;
        notificationProviderConfigRepository.findByNameIgnoreCase(name)
                .filter(other -> !other.equals(saving))
                .ifPresent(other -> {
                    throw new NotificationProviderServiceException("There is already a provider named " + name);
                });

        entity.setName(name);
        entity.setChannel(config.getChannel());
        entity.setProviderType(config.getProviderType().trim());
        entity.setEndpointUrl(StringUtils.trimToNull(config.getEndpointUrl()));
        entity.setCredentialsSecretRef(config.getCredentialsSecretRef().trim());
        entity.setSenderAddress(StringUtils.trimToNull(config.getSenderAddress()));
        entity.setActive(config.getActive() == null || config.getActive());
        entity.setPriority(config.getPriority() == null ? 100 : config.getPriority());
        entity.setRateLimitPerMinute(config.getRateLimitPerMinute());
        NotificationProviderConfig saved = notificationProviderConfigRepository.save(entity);

        audit(saved, (creating ? "Added" : "Changed") + " the " + saved.getChannel() + " provider " + saved.getName()
                + " (" + saved.getProviderType() + (saved.isActive() ? ", active" : ", inactive") + ")");
        return notificationProviderConfigMapper.toNotificationProviderConfigDTO(saved);
    }

    /**
     * @see bw.co.starsign.notification.NotificationProviderService#remove(String)
     */
    @Override
    protected boolean handleRemove(String id)
        throws Exception
    {
        NotificationProviderConfig entity = load(id);
        notificationProviderConfigRepository.delete(entity);
        audit(entity, "Removed the " + entity.getChannel() + " provider " + entity.getName());
        return true;
    }

    /**
     * Sends a test message through the delivery path real notifications take (Novu). Delivery
     * problems come back as an error saying why; false means delivery is turned off.
     *
     * @see bw.co.starsign.notification.NotificationProviderService#test(String, String)
     */
    @Override
    protected boolean handleTest(String id, String recipientAddress)
        throws Exception
    {
        NotificationProviderConfig provider = load(id);
        String recipient = recipientAddress.trim();
        if (provider.getChannel() == NotificationChannel.IN_APP)
        {
            throw new NotificationProviderServiceException("In-app notifications have no provider to test");
        }
        if (provider.getChannel() == NotificationChannel.EMAIL && !EMAIL.matcher(recipient).matches())
        {
            throw new NotificationProviderServiceException("'" + recipient + "' is not a valid email address");
        }
        if (provider.getChannel() == NotificationChannel.SMS
                && !PHONE.matcher(recipient.replaceAll("[\\s()-]", "")).matches())
        {
            throw new NotificationProviderServiceException("'" + recipient + "' is not a valid phone number");
        }
        if (provider.getChannel() == NotificationChannel.SMS && !novuNotifier.isSmsEnabled())
        {
            throw new NotificationProviderServiceException(
                    "SMS delivery is turned off (app.novu.sms-enabled=false): nothing can be sent to test this provider");
        }
        if (!novuNotifier.isEnabled())
        {
            logger.info("Novu is disabled: test message for provider {} to {} not sent", provider.getName(),
                    NovuNotifier.mask(recipient));
            return false;
        }

        Map<String, Object> payload = new HashMap<String, Object>();
        payload.put("providerName", provider.getName());
        payload.put("providerType", provider.getProviderType());
        GatewayCall.Hint hint = new GatewayCall.Hint(novuNotifier.workflowId("provider-test"),
                UUID.randomUUID().toString(), null, payload);
        String text = "This is a test message from StarSign for the provider " + provider.getName() + ".";
        try
        {
            if (provider.getChannel() == NotificationChannel.SMS)
            {
                GatewayCall.with(hint, () -> smsGateway.send(recipient, text,
                        StringUtils.defaultIfBlank(provider.getSenderAddress(), "StarSign")));
            }
            else
            {
                Map<String, String> headers = new HashMap<String, String>();
                headers.put(EmailGatewayImpl.WORKFLOW_HEADER, hint.workflowId());
                headers.put(EmailGatewayImpl.TRANSACTION_HEADER, hint.transactionId());
                GatewayCall.with(hint, () -> emailGateway.send(
                        StringUtils.defaultIfBlank(provider.getSenderAddress(), "no-reply@starsign.co.bw"), recipient,
                        "StarSign test message", "<p>" + NotificationTemplates.escapeHtml(text) + "</p>", text,
                        headers));
            }
        }
        catch (RuntimeException e)
        {
            // Reported to the administrator as a result of the test, not as a server error
            logger.warn("Test message for provider {} failed", provider.getName(), e);
            throw new NotificationProviderServiceException("The test message could not be sent: "
                    + StringUtils.abbreviate(e.getMessage(), 500));
        }
        return true;
    }

    private static void check(NotificationProviderConfigDTO config)
    {
        List<String> errors = new ArrayList<String>();
        if (StringUtils.isBlank(config.getName()))
        {
            errors.add("The name is required");
        }
        if (config.getChannel() == null || config.getChannel() == NotificationChannel.IN_APP)
        {
            errors.add("Say which channel the provider delivers: EMAIL or SMS");
        }
        if (StringUtils.isBlank(config.getProviderType()))
        {
            errors.add("The provider type is required, e.g. novu, smtp or twilio");
        }
        if (StringUtils.isNotBlank(config.getEndpointUrl()) && !URL.matcher(config.getEndpointUrl().trim()).matches())
        {
            errors.add("The endpoint must be an http or https URL");
        }
        if (StringUtils.isBlank(config.getCredentialsSecretRef())
                || !SECRET_REF.matcher(config.getCredentialsSecretRef().trim()).matches())
        {
            errors.add("The credentials must be a reference to where they are kept, e.g. env:SMS_API_KEY or "
                    + "vault:secret/starsign/sms (env, vault, secret, k8s, aws-sm or novu), never the secret itself");
        }
        if (config.getChannel() == NotificationChannel.EMAIL && StringUtils.isNotBlank(config.getSenderAddress())
                && !EMAIL.matcher(config.getSenderAddress().trim()).matches())
        {
            errors.add("The sender of an email provider must be an email address");
        }
        if (config.getPriority() != null && config.getPriority() < 0)
        {
            errors.add("The priority cannot be negative");
        }
        if (config.getRateLimitPerMinute() != null && config.getRateLimitPerMinute() <= 0)
        {
            errors.add("The rate limit must be positive, or empty for none");
        }
        if (!errors.isEmpty())
        {
            throw new NotificationProviderServiceException(String.join("; ", errors));
        }
    }

    private void audit(NotificationProviderConfig provider, String details)
    {
        AuditEventDTO event = new AuditEventDTO();
        event.setEventType(AuditEventType.SETTINGS_CHANGED);
        event.setOccurredAt(LocalDateTime.now());
        event.setActorType(ActorType.PLATFORM_ADMIN);
        event.setActorId(caller.userId());
        event.setOutcome(AuditOutcome.SUCCESS);
        event.setEntityType("NotificationProviderConfig");
        event.setEntityId(provider.getId() == null ? null : provider.getId().toString());
        event.setDetails(details);
        auditTrailService.record(event);
    }

    private NotificationProviderConfig load(String id)
    {
        return notificationProviderConfigRepository.findById(uuid(id))
                .orElseThrow(() -> NotificationProviderServiceException.notFound("The notification provider does not exist"));
    }

    private static UUID uuid(String id)
    {
        try
        {
            return UUID.fromString(id.trim());
        }
        catch (IllegalArgumentException e)
        {
            throw NotificationProviderServiceException.notFound("'" + id + "' is not the id of a notification provider");
        }
    }
}
