// license-header java merge-point
/**
 * This is only generated once! It will never be overwritten.
 * You can (and have to!) safely modify it by hand.
 * TEMPLATE:    SpringServiceImpl.vsl in andromda-spring cartridge
 * MODEL CLASS: AndroMDAModel::backend::bw.co.starsign::notification::NotificationService
 * STEREOTYPE:  Service
 */
package bw.co.starsign.notification;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Page;
import bw.co.starsign.audit.ActorType;
import bw.co.starsign.audit.AuditEventDTO;
import bw.co.starsign.audit.AuditEventType;
import bw.co.starsign.audit.AuditOutcome;
import bw.co.starsign.audit.AuditTrailService;
import bw.co.starsign.branding.BrandingDTO;
import bw.co.starsign.branding.BrandingService;
import bw.co.starsign.common.SortDirection;
import bw.co.starsign.context.CallerContext;
import bw.co.starsign.identity.OrganisationUser;
import bw.co.starsign.identity.OrganisationUserRepository;
import bw.co.starsign.messaging.OutboxEventDTO;
import bw.co.starsign.messaging.OutboxService;
import bw.co.starsign.organisation.Organisation;
import bw.co.starsign.organisation.OrganisationNotificationSettings;
import bw.co.starsign.organisation.OrganisationRepository;
import bw.co.starsign.workflow.Signatory;
import bw.co.starsign.workflow.SignatoryRepository;
import bw.co.starsign.workflow.SignatoryStatus;
import bw.co.starsign.workflow.SigningWorkflow;
import bw.co.starsign.workflow.SigningWorkflowRepository;
import bw.co.starsign.workflow.WorkflowStatus;
import jakarta.persistence.criteria.Predicate;
import jakarta.validation.Valid;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Notifications are written QUEUED with their rendered message and handed to the outbox; the
 * outbox relay calls {@link #dispatch} which sends them through Novu (email, SMS) with retries.
 * In-app notifications need no delivery and are DELIVERED as soon as they are written.
 *
 * @see bw.co.starsign.notification.NotificationService
 */
@Service("notificationService")
@Transactional(propagation = Propagation.REQUIRED, readOnly = false)
public class NotificationServiceImpl
    extends NotificationServiceBase
{
    /** Outbox routing key consumed by {@link NotificationOutboxHandler}. */
    public static final String DISPATCH_ROUTING_KEY = "notification.dispatch";

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern PHONE = Pattern.compile("^\\+?[0-9]{7,15}$");
    private static final int MAX_ATTEMPTS = 5;
    private static final int MAX_ATTEMPTS_VERIFICATION_CODE = 3;
    private static final Duration FIRST_RETRY = Duration.ofSeconds(30);
    private static final Duration MAX_RETRY = Duration.ofHours(1);
    private static final int BATCH_SIZE = 200;
    private static final int MAX_MINE = 100;
    private static final int MAX_ERROR_LENGTH = 2000;
    private static final int MAX_REMINDERS = 20;
    private static final String SYSTEM = "system";
    private static final String PROVIDER = "novu";
    private static final String DEFAULT_SENDER = "no-reply@starsign.co.bw";
    private static final String DEFAULT_SMS_SENDER = "StarSign";

    /** Sent whatever the organisation's channel settings: without them nobody can sign or log in. */
    private static final Set<NotificationEventType> ESSENTIAL = EnumSet.of(NotificationEventType.VERIFICATION_CODE,
            NotificationEventType.SIGNING_INVITATION, NotificationEventType.USER_INVITATION,
            NotificationEventType.ORGANISATION_STATUS);
    /** Statuses after which a receipt or retry no longer changes anything. */
    private static final Set<NotificationStatus> FINAL = EnumSet.of(NotificationStatus.DELIVERED,
            NotificationStatus.FAILED, NotificationStatus.CANCELLED, NotificationStatus.SUPPRESSED);
    /** Signatories who still have to act and may be reminded. */
    private static final Set<SignatoryStatus> AWAITING = EnumSet.of(SignatoryStatus.INVITED, SignatoryStatus.VIEWED,
            SignatoryStatus.VERIFIED);
    private static final Set<String> SORTABLE = Set.of("createdAt", "eventType", "channel", "status", "sentAt",
            "deliveredAt", "recipientAddress");

    private final OrganisationRepository organisationRepository;
    private final OrganisationUserRepository organisationUserRepository;
    private final SignatoryRepository signatoryRepository;
    private final SigningWorkflowRepository signingWorkflowRepository;
    private final NotificationProviderConfigRepository notificationProviderConfigRepository;
    private final NotificationTemplates templates;
    private final NovuNotifier novuNotifier;
    private final CallerContext caller;
    private final String portalUrl;
    private final String signingPortalUrl;
    private final Duration manualReminderCooldown;

    public NotificationServiceImpl(
        @Lazy NotificationTemplateService notificationTemplateService,
        @Lazy EmailGateway emailGateway,
        @Lazy SmsGateway smsGateway,
        @Lazy OutboxService outboxService,
        @Lazy AuditTrailService auditTrailService,
        @Lazy BrandingService brandingService,
        NotificationRepository notificationRepository,
        NotificationMapper notificationMapper,
        SigningReminderRepository signingReminderRepository,
        SigningReminderMapper signingReminderMapper,
        OrganisationRepository organisationRepository,
        OrganisationUserRepository organisationUserRepository,
        SignatoryRepository signatoryRepository,
        SigningWorkflowRepository signingWorkflowRepository,
        NotificationProviderConfigRepository notificationProviderConfigRepository,
        NotificationTemplates templates,
        NovuNotifier novuNotifier,
        @Lazy CallerContext caller,
        @Value("${app.portal-url:}") String portalUrl,
        @Value("${app.signing-portal-url:}") String signingPortalUrl,
        @Value("${app.notifications.manual-reminder-cooldown-minutes:60}") long manualReminderCooldownMinutes) {

        super(
            notificationTemplateService,
            emailGateway,
            smsGateway,
            outboxService,
            auditTrailService,
            brandingService,
            notificationRepository,
            notificationMapper,
            signingReminderRepository,
            signingReminderMapper);
        this.organisationRepository = organisationRepository;
        this.organisationUserRepository = organisationUserRepository;
        this.signatoryRepository = signatoryRepository;
        this.signingWorkflowRepository = signingWorkflowRepository;
        this.notificationProviderConfigRepository = notificationProviderConfigRepository;
        this.templates = templates;
        this.novuNotifier = novuNotifier;
        this.caller = caller;
        this.portalUrl = StringUtils.removeEnd(StringUtils.trimToEmpty(portalUrl), "/");
        this.signingPortalUrl = StringUtils.removeEnd(StringUtils.trimToEmpty(signingPortalUrl), "/");
        this.manualReminderCooldown = Duration.ofMinutes(Math.max(0, manualReminderCooldownMinutes));
    }

    /**
     * @see bw.co.starsign.notification.NotificationService#enqueue(NotificationRequestDTO)
     */
    @Override
    protected NotificationDTO handleEnqueue(@Valid NotificationRequestDTO request)
        throws Exception
    {
        return toDto(create(request));
    }

    /**
     * @see bw.co.starsign.notification.NotificationService#dispatch(String)
     */
    @Override
    protected NotificationDTO handleDispatch(String notificationId)
        throws Exception
    {
        Notification notification = load(notificationId);
        // A redelivered outbox event or a retry racing a receipt must not send twice
        if (notification.getStatus() != NotificationStatus.QUEUED
                && notification.getStatus() != NotificationStatus.RETRY_SCHEDULED)
        {
            return toDto(notification);
        }

        LocalDateTime now = LocalDateTime.now();
        notification.setAttempts(notification.getAttempts() + 1);
        notification.setModifiedAt(now);
        notification.setModifiedBy(SYSTEM);

        if (notification.getChannel() == NotificationChannel.IN_APP)
        {
            delivered(notification, now);
            return toDto(notificationRepository.save(notification));
        }

        if (!novuNotifier.isEnabled())
        {
            // Development and test environments: nothing leaves the system. The body is not logged:
            // it may hold a signing link or a verification code.
            logger.info("Novu is disabled, notification {} not sent: {} {} to {} \"{}\"", notification.getId(),
                    notification.getEventType(), notification.getChannel(),
                    NovuNotifier.mask(notification.getRecipientAddress()), notification.getSubject());
            notification.setStatus(NotificationStatus.SUPPRESSED);
            notification.setLastError("Delivery is disabled (app.novu.enabled=false)");
            notification.setNextAttemptAt(null);
            redactSecrets(notification);
            return toDto(notificationRepository.save(notification));
        }

        try
        {
            String messageId = send(notification);
            notification.setStatus(NotificationStatus.SENT);
            notification.setSentAt(now);
            notification.setProviderName(PROVIDER);
            notification.setProviderMessageId(messageId);
            notification.setLastError(null);
            notification.setNextAttemptAt(null);
            redactSecrets(notification);
        }
        catch (RuntimeException e)
        {
            failedAttempt(notification, e, now);
        }
        return toDto(notificationRepository.save(notification));
    }

    /**
     * Hands the notifications whose retry is due back to the outbox.
     *
     * @see bw.co.starsign.notification.NotificationService#retryFailed()
     */
    @Override
    protected int handleRetryFailed()
        throws Exception
    {
        List<Notification> due = notificationRepository.findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
                NotificationStatus.RETRY_SCHEDULED, LocalDateTime.now(), PageRequest.of(0, BATCH_SIZE));
        for (Notification notification : due)
        {
            publish(notification);
        }
        return due.size();
    }

    /**
     * Receipts only move a notification forward (a late SENT receipt does not undo DELIVERED);
     * receipts for unknown messages are ignored, as the endpoint is public.
     *
     * @see bw.co.starsign.notification.NotificationService#recordDeliveryReceipt(DeliveryReceiptDTO)
     */
    @Override
    protected void handleRecordDeliveryReceipt(@Valid DeliveryReceiptDTO receipt)
        throws Exception
    {
        if (StringUtils.isBlank(receipt.getProviderMessageId()) || receipt.getStatus() == null)
        {
            throw new NotificationServiceException("A receipt needs the provider message id and the status");
        }
        Optional<Notification> found = notificationRepository.findFirstByProviderMessageId(
                receipt.getProviderMessageId().trim());
        if (found.isEmpty())
        {
            logger.info("Ignoring a {} receipt for unknown provider message {}", receipt.getStatus(),
                    StringUtils.abbreviate(receipt.getProviderMessageId(), 80));
            return;
        }
        Notification notification = found.get();
        if (StringUtils.isNotBlank(receipt.getProviderName()) && notification.getProviderName() != null
                && !notification.getProviderName().equalsIgnoreCase(receipt.getProviderName().trim()))
        {
            logger.info("Ignoring a receipt from {} for notification {} sent through {}", receipt.getProviderName(),
                    notification.getId(), notification.getProviderName());
            return;
        }
        if (FINAL.contains(notification.getStatus()))
        {
            return;
        }

        LocalDateTime at = receipt.getOccurredAt() != null ? receipt.getOccurredAt() : LocalDateTime.now();
        switch (receipt.getStatus())
        {
            case DELIVERED -> delivered(notification, at);
            case SENT -> {
                notification.setStatus(NotificationStatus.SENT);
                if (notification.getSentAt() == null)
                {
                    notification.setSentAt(at);
                }
            }
            case FAILED -> {
                // The provider gave up (bounce, unknown number): retrying the same address does not help
                notification.setStatus(NotificationStatus.FAILED);
                notification.setFailedAt(at);
                notification.setNextAttemptAt(null);
                notification.setLastError(StringUtils.abbreviate(
                        StringUtils.defaultIfBlank(receipt.getError(), "The provider reported a failure"),
                        MAX_ERROR_LENGTH));
                auditFailure(notification);
            }
            default -> throw new NotificationServiceException(
                    "A receipt can only report SENT, DELIVERED or FAILED, not " + receipt.getStatus());
        }
        notification.setModifiedAt(LocalDateTime.now());
        notification.setModifiedBy(SYSTEM);
        notificationRepository.save(notification);
    }

    /**
     * @see bw.co.starsign.notification.NotificationService#findMine(Boolean)
     */
    @Override
    protected List<NotificationDTO> handleFindMine(Boolean unreadOnly)
        throws Exception
    {
        Optional<OrganisationUser> me = organisationUserRepository.findByKeycloakUserId(caller.userId());
        if (me.isEmpty())
        {
            return new ArrayList<NotificationDTO>();
        }
        UUID userId = me.get().getId();
        boolean unread = Boolean.TRUE.equals(unreadOnly);
        Specification<Notification> mine = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<Predicate>();
            where.add(cb.equal(root.get("recipientUser").get("id"), userId));
            where.add(cb.equal(root.get("channel"), NotificationChannel.IN_APP));
            if (unread)
            {
                where.add(cb.isNull(root.get("readAt")));
            }
            return cb.and(where.toArray(new Predicate[0]));
        };
        return toDtos(notificationRepository.findAll(mine,
                PageRequest.of(0, MAX_MINE, Sort.by(Sort.Direction.DESC, "createdAt"))).getContent());
    }

    /**
     * @see bw.co.starsign.notification.NotificationService#markRead(String)
     */
    @Override
    protected NotificationDTO handleMarkRead(String id)
        throws Exception
    {
        Notification notification = load(id);
        OrganisationUser recipient = notification.getRecipientUser();
        // Somebody else's notification is reported as missing, not as forbidden
        if (recipient == null || !caller.userId().equals(recipient.getKeycloakUserId()))
        {
            throw NotificationServiceException.notFound("The notification does not exist");
        }
        if (notification.getReadAt() == null)
        {
            LocalDateTime now = LocalDateTime.now();
            notification.setReadAt(now);
            notification.setModifiedAt(now);
            notification.setModifiedBy(caller.userId());
            notification = notificationRepository.save(notification);
        }
        return toDto(notification);
    }

    /**
     * Organisation users search their own organisation only, and only their own notifications when
     * they hold notifications:list-own; platform administrators may search any organisation.
     *
     * @see bw.co.starsign.notification.NotificationService#search(NotificationSearchCriteria)
     */
    @Override
    protected List<NotificationDTO> handleSearch(@Valid NotificationSearchCriteria criteria)
        throws Exception
    {
        Specification<Notification> matching = searchSpecification(criteria);
        if (matching == null)
        {
            return new ArrayList<NotificationDTO>();
        }
        return toDtos(notificationRepository.findAll(matching, searchRequest(criteria)).getContent());
    }

    /**
     * @see bw.co.starsign.notification.NotificationService#pagedSearch(NotificationSearchCriteria)
     */
    @Override
    protected Page<NotificationDTO> handlePagedSearch(@Valid NotificationSearchCriteria criteria)
        throws Exception
    {
        PageRequest request = searchRequest(criteria);
        Specification<Notification> matching = searchSpecification(criteria);
        if (matching == null)
        {
            return Page.empty(request);
        }
        Page<Notification> found = notificationRepository.findAll(matching, request);
        return new PageImpl<NotificationDTO>(toDtos(found.getContent()), request, found.getTotalElements());
    }

    /**
     * The search's specification, limited to the caller's organisation and, for owner-scoped callers, their own
     * notifications; null when an owner-scoped caller has no user account, so has no notifications.
     */
    private Specification<Notification> searchSpecification(NotificationSearchCriteria criteria)
    {
        UUID organisationId = null;
        if (caller.tenantId().isPresent())
        {
            String own = caller.organisationId()
                    .orElseThrow(() -> new NotificationServiceException("Your account is not linked to an organisation"));
            if (StringUtils.isNotBlank(criteria.getOrganisationId()) && !own.equals(criteria.getOrganisationId().trim()))
            {
                throw new NotificationServiceException("You can only search your own organisation's notifications");
            }
            organisationId = uuid(own, "organisation");
        }
        else if (StringUtils.isNotBlank(criteria.getOrganisationId()))
        {
            organisationId = uuid(criteria.getOrganisationId(), "organisation");
        }

        UUID recipientUserId = null;
        if (caller.ownRecordsOnly("notifications:list"))
        {
            Optional<OrganisationUser> me = organisationUserRepository.findByKeycloakUserId(caller.userId());
            if (me.isEmpty())
            {
                return null;
            }
            recipientUserId = me.get().getId();
        }
        UUID workflowId = StringUtils.isBlank(criteria.getWorkflowId()) ? null : uuid(criteria.getWorkflowId(), "workflow");

        final UUID organisation = organisationId;
        final UUID recipient = recipientUserId;
        Specification<Notification> matching = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<Predicate>();
            if (organisation != null)
            {
                where.add(cb.equal(root.get("organisation").get("id"), organisation));
            }
            if (recipient != null)
            {
                where.add(cb.equal(root.get("recipientUser").get("id"), recipient));
            }
            if (workflowId != null)
            {
                where.add(cb.equal(root.get("workflow").get("id"), workflowId));
            }
            if (criteria.getEventType() != null)
            {
                where.add(cb.equal(root.get("eventType"), criteria.getEventType()));
            }
            if (criteria.getChannel() != null)
            {
                where.add(cb.equal(root.get("channel"), criteria.getChannel()));
            }
            if (criteria.getStatus() != null)
            {
                where.add(cb.equal(root.get("status"), criteria.getStatus()));
            }
            if (criteria.getFrom() != null)
            {
                where.add(cb.greaterThanOrEqualTo(root.<LocalDateTime>get("createdAt"), criteria.getFrom()));
            }
            if (criteria.getTo() != null)
            {
                where.add(cb.lessThan(root.<LocalDateTime>get("createdAt"), criteria.getTo()));
            }
            return cb.and(where.toArray(new Predicate[0]));
        };
        return matching;
    }

    private static PageRequest searchRequest(NotificationSearchCriteria criteria)
    {
        String sortField = StringUtils.isNotBlank(criteria.getSortField()) && SORTABLE.contains(criteria.getSortField().trim())
                ? criteria.getSortField().trim()
                : "createdAt";
        Sort.Direction direction = criteria.getSortDirection() == SortDirection.ASC ? Sort.Direction.ASC
                : Sort.Direction.DESC;
        int page = criteria.getPageNumber() == null || criteria.getPageNumber() < 0 ? 0 : criteria.getPageNumber();
        int size = criteria.getPageSize() == null || criteria.getPageSize() <= 0 ? 50 : Math.min(criteria.getPageSize(), 500);
        return PageRequest.of(page, size, Sort.by(direction, sortField).and(Sort.by("id")));
    }

    /**
     * Plans the signatory's reminders from the organisation's settings, replacing any still
     * scheduled: one after inactivity, then repeating ones until the deadline, one shortly before the
     * deadline (at most maxReminders in all), and an escalation to the owner after
     * escalateAfterReminders reminders.
     *
     * @see bw.co.starsign.notification.NotificationService#scheduleReminders(String, String)
     */
    @Override
    protected List<SigningReminderDTO> handleScheduleReminders(String workflowId, String signatoryId)
        throws Exception
    {
        Signatory signatory = signatory(workflowId, signatoryId);
        SigningWorkflow workflow = signatory.getWorkflow();
        cancel(workflow.getId(), signatory.getId());

        OrganisationNotificationSettings settings = settings(workflow.getOrganisation());
        if (settings != null && !settings.isRemindersEnabled())
        {
            return new ArrayList<SigningReminderDTO>();
        }
        int maxReminders = Math.min(settings == null ? 3 : settings.getMaxReminders(), MAX_REMINDERS);
        if (maxReminders <= 0)
        {
            return new ArrayList<SigningReminderDTO>();
        }
        int inactivityHours = settings == null ? 48 : settings.getReminderAfterInactivityHours();
        int repeatHours = workflow.getReminderIntervalHours() > 0 ? workflow.getReminderIntervalHours()
                : settings == null ? 72 : settings.getReminderRepeatIntervalHours();
        int beforeDeadlineHours = settings == null ? 24 : settings.getReminderBeforeDeadlineHours();

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime deadline = signatory.getDeadline() != null ? signatory.getDeadline() : workflow.getExpiresAt();

        Map<LocalDateTime, ReminderType> plan = new LinkedHashMap<LocalDateTime, ReminderType>();
        LocalDateTime beforeDeadline = deadline != null && beforeDeadlineHours > 0
                ? deadline.minusHours(beforeDeadlineHours)
                : null;
        if (beforeDeadline != null && !beforeDeadline.isAfter(now))
        {
            beforeDeadline = null;
        }
        int budget = maxReminders - (beforeDeadline == null ? 0 : 1);
        LocalDateTime next = inactivityHours > 0 ? now.plusHours(inactivityHours)
                : repeatHours > 0 ? now.plusHours(repeatHours) : null;
        ReminderType type = inactivityHours > 0 ? ReminderType.AFTER_INACTIVITY : ReminderType.REPEATING;
        while (next != null && plan.size() < budget
                && (beforeDeadline == null || next.isBefore(beforeDeadline))
                && (deadline == null || next.isBefore(deadline)))
        {
            plan.put(next, type);
            if (repeatHours <= 0)
            {
                break;
            }
            next = next.plusHours(repeatHours);
            type = ReminderType.REPEATING;
        }
        if (beforeDeadline != null)
        {
            plan.put(beforeDeadline, ReminderType.BEFORE_DEADLINE);
        }

        List<Map.Entry<LocalDateTime, ReminderType>> ordered = new ArrayList<Map.Entry<LocalDateTime, ReminderType>>(
                plan.entrySet());
        ordered.sort(Map.Entry.comparingByKey());
        List<SigningReminder> reminders = new ArrayList<SigningReminder>();
        for (Map.Entry<LocalDateTime, ReminderType> entry : ordered)
        {
            reminders.add(reminder(workflow, signatory, entry.getValue(), entry.getKey(), reminders.size() + 1, now));
        }

        if (settings != null && settings.isEscalateToOwner() && settings.getEscalateAfterReminders() > 0
                && reminders.size() >= settings.getEscalateAfterReminders())
        {
            LocalDateTime trigger = reminders.get(settings.getEscalateAfterReminders() - 1).getScheduledAt()
                    .plusHours(repeatHours > 0 ? repeatHours : 24);
            if (deadline != null && trigger.isAfter(deadline))
            {
                trigger = deadline;
            }
            reminders.add(reminder(workflow, signatory, ReminderType.ESCALATION, trigger, reminders.size() + 1, now));
        }

        return toReminderDtos(signingReminderRepository.saveAll(reminders));
    }

    /**
     * @see bw.co.starsign.notification.NotificationService#cancelReminders(String, String)
     */
    @Override
    protected int handleCancelReminders(String workflowId, String signatoryId)
        throws Exception
    {
        Signatory signatory = signatory(workflowId, signatoryId);
        return cancel(signatory.getWorkflow().getId(), signatory.getId());
    }

    /**
     * Reminders whose signatory has acted, whose workflow is no longer running or whose
     * organisation turned reminders off are SKIPPED rather than sent.
     *
     * @see bw.co.starsign.notification.NotificationService#processDueReminders()
     */
    @Override
    protected int handleProcessDueReminders()
        throws Exception
    {
        LocalDateTime now = LocalDateTime.now();
        List<SigningReminder> due = signingReminderRepository
                .findByStatusAndScheduledAtLessThanEqualOrderByScheduledAtAscSequenceAsc(ReminderStatus.SCHEDULED, now,
                        PageRequest.of(0, BATCH_SIZE));
        int sent = 0;
        for (SigningReminder reminder : due)
        {
            Notification notification;
            try
            {
                notification = remind(reminder, false);
            }
            catch (NotificationServiceException e)
            {
                // e.g. a signatory address that is not valid: retrying the same reminder will not help
                logger.warn("Reminder {} for signatory {} is skipped: {}", reminder.getId(),
                        reminder.getSignatory().getId(), e.getMessage());
                notification = null;
            }
            reminder.setModifiedAt(now);
            reminder.setModifiedBy(SYSTEM);
            if (notification == null)
            {
                reminder.setStatus(ReminderStatus.SKIPPED);
            }
            else
            {
                reminder.setStatus(ReminderStatus.SENT);
                reminder.setSentAt(now);
                reminder.setNotification(notification);
                sent++;
            }
            signingReminderRepository.save(reminder);
        }
        return sent;
    }

    /**
     * Recorded as a REPEATING reminder with sequence 0, created by the caller, and sent at once; it
     * counts towards the escalation like a scheduled one and leaves the schedule as it is.
     *
     * @see bw.co.starsign.notification.NotificationService#sendReminder(String, String)
     */
    @Override
    protected SigningReminderDTO handleSendReminder(String workflowId, String signatoryId)
        throws Exception
    {
        Signatory signatory = signatory(workflowId, signatoryId);
        SigningWorkflow workflow = signatory.getWorkflow();
        if (workflow.getStatus() != WorkflowStatus.IN_PROGRESS)
        {
            throw new NotificationServiceException("Reminders are only sent while the workflow is in progress");
        }
        if (!AWAITING.contains(signatory.getStatus()))
        {
            throw new NotificationServiceException(signatory.getFullName() + " has nothing to sign at the moment");
        }

        LocalDateTime now = LocalDateTime.now();
        if (!manualReminderCooldown.isZero() && signingReminderRepository
                .existsBySignatory_IdAndStatusAndReminderTypeNotAndSentAtAfter(signatory.getId(), ReminderStatus.SENT,
                        ReminderType.ESCALATION, now.minus(manualReminderCooldown)))
        {
            throw new NotificationServiceException(signatory.getFullName()
                    + " was reminded less than " + manualReminderCooldown.toMinutes() + " minutes ago: try again later");
        }

        String by = StringUtils.defaultIfBlank(caller.userId(), SYSTEM);
        SigningReminder reminder = reminder(workflow, signatory, ReminderType.REPEATING, now, 0, now);
        reminder.setCreatedBy(by);
        // Saved first: the notifications' deduplication keys carry the reminder's id
        reminder = signingReminderRepository.save(reminder);
        Notification notification = remind(reminder, true);
        if (notification == null)
        {
            throw new NotificationServiceException(signatory.getFullName()
                    + " has no email address or phone number to send a reminder to");
        }
        reminder.setStatus(ReminderStatus.SENT);
        reminder.setSentAt(now);
        reminder.setNotification(notification);
        reminder.setModifiedAt(now);
        reminder.setModifiedBy(by);
        return toReminderDtos(List.of(signingReminderRepository.save(reminder))).get(0);
    }

    // ---------------------------------------------------------------------------------------------
    // Creating and sending

    private Notification create(NotificationRequestDTO request)
    {
        check(request);

        String deduplicationKey = StringUtils.isNotBlank(request.getDeduplicationKey())
                ? request.getDeduplicationKey().trim()
                : UUID.randomUUID().toString();
        Optional<Notification> existing = notificationRepository.findByDeduplicationKey(deduplicationKey);
        if (existing.isPresent())
        {
            return existing.get();
        }

        Organisation organisation = StringUtils.isBlank(request.getOrganisationId()) ? null
                : organisationRepository.findById(uuid(request.getOrganisationId(), "organisation"))
                        .orElseThrow(() -> NotificationServiceException.notFound("The organisation does not exist"));
        OrganisationUser recipientUser = StringUtils.isBlank(request.getRecipientUserId()) ? null
                : findUser(request.getRecipientUserId().trim())
                        .orElseThrow(() -> NotificationServiceException.notFound("The recipient user does not exist"));
        SigningWorkflow workflow = StringUtils.isBlank(request.getWorkflowId()) ? null
                : signingWorkflowRepository.findById(uuid(request.getWorkflowId(), "workflow"))
                        .orElseThrow(() -> NotificationServiceException.notFound("The workflow does not exist"));
        Signatory signatory = StringUtils.isBlank(request.getSignatoryId()) ? null
                : signatoryRepository.findById(uuid(request.getSignatoryId(), "signatory"))
                        .orElseThrow(() -> NotificationServiceException.notFound("The signatory does not exist"));
        if (workflow == null && signatory != null)
        {
            workflow = signatory.getWorkflow();
        }
        if (organisation == null && workflow != null)
        {
            organisation = workflow.getOrganisation();
        }
        if (organisation == null && recipientUser != null)
        {
            organisation = recipientUser.getOrganisation();
        }

        NotificationChannel channel = request.getChannel();
        String address = StringUtils.trimToNull(request.getRecipientAddress());
        if (address == null && recipientUser != null)
        {
            address = channel == NotificationChannel.SMS ? StringUtils.trimToNull(recipientUser.getPhoneNumber())
                    : channel == NotificationChannel.EMAIL ? StringUtils.trimToNull(recipientUser.getEmailAddress())
                    : recipientUser.getKeycloakUserId();
        }
        if (address == null && signatory != null)
        {
            address = channel == NotificationChannel.SMS ? StringUtils.trimToNull(signatory.getPhoneNumber())
                    : StringUtils.trimToNull(signatory.getEmailAddress());
        }
        checkAddress(channel, address);

        String recipientName = StringUtils.firstNonBlank(request.getRecipientName(),
                signatory == null ? null : signatory.getFullName(),
                recipientUser == null ? null : StringUtils.trimToNull(
                        StringUtils.defaultString(recipientUser.getFirstName()) + " "
                                + StringUtils.defaultString(recipientUser.getLastName())));
        String locale = StringUtils.firstNonBlank(request.getLocale(),
                signatory == null ? null : signatory.getLocale(),
                recipientUser == null ? null : recipientUser.getLocale(),
                organisation == null ? null : organisation.getLocale(), "en");

        Map<String, Object> model = model(request, organisation, workflow, recipientName);
        NotificationTemplates.Rendered message = templates.renderEvent(request.getEventType(), channel, locale,
                organisation == null ? null : organisation.getId(), model);

        LocalDateTime now = LocalDateTime.now();
        Notification notification = Notification.Factory.newInstance();
        notification.setEventType(request.getEventType());
        notification.setChannel(channel);
        notification.setRecipientName(recipientName);
        notification.setRecipientAddress(address);
        notification.setSubject(StringUtils.abbreviate(message.subject(), 255));
        notification.setBody(message.body());
        notification.setAttempts(0);
        notification.setMaxAttempts(request.getEventType() == NotificationEventType.VERIFICATION_CODE
                ? MAX_ATTEMPTS_VERIFICATION_CODE
                : MAX_ATTEMPTS);
        notification.setDeduplicationKey(deduplicationKey);
        notification.setLinkPath(StringUtils.trimToNull(asString(model.get("linkPath"))));
        notification.setOrganisation(organisation);
        notification.setRecipientUser(recipientUser);
        notification.setSignatory(signatory);
        notification.setWorkflow(workflow);
        notification.setCreatedAt(now);
        notification.setCreatedBy(SYSTEM);

        String suppressed = suppressedBy(request.getEventType(), channel, settings(organisation));
        if (suppressed != null)
        {
            notification.setStatus(NotificationStatus.SUPPRESSED);
            notification.setLastError(suppressed);
            redactSecrets(notification);
            return notificationRepository.save(notification);
        }
        if (channel == NotificationChannel.IN_APP)
        {
            notification.setAttempts(1);
            delivered(notification, now);
            return notificationRepository.save(notification);
        }

        notification.setStatus(NotificationStatus.QUEUED);
        notification.setNextAttemptAt(now);
        Notification saved = notificationRepository.save(notification);
        publish(saved);
        return saved;
    }

    /** Queues the dispatch of the notification's next attempt through the outbox (same transaction). */
    private void publish(Notification notification)
    {
        OutboxEventDTO event = new OutboxEventDTO();
        event.setAggregateType("Notification");
        event.setAggregateId(notification.getId().toString());
        event.setEventType("NOTIFICATION_" + notification.getEventType().name());
        event.setOrganisationId(notification.getOrganisation() == null ? null
                : notification.getOrganisation().getId().toString());
        event.setRoutingKey(DISPATCH_ROUTING_KEY);
        event.setPayload("{\"notificationId\":\"" + notification.getId() + "\"}");
        event.setIdempotencyKey("notification:" + notification.getId() + ":" + (notification.getAttempts() + 1));
        event.setCorrelationId(notification.getWorkflow() == null ? null : notification.getWorkflow().getId().toString());
        outboxService.enqueue(event);
    }

    /** Sends through the channel's gateway and returns the provider's message id. */
    private String send(Notification notification)
    {
        Map<String, Object> payload = new HashMap<String, Object>();
        payload.put("eventType", notification.getEventType().name());
        payload.put("notificationId", notification.getId().toString());
        if (notification.getRecipientName() != null)
        {
            payload.put("recipientName", notification.getRecipientName());
        }
        if (notification.getLinkPath() != null)
        {
            payload.put("link", link(notification.getLinkPath()));
        }
        GatewayCall.Hint hint = new GatewayCall.Hint(novuNotifier.workflowId(notification.getEventType()),
                notification.getId().toString(), notification.getRecipientName(), payload);
        OrganisationNotificationSettings settings = settings(notification.getOrganisation());
        String body = StringUtils.defaultIfBlank(notification.getBody(), notification.getSubject());

        if (notification.getChannel() == NotificationChannel.SMS)
        {
            String sender = StringUtils.firstNonBlank(settings == null ? null : settings.getSenderDisplayName(),
                    provider(NotificationChannel.SMS), DEFAULT_SMS_SENDER);
            return GatewayCall.with(hint, () -> smsGateway.send(notification.getRecipientAddress(), body, sender));
        }

        String from = StringUtils.firstNonBlank(provider(NotificationChannel.EMAIL), DEFAULT_SENDER);
        Map<String, String> headers = new HashMap<String, String>();
        headers.put(EmailGatewayImpl.WORKFLOW_HEADER, hint.workflowId());
        headers.put(EmailGatewayImpl.TRANSACTION_HEADER, hint.transactionId());
        if (settings != null && StringUtils.isNotBlank(settings.getReplyToEmail()))
        {
            headers.put("Reply-To", settings.getReplyToEmail().trim());
            payload.put("replyTo", settings.getReplyToEmail().trim());
        }
        if (settings != null && StringUtils.isNotBlank(settings.getSenderDisplayName()))
        {
            payload.put("senderName", settings.getSenderDisplayName().trim());
        }
        String subject = StringUtils.defaultIfBlank(notification.getSubject(), humanise(notification.getEventType()));
        String content = StringUtils.defaultIfBlank(body, NotificationTemplates.escapeHtml(subject));
        String contentText = StringUtils.defaultIfBlank(NotificationTemplates.toText(content), subject);
        // The stored body is the message alone; the logo header and footer go around it as it is sent,
        // with the organisation's current branding. A template that is a whole HTML document is sent as it is.
        String html;
        String text;
        if (EmailLayout.isDocument(content))
        {
            html = content;
            text = contentText;
        }
        else
        {
            EmailLayout.Brand brand = emailBrand(notification.getOrganisation());
            html = EmailLayout.wrap(content, subject, brand);
            text = contentText + "\n\n--\n" + EmailLayout.textFooter(brand);
        }
        return GatewayCall.with(hint, () -> emailGateway.send(from, notification.getRecipientAddress(), subject, html,
                text, headers));
    }

    private void failedAttempt(Notification notification, RuntimeException e, LocalDateTime now)
    {
        notification.setLastError(StringUtils.abbreviate(rootMessage(e), MAX_ERROR_LENGTH));
        if (notification.getAttempts() >= notification.getMaxAttempts())
        {
            notification.setStatus(NotificationStatus.FAILED);
            notification.setFailedAt(now);
            notification.setNextAttemptAt(null);
            redactSecrets(notification);
            logger.warn("Notification {} ({} {}) failed after {} attempts: {}", notification.getId(),
                    notification.getEventType(), notification.getChannel(), notification.getAttempts(),
                    notification.getLastError());
            auditFailure(notification);
        }
        else
        {
            Duration delay = FIRST_RETRY.multipliedBy(1L << Math.min(notification.getAttempts() - 1, 16));
            notification.setStatus(NotificationStatus.RETRY_SCHEDULED);
            notification.setNextAttemptAt(now.plus(delay.compareTo(MAX_RETRY) > 0 ? MAX_RETRY : delay));
        }
    }

    private static void delivered(Notification notification, LocalDateTime at)
    {
        notification.setStatus(NotificationStatus.DELIVERED);
        if (notification.getSentAt() == null)
        {
            notification.setSentAt(at);
        }
        notification.setDeliveredAt(at);
        notification.setNextAttemptAt(null);
        notification.setLastError(null);
    }

    /** Placeholder left where a signing link's token was. */
    static final String REDACTED_TOKEN = "[link removed after sending]";

    /**
     * Secrets are kept only while the message may still have to be sent (queued or retrying): once it is sent,
     * suppressed or has finally failed, a verification code is dropped and every signing link loses its token,
     * so the stored copy cannot be used to sign. Only the token's hash is kept (SignatoryInvitation).
     */
    private void redactSecrets(Notification notification)
    {
        if (notification.getEventType() == NotificationEventType.VERIFICATION_CODE)
        {
            notification.setBody(null);
            return;
        }
        notification.setBody(redactSigningLinks(notification.getBody(), signingPortalUrl));
    }

    /**
     * Replaces the token of every signing-portal link ({signingPortalUrl}/{token}, also any .../sign/{token})
     * with a placeholder. Tokens are base64url (SigningTokens).
     */
    static String redactSigningLinks(String text, String signingPortalUrl)
    {
        if (StringUtils.isBlank(text))
        {
            return text;
        }
        String result = text;
        if (StringUtils.isNotBlank(signingPortalUrl))
        {
            result = result.replaceAll(java.util.regex.Pattern.quote(signingPortalUrl + "/") + "[A-Za-z0-9_-]{16,}",
                    java.util.regex.Matcher.quoteReplacement(signingPortalUrl + "/" + REDACTED_TOKEN));
        }
        return result.replaceAll("(/sign/)[A-Za-z0-9_-]{16,}",
                "$1" + java.util.regex.Matcher.quoteReplacement(REDACTED_TOKEN));
    }

    private void auditFailure(Notification notification)
    {
        AuditEventDTO event = new AuditEventDTO();
        event.setEventType(AuditEventType.NOTIFICATION_FAILED);
        event.setOccurredAt(LocalDateTime.now());
        event.setOrganisationId(notification.getOrganisation() == null ? null
                : notification.getOrganisation().getId().toString());
        event.setActorType(ActorType.SYSTEM);
        event.setActorId(SYSTEM);
        event.setOutcome(AuditOutcome.FAILURE);
        event.setWorkflowId(notification.getWorkflow() == null ? null : notification.getWorkflow().getId().toString());
        event.setSignatoryId(notification.getSignatory() == null ? null
                : notification.getSignatory().getId().toString());
        event.setEntityType("Notification");
        event.setEntityId(notification.getId().toString());
        event.setDetails(notification.getEventType() + " by " + notification.getChannel() + " to "
                + NovuNotifier.mask(notification.getRecipientAddress()) + " failed: "
                + StringUtils.abbreviate(notification.getLastError(), 500));
        auditTrailService.record(event);
    }

    // ---------------------------------------------------------------------------------------------
    // Reminders

    /**
     * @param manual sent on request: the organisation's switch for scheduled reminders does not apply
     */
    private Notification remind(SigningReminder reminder, boolean manual)
    {
        Signatory signatory = reminder.getSignatory();
        SigningWorkflow workflow = reminder.getWorkflow();
        OrganisationNotificationSettings settings = settings(workflow.getOrganisation());
        if (workflow.getStatus() != WorkflowStatus.IN_PROGRESS || !AWAITING.contains(signatory.getStatus())
                || (!manual && settings != null && !settings.isRemindersEnabled()))
        {
            return null;
        }

        Map<String, Object> model = new HashMap<String, Object>();
        model.put("signatoryName", signatory.getFullName());
        // Internal signatories sign in the portal with their own account; others on the signing portal
        model.put("linkPath", signatory.getInternalUser() != null
                ? "/signing/workflows/" + workflow.getId() + "/signatories/" + signatory.getId()
                : "/sign");
        if (signatory.getDeadline() != null || workflow.getExpiresAt() != null)
        {
            model.put("deadline", String.valueOf(signatory.getDeadline() != null ? signatory.getDeadline()
                    : workflow.getExpiresAt()));
        }

        if (reminder.getReminderType() == ReminderType.ESCALATION)
        {
            OrganisationUser owner = workflow.getOwner();
            if ((settings != null && !settings.isEscalateToOwner()) || owner == null
                    || StringUtils.isBlank(owner.getEmailAddress()))
            {
                return null;
            }
            model.put("reminderCount", signingReminderRepository.countBySignatory_IdAndStatusAndReminderTypeNot(
                    signatory.getId(), ReminderStatus.SENT, ReminderType.ESCALATION));
            model.put("linkPath", "/workflows/" + workflow.getId());
            create(request(NotificationEventType.ESCALATION, NotificationChannel.IN_APP, workflow, signatory,
                    owner, null, model, "reminder:" + reminder.getId() + ":IN_APP"));
            return create(request(NotificationEventType.ESCALATION, NotificationChannel.EMAIL, workflow, signatory,
                    owner, owner.getEmailAddress(), model, "reminder:" + reminder.getId() + ":EMAIL"));
        }

        Notification first = null;
        if (signatory.isNotifyByEmail() && StringUtils.isNotBlank(signatory.getEmailAddress()))
        {
            first = create(request(NotificationEventType.SIGNING_REMINDER, NotificationChannel.EMAIL, workflow,
                    signatory, null, signatory.getEmailAddress(), model, "reminder:" + reminder.getId() + ":EMAIL"));
        }
        if (signatory.isNotifyBySms() && StringUtils.isNotBlank(signatory.getPhoneNumber()))
        {
            Notification sms = create(request(NotificationEventType.SIGNING_REMINDER, NotificationChannel.SMS, workflow,
                    signatory, null, signatory.getPhoneNumber(), model, "reminder:" + reminder.getId() + ":SMS"));
            first = first == null ? sms : first;
        }
        return first;
    }

    private static NotificationRequestDTO request(NotificationEventType eventType, NotificationChannel channel,
            SigningWorkflow workflow, Signatory signatory, OrganisationUser user, String address, Map<String, Object> model,
            String deduplicationKey)
    {
        NotificationRequestDTO request = new NotificationRequestDTO();
        request.setEventType(eventType);
        request.setChannel(channel);
        request.setOrganisationId(workflow.getOrganisation() == null ? null : workflow.getOrganisation().getId().toString());
        request.setWorkflowId(workflow.getId().toString());
        // The signatory is the subject of an escalation but the owner is its recipient
        request.setSignatoryId(signatory.getId().toString());
        if (user != null)
        {
            request.setRecipientUserId(user.getId().toString());
            request.setRecipientName(StringUtils.trimToNull(StringUtils.defaultString(user.getFirstName()) + " "
                    + StringUtils.defaultString(user.getLastName())));
        }
        else
        {
            request.setRecipientName(signatory.getFullName());
        }
        request.setRecipientAddress(address);
        request.setTemplateModel(model);
        request.setDeduplicationKey(deduplicationKey);
        return request;
    }

    private SigningReminder reminder(SigningWorkflow workflow, Signatory signatory, ReminderType type,
            LocalDateTime scheduledAt, int sequence, LocalDateTime now)
    {
        SigningReminder reminder = SigningReminder.Factory.newInstance();
        reminder.setWorkflow(workflow);
        reminder.setSignatory(signatory);
        reminder.setReminderType(type);
        reminder.setStatus(ReminderStatus.SCHEDULED);
        reminder.setScheduledAt(scheduledAt);
        reminder.setSequence(sequence);
        reminder.setCreatedAt(now);
        reminder.setCreatedBy(SYSTEM);
        return reminder;
    }

    private int cancel(UUID workflowId, UUID signatoryId)
    {
        List<SigningReminder> scheduled = signingReminderRepository.findByWorkflow_IdAndSignatory_IdAndStatus(workflowId,
                signatoryId, ReminderStatus.SCHEDULED);
        LocalDateTime now = LocalDateTime.now();
        for (SigningReminder reminder : scheduled)
        {
            reminder.setStatus(ReminderStatus.CANCELLED);
            reminder.setModifiedAt(now);
            reminder.setModifiedBy(SYSTEM);
        }
        signingReminderRepository.saveAll(scheduled);
        return scheduled.size();
    }

    private Signatory signatory(String workflowId, String signatoryId)
    {
        Signatory signatory = signatoryRepository.findById(uuid(signatoryId, "signatory"))
                .orElseThrow(() -> NotificationServiceException.notFound("The signatory does not exist"));
        if (signatory.getWorkflow() == null || !signatory.getWorkflow().getId().equals(uuid(workflowId, "workflow")))
        {
            throw NotificationServiceException.notFound("The signatory is not part of this workflow");
        }
        return signatory;
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers

    private void check(NotificationRequestDTO request)
    {
        List<String> errors = new ArrayList<String>();
        if (request.getEventType() == null)
        {
            errors.add("The event type is required");
        }
        if (request.getChannel() == null)
        {
            errors.add("The channel is required");
        }
        if (request.getChannel() == NotificationChannel.IN_APP && StringUtils.isBlank(request.getRecipientUserId()))
        {
            errors.add("An in-app notification needs the recipient user");
        }
        if (StringUtils.length(request.getDeduplicationKey()) > 255)
        {
            errors.add("The deduplication key is longer than 255 characters");
        }
        if (!errors.isEmpty())
        {
            throw new NotificationServiceException(String.join("; ", errors));
        }
    }

    private static void checkAddress(NotificationChannel channel, String address)
    {
        if (address == null)
        {
            String what = switch (channel)
            {
                case EMAIL -> "email address";
                case SMS -> "phone number";
                case IN_APP -> "user";
            };
            throw new NotificationServiceException("The recipient's " + what + " is required");
        }
        if (channel == NotificationChannel.EMAIL && !EMAIL.matcher(address).matches())
        {
            throw new NotificationServiceException("'" + address + "' is not a valid email address");
        }
        if (channel == NotificationChannel.SMS && !PHONE.matcher(address.replaceAll("[\\s()-]", "")).matches())
        {
            throw new NotificationServiceException("'" + address + "' is not a valid phone number");
        }
    }

    /** Why the organisation's settings stop this notification, or null when they do not. */
    private static String suppressedBy(NotificationEventType eventType, NotificationChannel channel,
            OrganisationNotificationSettings settings)
    {
        if (settings == null || ESSENTIAL.contains(eventType))
        {
            return null;
        }
        return switch (channel)
        {
            case EMAIL -> settings.isEmailEnabled() ? null : "Email notifications are turned off for the organisation";
            case SMS -> settings.isSmsEnabled() ? null : "SMS notifications are turned off for the organisation";
            case IN_APP -> settings.isInAppEnabled() ? null : "In-app notifications are turned off for the organisation";
        };
    }

    /**
     * The template model: the caller's values, completed with what the platform knows (names,
     * workflow, branding, the full link). The caller's values win.
     */
    private Map<String, Object> model(NotificationRequestDTO request, Organisation organisation,
            SigningWorkflow workflow, String recipientName)
    {
        Map<String, Object> model = new HashMap<String, Object>();
        if (recipientName != null)
        {
            model.put("recipientName", recipientName);
        }
        if (organisation != null)
        {
            model.put("organisationName", StringUtils.defaultIfBlank(organisation.getTradingName(), organisation.getName()));
        }
        if (workflow != null)
        {
            model.put("workflowTitle", workflow.getTitle());
            model.put("workflowReference", workflow.getReferenceNumber());
            if (workflow.getExpiresAt() != null)
            {
                model.put("expiresAt", workflow.getExpiresAt().toString());
            }
        }
        BrandingDTO branding = branding(organisation);
        model.put("appName", branding == null || StringUtils.isBlank(branding.getAppName()) ? "StarSign"
                : branding.getAppName());
        if (branding != null)
        {
            putIfPresent(model, "supportEmail", branding.getSupportEmail());
            putIfPresent(model, "supportUrl", branding.getSupportUrl());
            putIfPresent(model, "emailFooter", branding.getEmailFooter());
        }
        model.put("portalUrl", portalUrl);
        model.put("signingPortalUrl", signingPortalUrl);
        putIfPresent(model, "emailLogoUrl", emailLogoUrl(branding));

        if (request.getTemplateModel() != null)
        {
            for (Object entry : request.getTemplateModel().entrySet())
            {
                Map.Entry<?, ?> value = (Map.Entry<?, ?>) entry;
                if (value.getKey() != null)
                {
                    model.put(value.getKey().toString(), value.getValue());
                }
            }
        }
        if (!model.containsKey("link") && model.get("linkPath") != null)
        {
            model.put("link", link(asString(model.get("linkPath"))));
        }
        // Dates reach the templates (built-in and organisation copies) as dd-MM-yyyy, whatever form the caller used
        model.replaceAll((key, value) -> displayDate(value));
        return model;
    }

    /** What the e-mail layout shows around a message for this organisation (platform branding without one). */
    private EmailLayout.Brand emailBrand(Organisation organisation)
    {
        BrandingDTO branding = branding(organisation);
        String logoUrl = emailLogoUrl(branding);
        boolean defaultLogo = logoUrl != null && logoUrl.equals(portalUrl + EmailLayout.DEFAULT_LOGO_PATH);
        return new EmailLayout.Brand(
                branding == null || StringUtils.isBlank(branding.getAppName()) ? "StarSign" : branding.getAppName(),
                organisation == null ? null
                        : StringUtils.defaultIfBlank(organisation.getTradingName(), organisation.getName()),
                logoUrl,
                defaultLogo,
                branding == null ? null : branding.getEmailFooter(),
                branding == null ? null : branding.getSupportEmail(),
                branding == null ? null : branding.getSupportUrl());
    }

    /**
     * An absolute URL for the e-mail header logo ({{emailLogoUrl}}): the organisation's or platform's upload
     * (an API path, reached through the portal's /api proxy), else the StarSign logo the portal serves
     * (public/brand/email-logo.png). Null when the portal URL is not configured.
     */
    private String emailLogoUrl(BrandingDTO branding)
    {
        if (StringUtils.isBlank(portalUrl))
        {
            return null;
        }
        String uploaded = branding == null ? null : branding.getEmailLogoUrl();
        if (StringUtils.isBlank(uploaded))
        {
            return portalUrl + EmailLayout.DEFAULT_LOGO_PATH;
        }
        return uploaded.matches("(?i)^(https?:)?//.*") ? uploaded : portalUrl + "/api" + uploaded;
    }

    private static final java.time.format.DateTimeFormatter DISPLAY_DATE =
            java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final java.time.format.DateTimeFormatter DISPLAY_DATE_TIME =
            java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");
    private static final java.util.regex.Pattern ISO_DATE = java.util.regex.Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final java.util.regex.Pattern ISO_DATE_TIME =
            java.util.regex.Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?");

    /**
     * A date or timestamp as shown to people: dd-MM-yyyy, with HH:mm for timestamps. Accepts java.time values and
     * their ISO strings (whole value only, so titles that merely contain a date are left alone); anything else is
     * returned unchanged.
     */
    static Object displayDate(Object value)
    {
        if (value instanceof LocalDateTime time)
        {
            return time.format(DISPLAY_DATE_TIME);
        }
        if (value instanceof java.time.LocalDate day)
        {
            return day.format(DISPLAY_DATE);
        }
        if (value instanceof java.time.OffsetDateTime time)
        {
            return time.format(DISPLAY_DATE_TIME);
        }
        if (value instanceof String text)
        {
            try
            {
                if (ISO_DATE_TIME.matcher(text).matches())
                {
                    return LocalDateTime.parse(text).format(DISPLAY_DATE_TIME);
                }
                if (ISO_DATE.matcher(text).matches())
                {
                    return java.time.LocalDate.parse(text).format(DISPLAY_DATE);
                }
            }
            catch (java.time.format.DateTimeParseException e)
            {
                return value;
            }
        }
        return value;
    }

    /** Signatory pages live on the signing portal, everything else on the main portal. */
    private String link(String path)
    {
        if (path == null || path.startsWith("http://") || path.startsWith("https://"))
        {
            return path;
        }
        String normalised = path.startsWith("/") ? path : "/" + path;
        if (normalised.equals("/sign") || normalised.startsWith("/sign/"))
        {
            return signingPortalUrl + normalised.substring("/sign".length());
        }
        return portalUrl + normalised;
    }

    /** Branding only decorates a message, so a branding failure must not stop the notification. */
    private BrandingDTO branding(Organisation organisation)
    {
        try
        {
            return organisation == null ? brandingService.getPlatform()
                    : brandingService.getForOrganisation(organisation.getId().toString());
        }
        catch (RuntimeException e)
        {
            logger.warn("Branding for organisation {} is not available, sending without it: {}",
                    organisation == null ? "-" : organisation.getId(), e.toString());
            return null;
        }
    }

    private String provider(NotificationChannel channel)
    {
        return notificationProviderConfigRepository.findByChannelAndActiveTrueOrderByPriorityAsc(channel).stream()
                .map(NotificationProviderConfig::getSenderAddress)
                .filter(StringUtils::isNotBlank)
                .findFirst()
                .orElse(null);
    }

    private static OrganisationNotificationSettings settings(Organisation organisation)
    {
        return organisation == null ? null : organisation.getNotificationSettings();
    }

    private Optional<OrganisationUser> findUser(String id)
    {
        // Callers pass either the StarSign user id or the Keycloak user id; both are UUIDs, so a well-formed
        // id that is not a StarSign user is looked up as a Keycloak id too
        Optional<OrganisationUser> user = Optional.empty();
        try
        {
            user = organisationUserRepository.findById(UUID.fromString(id));
        }
        catch (IllegalArgumentException e)
        {
            // Not a UUID: only a Keycloak id can match
        }
        return user.isPresent() ? user : organisationUserRepository.findByKeycloakUserId(id);
    }

    private Notification load(String id)
    {
        return notificationRepository.findById(uuid(id, "notification"))
                .orElseThrow(() -> NotificationServiceException.notFound("The notification does not exist"));
    }

    private NotificationDTO toDto(Notification entity)
    {
        NotificationDTO dto = notificationMapper.toNotificationDTO(entity);
        dto.setOrganisationId(entity.getOrganisation() == null ? null : entity.getOrganisation().getId().toString());
        dto.setRecipientUserId(entity.getRecipientUser() == null ? null : entity.getRecipientUser().getId().toString());
        dto.setSignatoryId(entity.getSignatory() == null ? null : entity.getSignatory().getId().toString());
        dto.setWorkflowId(entity.getWorkflow() == null ? null : entity.getWorkflow().getId().toString());
        return dto;
    }

    private List<NotificationDTO> toDtos(List<Notification> entities)
    {
        List<NotificationDTO> dtos = new ArrayList<NotificationDTO>(entities.size());
        for (Notification entity : entities)
        {
            dtos.add(toDto(entity));
        }
        return dtos;
    }

    private List<SigningReminderDTO> toReminderDtos(List<SigningReminder> entities)
    {
        List<SigningReminderDTO> dtos = new ArrayList<SigningReminderDTO>(entities.size());
        entities.stream().sorted(Comparator.comparingInt(SigningReminder::getSequence)).forEach(entity -> {
            SigningReminderDTO dto = signingReminderMapper.toSigningReminderDTO(entity);
            dto.setSignatoryId(entity.getSignatory().getId().toString());
            dto.setWorkflowId(entity.getWorkflow().getId().toString());
            dtos.add(dto);
        });
        return dtos;
    }

    private static void putIfPresent(Map<String, Object> model, String key, String value)
    {
        if (StringUtils.isNotBlank(value))
        {
            model.put(key, value);
        }
    }

    private static String asString(Object value)
    {
        return value == null ? null : value.toString();
    }

    private static String humanise(NotificationEventType eventType)
    {
        return StringUtils.capitalize(eventType.name().toLowerCase().replace('_', ' '));
    }

    private static String rootMessage(Throwable e)
    {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root)
        {
            root = root.getCause();
        }
        return root == e ? e.toString() : e.getClass().getSimpleName() + ": " + root;
    }

    private static UUID uuid(String id, String what)
    {
        try
        {
            return UUID.fromString(id.trim());
        }
        catch (IllegalArgumentException e)
        {
            throw NotificationServiceException.notFound("'" + id + "' is not the id of a " + what);
        }
    }
}
