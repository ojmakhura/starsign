package bw.co.starsign.workflow;

import bw.co.starsign.audit.ActorType;
import bw.co.starsign.audit.AuditEventDTO;
import bw.co.starsign.audit.AuditEventType;
import bw.co.starsign.audit.AuditOutcome;
import bw.co.starsign.audit.AuditTrailService;
import bw.co.starsign.document.Document;
import bw.co.starsign.document.DocumentRepository;
import bw.co.starsign.document.DocumentStatus;
import bw.co.starsign.identity.OrganisationUser;
import bw.co.starsign.messaging.OutboxEventDTO;
import bw.co.starsign.messaging.OutboxService;
import bw.co.starsign.messaging.OutboxStatus;
import bw.co.starsign.notification.NotificationChannel;
import bw.co.starsign.notification.NotificationEventType;
import bw.co.starsign.notification.NotificationRequestDTO;
import bw.co.starsign.notification.NotificationService;
import bw.co.starsign.notification.NotificationServiceException;
import bw.co.starsign.notification.SigningReminderDTO;
import bw.co.starsign.signing.SigningInvitationService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * What the workflow service and the workflow engine both do when a workflow moves: activating stages and
 * inviting signatories, keeping the document status in step, and recording every transition as an audit
 * event, an outbox event and, for configuration changes, a {@link WorkflowConfigurationChange}. Runs inside
 * the caller's transaction.
 */
@Component
public class WorkflowSupport
{
    /** Signatories who have been invited and have not finished. */
    static final Set<SignatoryStatus> ACTIVE_SIGNATORY =
            EnumSet.of(SignatoryStatus.INVITED, SignatoryStatus.VIEWED, SignatoryStatus.VERIFIED);

    /** Who performs a transition, for the audit trail. */
    public record Actor(ActorType type, String id, String name)
    {
        public static final Actor SYSTEM = new Actor(ActorType.SYSTEM, "system", "StarSign");
    }

    private final SigningInvitationService signingInvitationService;
    private final NotificationService notificationService;
    private final AuditTrailService auditTrailService;
    private final OutboxService outboxService;
    private final DocumentRepository documentRepository;

    public WorkflowSupport(
        @Lazy SigningInvitationService signingInvitationService,
        @Lazy NotificationService notificationService,
        @Lazy AuditTrailService auditTrailService,
        @Lazy OutboxService outboxService,
        DocumentRepository documentRepository)
    {
        this.signingInvitationService = signingInvitationService;
        this.notificationService = notificationService;
        this.auditTrailService = auditTrailService;
        this.outboxService = outboxService;
        this.documentRepository = documentRepository;
    }

    // ---------------------------------------------------------------- progression

    static SigningStage stage(SigningWorkflow workflow, int stageNumber)
    {
        return workflow.getStages().stream()
                .filter(stage -> stage.getStageNumber() == stageNumber)
                .findFirst()
                .orElse(null);
    }

    static List<Signatory> members(SigningStage stage)
    {
        return stage.getWorkflow().getSignatories().stream()
                .filter(s -> s.getStage() == stage)
                .sorted(WorkflowViews.SIGNATORY_ORDER)
                .toList();
    }

    /**
     * Activates the first PENDING stage after the current one, provided the current stage (if any) is
     * finished (rule 7), and invites its first signatories.
     *
     * @return the activated stage, or null when the current stage is not finished or no stage is left
     */
    SigningStage activateNextStage(SigningWorkflow workflow, boolean sendInvitations, Actor actor)
    {
        SigningStage current = stage(workflow, workflow.getCurrentStageNumber());
        if (current != null && current.getStatus() != StageStatus.COMPLETED && current.getStatus() != StageStatus.SKIPPED)
        {
            return null;
        }
        List<SigningStage> pending = workflow.getStages().stream()
                .filter(stage -> stage.getStatus() == StageStatus.PENDING
                        && stage.getStageNumber() > workflow.getCurrentStageNumber())
                .sorted(WorkflowViews.STAGE_ORDER)
                .toList();
        LocalDateTime now = LocalDateTime.now();
        for (SigningStage next : pending)
        {
            workflow.setCurrentStageNumber(next.getStageNumber());
            if (members(next).isEmpty())
            {
                // Nobody to wait for: validation prevents this, but a stage emptied by an amendment must not
                // block the workflow
                next.setStatus(StageStatus.SKIPPED);
                next.setCompletedAt(now);
                continue;
            }
            next.setStatus(StageStatus.ACTIVE);
            next.setActivatedAt(now);
            audit(workflow, AuditEventType.SIGNING_STAGE_ACTIVATED, actor, null,
                    "Stage " + next.getStageNumber() + " (" + next.getName() + ") activated");
            publish(workflow, "STAGE_ACTIVATED", Map.of("stageNumber", next.getStageNumber()));
            inviteDue(next, sendInvitations);
            return next;
        }
        return null;
    }

    /**
     * Invites the signatories of an active stage whose turn it is: everyone at once in PARALLEL mode; in
     * SEQUENTIAL mode the next signing order, once nobody of the stage is still busy signing.
     */
    void inviteDue(SigningStage stage, boolean sendInvitations)
    {
        if (stage.getStatus() != StageStatus.ACTIVE)
        {
            return;
        }
        List<Signatory> members = members(stage);
        boolean sequential = stage.getWorkflow().getExecutionMode() != ExecutionMode.PARALLEL;
        if (sequential && members.stream().anyMatch(s -> ACTIVE_SIGNATORY.contains(s.getStatus())))
        {
            return;
        }
        List<Signatory> pending = members.stream().filter(s -> s.getStatus() == SignatoryStatus.PENDING).toList();
        if (pending.isEmpty())
        {
            return;
        }
        if (sequential)
        {
            int next = pending.stream().mapToInt(Signatory::getSigningOrder).min().getAsInt();
            pending = pending.stream().filter(s -> s.getSigningOrder() == next).toList();
        }
        for (Signatory signatory : pending)
        {
            invite(signatory, sendInvitations);
        }
    }

    /**
     * Makes the signatory INVITED and, when asked to, sends the invitation: external signatories get a
     * one-time link from the invitation service, internal users (who sign in the portal with their own
     * account) a notification. Reminders are scheduled when the workflow sends them.
     */
    void invite(Signatory signatory, boolean send)
    {
        SigningWorkflow workflow = signatory.getWorkflow();
        // A resent invitation leaves a signatory who already opened or verified where they are
        if (signatory.getStatus() == SignatoryStatus.PENDING || signatory.getStatus() == SignatoryStatus.EXPIRED)
        {
            signatory.setStatus(SignatoryStatus.INVITED);
        }
        if (!send)
        {
            return;
        }
        if (signatory.getSignatoryType() == SignatoryType.INTERNAL_USER && signatory.getInternalUser() != null)
        {
            OrganisationUser user = signatory.getInternalUser();
            Map<String, Object> model = model(workflow);
            model.put("signatoryName", signatory.getFullName());
            // The portal's sign page (SigningLinks.internalSigningLink): the notification service turns the path
            // into the full {{link}} on the main portal, and the in-app notification opens it
            model.put("linkPath", "/signing/workflows/" + workflow.getId() + "/signatories/" + signatory.getId());
            notify(workflow, NotificationEventType.NEXT_SIGNATORY_ACTIVATED, NotificationChannel.IN_APP,
                    signatory.getFullName(), null, user.getKeycloakUserId(), signatory, model);
            if (signatory.isNotifyByEmail())
            {
                notify(workflow, NotificationEventType.NEXT_SIGNATORY_ACTIVATED, NotificationChannel.EMAIL,
                        signatory.getFullName(), signatory.getEmailAddress(), user.getKeycloakUserId(), signatory, model);
            }
        }
        else
        {
            signingInvitationService.issue(signatory.getId().toString());
        }
        signatory.setInvitationSentAt(LocalDateTime.now());
        if (workflow.getReminderIntervalHours() > 0)
        {
            notificationService.scheduleReminders(workflow.getId().toString(), signatory.getId().toString());
        }
    }

    /** Stops the reminders of every signatory that was invited. */
    void cancelReminders(SigningWorkflow workflow)
    {
        for (Signatory signatory : workflow.getSignatories())
        {
            if (signatory.getInvitationSentAt() != null)
            {
                notificationService.cancelReminders(workflow.getId().toString(), signatory.getId().toString());
            }
        }
    }

    /** Sends the signatory a reminder now; the notification service's refusals become workflow rule violations. */
    SigningReminderDTO remind(Signatory signatory)
    {
        try
        {
            return notificationService.sendReminder(signatory.getWorkflow().getId().toString(), signatory.getId().toString());
        }
        catch (NotificationServiceException e)
        {
            throw new SigningWorkflowServiceException(e.getMessage(), e.isNotFound());
        }
    }

    void cancelReminders(Signatory signatory)
    {
        notificationService.cancelReminders(signatory.getWorkflow().getId().toString(), signatory.getId().toString());
    }

    void revokeInvitations(SigningWorkflow workflow, String reason)
    {
        signingInvitationService.revokeForWorkflow(workflow.getId().toString(), reason);
    }

    // ---------------------------------------------------------------- document

    /**
     * Moves the workflow's document along the DocumentStatus flow. DocumentService has no operation for it,
     * so only the status (and completion time) of the document row is written here.
     */
    void documentStatus(SigningWorkflow workflow, DocumentStatus status)
    {
        Document document = workflow.getDocument();
        if (document.getStatus() == status || document.getStatus() == DocumentStatus.ARCHIVED)
        {
            return;
        }
        document.setStatus(status);
        if (status == DocumentStatus.COMPLETED)
        {
            document.setCompletedAt(LocalDateTime.now());
        }
        documentRepository.save(document);
    }

    /** PENDING_SIGNATURES until the first signature, PARTIALLY_SIGNED afterwards. */
    void documentSigningStatus(SigningWorkflow workflow)
    {
        boolean signed = workflow.getSignatories().stream().anyMatch(s -> s.getStatus() == SignatoryStatus.SIGNED);
        documentStatus(workflow, signed ? DocumentStatus.PARTIALLY_SIGNED : DocumentStatus.PENDING_SIGNATURES);
    }

    // ---------------------------------------------------------------- records

    void audit(SigningWorkflow workflow, AuditEventType type, Actor actor, Signatory signatory, String details)
    {
        AuditEventDTO event = new AuditEventDTO();
        event.setEventType(type);
        event.setOccurredAt(LocalDateTime.now());
        event.setOrganisationId(workflow.getOrganisation().getId().toString());
        event.setActorType(actor.type());
        event.setActorId(actor.id());
        event.setActorDisplayName(actor.name());
        event.setOutcome(AuditOutcome.SUCCESS);
        event.setDocumentId(workflow.getDocument().getId().toString());
        event.setWorkflowId(workflow.getId().toString());
        event.setSignatoryId(signatory == null || signatory.getId() == null ? null : signatory.getId().toString());
        event.setEntityType("SigningWorkflow");
        event.setEntityId(workflow.getId().toString());
        event.setDocumentSha256(workflow.getSourceDocumentSha256());
        event.setDetails(details);
        auditTrailService.record(event);
    }

    /** Queues a {@code workflow.<event>} message for the other services, in the business transaction. */
    void publish(SigningWorkflow workflow, String eventType, Map<String, Object> data)
    {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("workflowId", workflow.getId().toString());
        payload.put("organisationId", workflow.getOrganisation().getId().toString());
        payload.put("documentId", workflow.getDocument().getId().toString());
        payload.put("referenceNumber", workflow.getReferenceNumber());
        payload.put("status", workflow.getStatus().name());
        payload.putAll(data);

        OutboxEventDTO event = new OutboxEventDTO();
        event.setAggregateType("SigningWorkflow");
        event.setAggregateId(workflow.getId().toString());
        event.setEventType(eventType);
        event.setOrganisationId(workflow.getOrganisation().getId().toString());
        event.setRoutingKey("workflow." + eventType.toLowerCase());
        event.setPayload(WorkflowViews.json(payload));
        event.setStatus(OutboxStatus.PENDING);
        event.setAttempts(0);
        event.setIdempotencyKey(UUID.randomUUID().toString());
        event.setCorrelationId(workflow.getId().toString());
        outboxService.enqueue(event);
    }

    /** Adds a numbered entry to the workflow's configuration history; snapshots are JSON. */
    void changed(SigningWorkflow workflow, WorkflowChangeType type, String description, String before,
            String reason, String changedBy)
    {
        LocalDateTime now = LocalDateTime.now();
        WorkflowConfigurationChange change = WorkflowConfigurationChange.Factory.newInstance();
        change.setChangeNumber(workflow.getChanges().stream()
                .mapToInt(WorkflowConfigurationChange::getChangeNumber).max().orElse(0) + 1);
        change.setChangeType(type);
        change.setDescription(description);
        change.setChangedBy(changedBy);
        change.setChangedAt(now);
        change.setBeforeSnapshot(before);
        change.setAfterSnapshot(snapshot(workflow));
        change.setReason(StringUtils.trimToNull(reason));
        change.setCreatedAt(now);
        change.setCreatedBy(changedBy);
        change.setWorkflow(workflow);
        workflow.getChanges().add(change);
    }

    /** The configuration of the workflow as JSON, for the configuration history. */
    static String snapshot(SigningWorkflow workflow)
    {
        Map<String, Object> snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("title", workflow.getTitle());
        snapshot.put("message", workflow.getMessage());
        snapshot.put("status", workflow.getStatus() == null ? null : workflow.getStatus().name());
        snapshot.put("executionMode", workflow.getExecutionMode() == null ? null : workflow.getExecutionMode().name());
        snapshot.put("expiresAt", text(workflow.getExpiresAt()));
        snapshot.put("assuranceLevel", workflow.getAssuranceLevel() == null ? null : workflow.getAssuranceLevel().name());
        snapshot.put("verificationMethod",
                workflow.getVerificationMethod() == null ? null : workflow.getVerificationMethod().name());
        snapshot.put("singleUseInvitations", workflow.isSingleUseInvitations());
        snapshot.put("invitationValidityHours", workflow.getInvitationValidityHours());
        snapshot.put("allowDecline", workflow.isAllowDecline());
        snapshot.put("declineTerminatesWorkflow", workflow.isDeclineTerminatesWorkflow());
        snapshot.put("requireDeclineReason", workflow.isRequireDeclineReason());
        snapshot.put("sendCompletedCopy", workflow.isSendCompletedCopy());
        snapshot.put("allowedCaptureMethods", workflow.getAllowedCaptureMethods());
        snapshot.put("reminderIntervalHours", workflow.getReminderIntervalHours());
        snapshot.put("configurationVersion", workflow.getConfigurationVersion());
        List<Map<String, Object>> stages = new ArrayList<Map<String, Object>>();
        for (SigningStage stage : workflow.getStages().stream().sorted(WorkflowViews.STAGE_ORDER).toList())
        {
            Map<String, Object> s = new LinkedHashMap<String, Object>();
            s.put("id", text(stage.getId()));
            s.put("stageNumber", stage.getStageNumber());
            s.put("name", stage.getName());
            s.put("completionRule", stage.getCompletionRule() == null ? null : stage.getCompletionRule().name());
            s.put("minimumSignatures", stage.getMinimumSignatures());
            s.put("status", stage.getStatus() == null ? null : stage.getStatus().name());
            s.put("dueAt", text(stage.getDueAt()));
            List<Map<String, Object>> signatories = new ArrayList<Map<String, Object>>();
            for (Signatory signatory : members(stage))
            {
                Map<String, Object> p = new LinkedHashMap<String, Object>();
                p.put("id", text(signatory.getId()));
                p.put("fullName", signatory.getFullName());
                p.put("emailAddress", signatory.getEmailAddress());
                p.put("signatoryType", signatory.getSignatoryType() == null ? null : signatory.getSignatoryType().name());
                p.put("signingOrder", signatory.getSigningOrder());
                p.put("mandatory", signatory.isMandatory());
                p.put("status", signatory.getStatus() == null ? null : signatory.getStatus().name());
                p.put("deadline", text(signatory.getDeadline()));
                signatories.add(p);
            }
            s.put("signatories", signatories);
            stages.add(s);
        }
        snapshot.put("stages", stages);
        snapshot.put("fieldCount", workflow.getFields().size());
        return WorkflowViews.json(snapshot);
    }

    private static String text(Object value)
    {
        return value == null ? null : value.toString();
    }

    // ---------------------------------------------------------------- notifications

    Map<String, Object> model(SigningWorkflow workflow)
    {
        Map<String, Object> model = new LinkedHashMap<String, Object>();
        model.put("workflowId", workflow.getId().toString());
        model.put("workflowTitle", workflow.getTitle());
        model.put("referenceNumber", workflow.getReferenceNumber());
        model.put("documentTitle", workflow.getDocument().getTitle());
        model.put("organisationName", workflow.getOrganisation().getName());
        model.put("ownerName", WorkflowViews.name(workflow.getOwner()));
        model.put("message", StringUtils.defaultString(workflow.getMessage()));
        model.put("expiresAt", text(workflow.getExpiresAt()));
        return model;
    }

    /** In-app and e-mail notification to the owner of the workflow. */
    void notifyOwner(SigningWorkflow workflow, NotificationEventType type, Map<String, Object> model)
    {
        OrganisationUser owner = workflow.getOwner();
        String name = WorkflowViews.name(owner);
        notify(workflow, type, NotificationChannel.IN_APP, name, null, owner.getKeycloakUserId(), null, model);
        notify(workflow, type, NotificationChannel.EMAIL, name, owner.getEmailAddress(), owner.getKeycloakUserId(), null,
                model);
    }

    /** E-mail to a signatory (and in-app for internal users). */
    void notifySignatory(Signatory signatory, NotificationEventType type, Map<String, Object> model)
    {
        SigningWorkflow workflow = signatory.getWorkflow();
        Map<String, Object> personal = new LinkedHashMap<String, Object>(model);
        personal.put("signatoryName", signatory.getFullName());
        String userId = signatory.getInternalUser() == null ? null : signatory.getInternalUser().getKeycloakUserId();
        if (userId != null)
        {
            notify(workflow, type, NotificationChannel.IN_APP, signatory.getFullName(), null, userId, signatory, personal);
        }
        if (signatory.isNotifyByEmail())
        {
            notify(workflow, type, NotificationChannel.EMAIL, signatory.getFullName(), signatory.getEmailAddress(), userId,
                    signatory, personal);
        }
    }

    private void notify(SigningWorkflow workflow, NotificationEventType type, NotificationChannel channel, String name,
            String address, String userId, Signatory signatory, Map<String, Object> model)
    {
        NotificationRequestDTO request = new NotificationRequestDTO();
        request.setOrganisationId(workflow.getOrganisation().getId().toString());
        request.setEventType(type);
        request.setChannel(channel);
        request.setRecipientName(name);
        request.setRecipientAddress(address);
        request.setRecipientUserId(userId);
        request.setSignatoryId(signatory == null ? null : signatory.getId().toString());
        request.setWorkflowId(workflow.getId().toString());
        request.setTemplateModel(model);
        // One notification per event, recipient and channel within the second: guards against the same
        // transition being enqueued twice, while a later resend or renewal still notifies again
        request.setDeduplicationKey(String.join(":", type.name(), channel.name(), workflow.getId().toString(),
                signatory == null ? "owner" : signatory.getId().toString(),
                String.valueOf(System.currentTimeMillis() / 1000)));
        request.setLocale(signatory != null && signatory.getLocale() != null ? signatory.getLocale()
                : workflow.getOrganisation().getLocale());
        notificationService.enqueue(request);
    }
}
