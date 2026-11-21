// license-header java merge-point
/**
 * This is only generated once! It will never be overwritten.
 * You can (and have to!) safely modify it by hand.
 * TEMPLATE:    SpringServiceImpl.vsl in andromda-spring cartridge
 * MODEL CLASS: AndroMDAModel::backend::bw.co.starsign::workflow::WorkflowEngineService
 * STEREOTYPE:  Service
 */
package bw.co.starsign.workflow;

import bw.co.starsign.audit.AuditEventType;
import bw.co.starsign.audit.AuditTrailService;
import bw.co.starsign.document.DocumentArtifact;
import bw.co.starsign.document.DocumentArtifactDTO;
import bw.co.starsign.document.DocumentArtifactRepository;
import bw.co.starsign.document.DocumentMapper;
import bw.co.starsign.document.DocumentRepository;
import bw.co.starsign.document.DocumentStatus;
import bw.co.starsign.messaging.OutboxService;
import bw.co.starsign.notification.NotificationEventType;
import bw.co.starsign.notification.NotificationService;
import bw.co.starsign.signing.CompletedCopyLinks;
import bw.co.starsign.signing.InvitationStatus;
import bw.co.starsign.signing.PdfSigningEngine;
import bw.co.starsign.signing.SignatoryInvitationDTO;
import bw.co.starsign.signing.SigningInvitationService;
import bw.co.starsign.workflow.WorkflowSupport.Actor;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * @see bw.co.starsign.workflow.WorkflowEngineService
 */
@Service("workflowEngineService")
@Transactional(propagation = Propagation.REQUIRED, readOnly = false)
public class WorkflowEngineServiceImpl
    extends WorkflowEngineServiceBase
{
    /** Consumer name under which signing events are marked processed in the outbox inbox. */
    private static final String CONSUMER = "workflow-engine";
    /** How long a workflow may stay COMPLETING before finalisation counts as failed. */
    private static final int FINALISATION_RETRY_HOURS = 24;
    private static final Set<SignatoryStatus> OUT = EnumSet.of(SignatoryStatus.DECLINED, SignatoryStatus.CANCELLED,
            SignatoryStatus.EXPIRED, SignatoryStatus.SKIPPED);
    private static final Set<InvitationStatus> CLOSED_INVITATION = EnumSet.of(InvitationStatus.CONSUMED,
            InvitationStatus.EXPIRED, InvitationStatus.REVOKED, InvitationStatus.FAILED);

    private final WorkflowSupport support;
    private final WorkflowViews views;
    private final DocumentArtifactRepository documentArtifactRepository;
    private final CompletedCopyLinks completedCopyLinks;
    private final TransactionTemplate newTransaction;

    public WorkflowEngineServiceImpl(
        @Lazy SigningInvitationService signingInvitationService,
        @Lazy PdfSigningEngine pdfSigningEngine,
        @Lazy NotificationService notificationService,
        @Lazy AuditTrailService auditTrailService,
        @Lazy OutboxService outboxService,
        SigningWorkflowRepository signingWorkflowRepository,
        SigningWorkflowMapper signingWorkflowMapper,
        SigningStageRepository signingStageRepository,
        SigningStageMapper signingStageMapper,
        SignatoryRepository signatoryRepository,
        SignatoryMapper signatoryMapper,
        DocumentRepository documentRepository,
        DocumentMapper documentMapper,
        WorkflowSupport support,
        WorkflowViews views,
        DocumentArtifactRepository documentArtifactRepository,
        CompletedCopyLinks completedCopyLinks,
        PlatformTransactionManager transactionManager) {

        super(
            signingInvitationService,
            pdfSigningEngine,
            notificationService,
            auditTrailService,
            outboxService,
            signingWorkflowRepository,
            signingWorkflowMapper,
            signingStageRepository,
            signingStageMapper,
            signatoryRepository,
            signatoryMapper,
            documentRepository,
            documentMapper);
        this.support = support;
        this.views = views;
        this.documentArtifactRepository = documentArtifactRepository;
        this.completedCopyLinks = completedCopyLinks;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * @see bw.co.starsign.workflow.WorkflowEngineService#activateNextStage(String)
     */
    @Override
    protected SigningStageDTO handleActivateNextStage(String workflowId)
        throws Exception
    {
        SigningWorkflow workflow = lock(workflowId);
        if (workflow.getStatus() != WorkflowStatus.IN_PROGRESS)
        {
            throw new WorkflowEngineServiceException("Stages are only activated while the workflow is in progress");
        }
        SigningStage stage = support.activateNextStage(workflow, true, Actor.SYSTEM);
        signingWorkflowRepository.save(workflow);
        return stage == null ? null : views.stage(stage);
    }

    /**
     * @see bw.co.starsign.workflow.WorkflowEngineService#evaluateStage(String)
     */
    @Override
    protected boolean handleEvaluateStage(String stageId)
        throws Exception
    {
        SigningStage stage = signingStageRepository.findById(uuid(stageId, "stage"))
                .orElseThrow(() -> WorkflowEngineServiceException.notFound("The stage does not exist"));
        SigningWorkflow workflow = lock(stage.getWorkflow().getId().toString());
        boolean completed = evaluate(stage);
        signingWorkflowRepository.save(workflow);
        return completed;
    }

    /**
     * @see bw.co.starsign.workflow.WorkflowEngineService#onSignatorySigned(String, String)
     */
    @Override
    protected void handleOnSignatorySigned(String signatoryId, String eventId)
        throws Exception
    {
        if (alreadyProcessed(eventId))
        {
            return;
        }
        Signatory signatory = loadSignatory(signatoryId);
        SigningWorkflow workflow = lock(signatory.getWorkflow().getId().toString());
        String outcome;
        if (workflow.getStatus() != WorkflowStatus.IN_PROGRESS)
        {
            // The signing service refuses signatures outside IN_PROGRESS (rule 13): nothing moves on
            logger.warn("Signature of signatory {} ignored: workflow {} is {}", signatoryId, workflow.getId(),
                    workflow.getStatus());
            outcome = "IGNORED_" + workflow.getStatus();
        }
        else if (signatory.getStatus() != SignatoryStatus.SIGNED && !WorkflowSupport.ACTIVE_SIGNATORY.contains(signatory.getStatus()))
        {
            logger.warn("Signature of signatory {} ignored: the signatory is {}", signatoryId, signatory.getStatus());
            outcome = "IGNORED_" + signatory.getStatus();
        }
        else
        {
            if (signatory.getStatus() != SignatoryStatus.SIGNED)
            {
                signatory.setStatus(SignatoryStatus.SIGNED);
                signatory.setSignedAt(LocalDateTime.now());
            }
            if (signatory.getInvitationSentAt() != null)
            {
                support.cancelReminders(signatory);
            }
            support.documentSigningStatus(workflow);
            Map<String, Object> model = support.model(workflow);
            model.put("signatoryName", signatory.getFullName());
            support.notifyOwner(workflow, NotificationEventType.SIGNATURE_COMPLETED, model);
            advance(workflow, signatory.getStage());
            outcome = "SIGNED";
        }
        signingWorkflowRepository.save(workflow);
        processed(eventId, outcome);
    }

    /**
     * @see bw.co.starsign.workflow.WorkflowEngineService#onSignatoryDeclined(String, String)
     */
    @Override
    protected void handleOnSignatoryDeclined(String signatoryId, String eventId)
        throws Exception
    {
        if (alreadyProcessed(eventId))
        {
            return;
        }
        Signatory signatory = loadSignatory(signatoryId);
        SigningWorkflow workflow = lock(signatory.getWorkflow().getId().toString());
        if (workflow.getStatus() != WorkflowStatus.IN_PROGRESS)
        {
            logger.warn("Decline of signatory {} ignored: workflow {} is {}", signatoryId, workflow.getId(),
                    workflow.getStatus());
            processed(eventId, "IGNORED_" + workflow.getStatus());
            return;
        }
        if (signatory.getStatus() != SignatoryStatus.DECLINED)
        {
            if (!WorkflowSupport.ACTIVE_SIGNATORY.contains(signatory.getStatus()))
            {
                logger.warn("Decline of signatory {} ignored: the signatory is {}", signatoryId, signatory.getStatus());
                processed(eventId, "IGNORED_" + signatory.getStatus());
                return;
            }
            signatory.setStatus(SignatoryStatus.DECLINED);
            signatory.setDeclinedAt(LocalDateTime.now());
        }
        if (signatory.getInvitationSentAt() != null)
        {
            support.cancelReminders(signatory);
        }
        SigningStage stage = signatory.getStage();
        Map<String, Object> model = support.model(workflow);
        model.put("signatoryName", signatory.getFullName());
        model.put("reason", StringUtils.defaultString(signatory.getDeclineReason()));
        support.notifyOwner(workflow, NotificationEventType.DOCUMENT_DECLINED, model);

        if (signatory.isDeclineTerminatesWorkflow() || !completable(stage))
        {
            decline(workflow, stage, signatory);
        }
        else
        {
            // An optional signatory's decline: the others carry on
            advance(workflow, stage);
        }
        signingWorkflowRepository.save(workflow);
        processed(eventId, "DECLINED");
    }

    /**
     * @see bw.co.starsign.workflow.WorkflowEngineService#finalise(String)
     */
    @Override
    protected SigningWorkflowDTO handleFinalise(String workflowId)
        throws Exception
    {
        return views.workflow(finalise(lock(workflowId)));
    }

    /**
     * @see bw.co.starsign.workflow.WorkflowEngineService#expireOverdue()
     */
    @Override
    protected int handleExpireOverdue()
        throws Exception
    {
        int expired = 0;
        LocalDateTime now = LocalDateTime.now();
        for (UUID id : signingWorkflowRepository.findIdsByStatusAndExpiresAtBefore(WorkflowStatus.IN_PROGRESS, now))
        {
            expired += inOwnTransaction(id, workflow -> {
                if (workflow.getStatus() == WorkflowStatus.IN_PROGRESS && workflow.getExpiresAt().isBefore(now))
                {
                    expire(workflow, "The workflow passed its expiry date");
                }
            });
        }
        // Stage due dates and signatory deadlines inside the workflows that are still running
        for (UUID id : signingWorkflowRepository.findIdsByStatusIn(List.of(WorkflowStatus.IN_PROGRESS)))
        {
            expired += inOwnTransaction(id, workflow -> expireDeadlines(workflow, now));
        }
        return expired + signingInvitationService.expireOverdue();
    }

    /**
     * @see bw.co.starsign.workflow.WorkflowEngineService#recoverStalled()
     */
    @Override
    protected int handleRecoverStalled()
        throws Exception
    {
        int recovered = 0;
        for (UUID id : signingWorkflowRepository.findIdsByStatusIn(List.of(WorkflowStatus.COMPLETING, WorkflowStatus.FAILED)))
        {
            try
            {
                newTransaction.executeWithoutResult(status -> finalise(lock(id.toString())));
                recovered++;
            }
            catch (RuntimeException e)
            {
                logger.error("Finalising workflow {} failed again", id, e);
                inOwnTransaction(id, this::failIfOverdue);
            }
        }
        // Workflows left between two steps: a completed stage whose successor was not activated, a stage
        // whose rule is met but not applied, or a sequential stage nobody was invited to next
        for (UUID id : signingWorkflowRepository.findIdsByStatusIn(List.of(WorkflowStatus.IN_PROGRESS)))
        {
            recovered += inOwnTransaction(id, this::redrive);
        }
        return recovered;
    }

    // ---------------------------------------------------------------- progression

    /**
     * Applies the stage's completion rule (rule 8); on completion the signatories still waiting are no longer
     * needed: those not yet invited are SKIPPED, open invitations are withdrawn.
     */
    private boolean evaluate(SigningStage stage)
    {
        if (stage.getStatus() == StageStatus.COMPLETED)
        {
            return true;
        }
        if (stage.getStatus() != StageStatus.ACTIVE)
        {
            return false;
        }
        List<Signatory> members = WorkflowSupport.members(stage);
        long signed = members.stream().filter(s -> s.getStatus() == SignatoryStatus.SIGNED).count();
        boolean satisfied = switch (stage.getCompletionRule())
        {
            case ALL_REQUIRED -> signed > 0 && members.stream().filter(Signatory::isMandatory)
                    .allMatch(s -> s.getStatus() == SignatoryStatus.SIGNED);
            case ANY_ONE -> signed > 0;
            case MINIMUM_COUNT -> signed >= (stage.getMinimumSignatures() == null ? 1 : stage.getMinimumSignatures());
        };
        if (!satisfied)
        {
            return false;
        }
        SigningWorkflow workflow = stage.getWorkflow();
        stage.setStatus(StageStatus.COMPLETED);
        stage.setCompletedAt(LocalDateTime.now());
        for (Signatory signatory : members)
        {
            if (signatory.getStatus() == SignatoryStatus.PENDING)
            {
                signatory.setStatus(SignatoryStatus.SKIPPED);
            }
            else if (WorkflowSupport.ACTIVE_SIGNATORY.contains(signatory.getStatus()))
            {
                withdraw(signatory, "The stage was completed without this signature");
                signatory.setStatus(SignatoryStatus.CANCELLED);
            }
        }
        support.audit(workflow, AuditEventType.SIGNING_STAGE_COMPLETED, Actor.SYSTEM, null,
                "Stage " + stage.getStageNumber() + " (" + stage.getName() + ") completed with " + signed + " signature"
                        + (signed == 1 ? "" : "s"));
        support.publish(workflow, "STAGE_COMPLETED", Map.of("stageNumber", stage.getStageNumber()));
        return true;
    }

    /** After a signature or decline: completes the stage and moves on, or invites whoever signs next. */
    private void advance(SigningWorkflow workflow, SigningStage stage)
    {
        if (!evaluate(stage))
        {
            support.inviteDue(stage, true);
            return;
        }
        SigningStage next = support.activateNextStage(workflow, true, Actor.SYSTEM);
        if (next == null && workflow.getStages().stream()
                .noneMatch(s -> s.getStatus() == StageStatus.PENDING || s.getStatus() == StageStatus.ACTIVE))
        {
            complete(workflow);
        }
    }

    /**
     * Whether the stage can still meet its rule with the signatories who have not dropped out.
     */
    private static boolean completable(SigningStage stage)
    {
        List<Signatory> members = WorkflowSupport.members(stage);
        long possible = members.stream().filter(s -> !OUT.contains(s.getStatus())).count();
        return switch (stage.getCompletionRule())
        {
            case ALL_REQUIRED -> possible > 0 && members.stream()
                    .noneMatch(s -> s.isMandatory() && OUT.contains(s.getStatus()));
            case ANY_ONE -> possible > 0;
            case MINIMUM_COUNT -> possible >= (stage.getMinimumSignatures() == null ? 1 : stage.getMinimumSignatures());
        };
    }

    /** The last stage is complete: COMPLETING, and the final document is produced once this commits. */
    private void complete(SigningWorkflow workflow)
    {
        workflow.setStatus(WorkflowStatus.COMPLETING);
        workflow.setModifiedAt(LocalDateTime.now());
        workflow.setModifiedBy(Actor.SYSTEM.id());
        support.publish(workflow, "WORKFLOW_COMPLETING", Map.of());
        String id = workflow.getId().toString();
        if (TransactionSynchronizationManager.isSynchronizationActive())
        {
            // After the commit, so a failing PDF generation cannot undo the signature that completed the
            // workflow; recoverStalled retries it
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization()
            {
                @Override
                public void afterCommit()
                {
                    finaliseInNewTransaction(id);
                }
            });
        }
        else
        {
            signingWorkflowRepository.save(workflow);
            finaliseInNewTransaction(id);
        }
    }

    private void finaliseInNewTransaction(String workflowId)
    {
        try
        {
            newTransaction.executeWithoutResult(status -> finalise(lock(workflowId)));
        }
        catch (RuntimeException e)
        {
            logger.error("Finalising workflow {} failed; it stays COMPLETING and is retried by recoverStalled",
                    workflowId, e);
        }
    }

    /**
     * COMPLETING -> COMPLETED (rule 15): the final signed PDF from the PDF engine, the document COMPLETED,
     * the owner told and completed copies sent. A FAILED workflow is first put back to COMPLETING.
     */
    private SigningWorkflow finalise(SigningWorkflow workflow)
    {
        if (workflow.getStatus() == WorkflowStatus.COMPLETED)
        {
            return workflow;
        }
        if (workflow.getStatus() == WorkflowStatus.FAILED)
        {
            workflow.setStatus(WorkflowStatus.COMPLETING);
        }
        if (workflow.getStatus() != WorkflowStatus.COMPLETING)
        {
            throw new WorkflowEngineServiceException("Only a workflow whose signatures are complete can be finalised; this one is "
                    + workflow.getStatus());
        }
        DocumentArtifactDTO generated = pdfSigningEngine.generateFinalDocument(workflow.getId().toString());
        DocumentArtifact artifact = generated == null || generated.getId() == null ? null
                : documentArtifactRepository.findById(UUID.fromString(generated.getId())).orElse(null);
        if (artifact == null)
        {
            throw new IllegalStateException("The PDF engine returned no stored final document for workflow " + workflow.getId());
        }
        LocalDateTime now = LocalDateTime.now();
        workflow.setFinalArtifact(artifact);
        workflow.setFinalDocumentSha256(artifact.getSha256());
        workflow.setStatus(WorkflowStatus.COMPLETED);
        workflow.setCompletedAt(now);
        workflow.setModifiedAt(now);
        workflow.setModifiedBy(Actor.SYSTEM.id());
        support.documentStatus(workflow, DocumentStatus.COMPLETED);
        support.audit(workflow, AuditEventType.DOCUMENT_GENERATED, Actor.SYSTEM, null,
                "Final signed document generated (SHA-256 " + artifact.getSha256() + ")");
        support.audit(workflow, AuditEventType.WORKFLOW_COMPLETED, Actor.SYSTEM, null, "All stages completed");
        support.publish(workflow, "WORKFLOW_COMPLETED", Map.of("finalArtifactId", artifact.getId().toString(),
                "finalDocumentSha256", StringUtils.defaultString(artifact.getSha256())));

        // The owner opens the completed request in the portal ({{link}} and the in-app notification)
        Map<String, Object> ownerModel = support.model(workflow);
        ownerModel.put("linkPath", "/workflows/" + workflow.getId());
        support.notifyOwner(workflow, NotificationEventType.WORKFLOW_COMPLETED, ownerModel);
        if (workflow.isSendCompletedCopy())
        {
            workflow.getSignatories().stream()
                    .filter(s -> s.getStatus() == SignatoryStatus.SIGNED && s.isReceiveCompletedCopy())
                    .forEach(s -> {
                        // Each signatory gets their own link: external ones a download-only token, internal ones
                        // the request in the portal
                        Map<String, Object> copyModel = support.model(workflow);
                        copyModel.put("link", completedCopyLinks.linkFor(s));
                        if (s.getSignatoryType() == SignatoryType.INTERNAL_USER && s.getInternalUser() != null)
                        {
                            copyModel.put("linkPath",
                                    "/signing/workflows/" + workflow.getId() + "/signatories/" + s.getId());
                        }
                        support.notifySignatory(s, NotificationEventType.COMPLETED_COPY, copyModel);
                    });
        }
        return signingWorkflowRepository.save(workflow);
    }

    /** COMPLETING for longer than the retry window: finalisation counts as failed (COMPLETING -> FAILED). */
    private void failIfOverdue(SigningWorkflow workflow)
    {
        LocalDateTime since = workflow.getModifiedAt() != null ? workflow.getModifiedAt() : workflow.getInitiatedAt();
        if (workflow.getStatus() == WorkflowStatus.COMPLETING && since != null
                && since.isBefore(LocalDateTime.now().minusHours(FINALISATION_RETRY_HOURS)))
        {
            workflow.setStatus(WorkflowStatus.FAILED);
            support.publish(workflow, "WORKFLOW_FAILED", Map.of());
        }
    }

    /** A terminating decline (or one that leaves the stage unable to complete): the workflow is DECLINED. */
    private void decline(SigningWorkflow workflow, SigningStage stage, Signatory declined)
    {
        List<Signatory> waiting = workflow.getSignatories().stream()
                .filter(s -> WorkflowSupport.ACTIVE_SIGNATORY.contains(s.getStatus()))
                .toList();
        stage.setStatus(StageStatus.DECLINED);
        for (SigningStage other : workflow.getStages())
        {
            if (other.getStatus() == StageStatus.PENDING || other.getStatus() == StageStatus.ACTIVE)
            {
                other.setStatus(StageStatus.CANCELLED);
            }
        }
        for (Signatory signatory : workflow.getSignatories())
        {
            if (signatory.getStatus() == SignatoryStatus.PENDING || WorkflowSupport.ACTIVE_SIGNATORY.contains(signatory.getStatus()))
            {
                signatory.setStatus(SignatoryStatus.CANCELLED);
            }
        }
        workflow.setStatus(WorkflowStatus.DECLINED);
        workflow.setModifiedAt(LocalDateTime.now());
        workflow.setModifiedBy(Actor.SYSTEM.id());
        support.revokeInvitations(workflow, "The workflow was declined");
        support.cancelReminders(workflow);
        support.documentStatus(workflow, DocumentStatus.DECLINED);
        support.audit(workflow, AuditEventType.WORKFLOW_UPDATED, Actor.SYSTEM, declined,
                "Workflow declined: " + declined.getFullName() + " declined to sign"
                        + (StringUtils.isBlank(declined.getDeclineReason()) ? "" : " (" + declined.getDeclineReason() + ")"));
        support.publish(workflow, "WORKFLOW_DECLINED", Map.of("signatoryId", declined.getId().toString()));
        Map<String, Object> model = support.model(workflow);
        model.put("reason", declined.getFullName() + " declined to sign");
        waiting.forEach(s -> support.notifySignatory(s, NotificationEventType.WORKFLOW_CANCELLED, model));
    }

    /** IN_PROGRESS -> EXPIRED: active stages and the signatories still waiting expire with it. */
    private void expire(SigningWorkflow workflow, String reason)
    {
        for (SigningStage stage : workflow.getStages())
        {
            if (stage.getStatus() == StageStatus.ACTIVE)
            {
                stage.setStatus(StageStatus.EXPIRED);
            }
        }
        for (Signatory signatory : workflow.getSignatories())
        {
            if (WorkflowSupport.ACTIVE_SIGNATORY.contains(signatory.getStatus()))
            {
                signatory.setStatus(SignatoryStatus.EXPIRED);
            }
        }
        workflow.setStatus(WorkflowStatus.EXPIRED);
        workflow.setModifiedAt(LocalDateTime.now());
        workflow.setModifiedBy(Actor.SYSTEM.id());
        support.revokeInvitations(workflow, reason);
        support.cancelReminders(workflow);
        support.documentStatus(workflow, DocumentStatus.EXPIRED);
        support.audit(workflow, AuditEventType.WORKFLOW_EXPIRED, Actor.SYSTEM, null, reason);
        support.publish(workflow, "WORKFLOW_EXPIRED", Map.of("reason", reason));
        Map<String, Object> model = support.model(workflow);
        model.put("reason", reason);
        support.notifyOwner(workflow, NotificationEventType.WORKFLOW_EXPIRED, model);
    }

    private void expireDeadlines(SigningWorkflow workflow, LocalDateTime now)
    {
        if (workflow.getStatus() != WorkflowStatus.IN_PROGRESS)
        {
            return;
        }
        for (SigningStage stage : workflow.getStages())
        {
            if (stage.getStatus() == StageStatus.ACTIVE && stage.getDueAt() != null && stage.getDueAt().isBefore(now))
            {
                expire(workflow, "Stage " + stage.getStageNumber() + " (" + stage.getName() + ") was not completed in time");
                return;
            }
        }
        for (Signatory signatory : workflow.getSignatories())
        {
            if (WorkflowSupport.ACTIVE_SIGNATORY.contains(signatory.getStatus()) && signatory.getDeadline() != null
                    && signatory.getDeadline().isBefore(now))
            {
                withdraw(signatory, "The signatory's deadline passed");
                signatory.setStatus(SignatoryStatus.EXPIRED);
                if (!completable(signatory.getStage()))
                {
                    expire(workflow, signatory.getFullName() + " did not sign before the deadline");
                    return;
                }
                advance(workflow, signatory.getStage());
                if (workflow.getStatus() != WorkflowStatus.IN_PROGRESS)
                {
                    return;
                }
            }
        }
    }

    /** Re-applies the progression to a running workflow; nothing changes when it is consistent. */
    private void redrive(SigningWorkflow workflow)
    {
        if (workflow.getStatus() != WorkflowStatus.IN_PROGRESS)
        {
            return;
        }
        SigningStage active = workflow.getStages().stream()
                .filter(stage -> stage.getStatus() == StageStatus.ACTIVE)
                .findFirst()
                .orElse(null);
        if (active != null)
        {
            advance(workflow, active);
            return;
        }
        if (support.activateNextStage(workflow, true, Actor.SYSTEM) == null && workflow.getStages().stream()
                .noneMatch(s -> s.getStatus() == StageStatus.PENDING || s.getStatus() == StageStatus.ACTIVE))
        {
            complete(workflow);
        }
    }

    /** Withdraws the open invitations and reminders of a signatory who is no longer needed. */
    private void withdraw(Signatory signatory, String reason)
    {
        if (signatory.getInvitationSentAt() == null)
        {
            return;
        }
        support.cancelReminders(signatory);
        for (SignatoryInvitationDTO invitation : signingInvitationService.findBySignatory(signatory.getId().toString()))
        {
            if (!CLOSED_INVITATION.contains(invitation.getStatus()))
            {
                signingInvitationService.revoke(invitation.getId(), reason);
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Runs the action on the locked workflow in a transaction of its own, so one broken workflow does not
     * stop a scheduled run.
     *
     * @return 1 when the action changed the workflow's state, else 0
     */
    private int inOwnTransaction(UUID id, Consumer<SigningWorkflow> action)
    {
        try
        {
            Integer changed = newTransaction.execute(status -> {
                SigningWorkflow workflow = lock(id.toString());
                String before = state(workflow);
                action.accept(workflow);
                signingWorkflowRepository.save(workflow);
                return before.equals(state(workflow)) ? 0 : 1;
            });
            return changed == null ? 0 : changed;
        }
        catch (RuntimeException e)
        {
            logger.error("Scheduled processing of workflow {} failed", id, e);
            return 0;
        }
    }

    /** The statuses of a workflow, its stages and signatories, to tell whether anything moved. */
    private static String state(SigningWorkflow workflow)
    {
        return workflow.getStatus() + "|"
                + workflow.getStages().stream().map(s -> s.getId() + ":" + s.getStatus()).sorted()
                        .collect(Collectors.joining(","))
                + "|"
                + workflow.getSignatories().stream().map(s -> s.getId() + ":" + s.getStatus()).sorted()
                        .collect(Collectors.joining(","));
    }

    private boolean alreadyProcessed(String eventId)
    {
        return StringUtils.isNotBlank(eventId) && outboxService.isProcessed(eventId, CONSUMER);
    }

    private void processed(String eventId, String outcome)
    {
        if (StringUtils.isNotBlank(eventId))
        {
            outboxService.markProcessed(eventId, CONSUMER, outcome);
        }
    }

    private Signatory loadSignatory(String signatoryId)
    {
        return signatoryRepository.findById(uuid(signatoryId, "signatory"))
                .orElseThrow(() -> WorkflowEngineServiceException.notFound("The signatory does not exist"));
    }

    /** The workflow with its row locked, so concurrent events of one workflow are applied one at a time. */
    private SigningWorkflow lock(String workflowId)
    {
        return signingWorkflowRepository.findForUpdate(uuid(workflowId, "workflow"))
                .orElseThrow(() -> WorkflowEngineServiceException.notFound("The signing workflow does not exist"));
    }

    private static UUID uuid(String id, String what)
    {
        try
        {
            return UUID.fromString(id.trim());
        }
        catch (IllegalArgumentException | NullPointerException e)
        {
            throw WorkflowEngineServiceException.notFound("'" + id + "' is not the id of a " + what);
        }
    }

    // ---------------------------------------------------------------- transaction boundaries

    /*
     * The generated base class declares the public operations; @Transactional on this class does not reach
     * methods it inherits, so each operation is redeclared here. The scheduled operations open a transaction
     * per workflow themselves.
     */

    @Override
    @Transactional
    public SigningStageDTO activateNextStage(final String workflowId)
    {
        return super.activateNextStage(workflowId);
    }

    @Override
    @Transactional
    public boolean evaluateStage(final String stageId)
    {
        return super.evaluateStage(stageId);
    }

    @Override
    @Transactional
    public void onSignatorySigned(final String signatoryId, final String eventId)
    {
        super.onSignatorySigned(signatoryId, eventId);
    }

    @Override
    @Transactional
    public void onSignatoryDeclined(final String signatoryId, final String eventId)
    {
        super.onSignatoryDeclined(signatoryId, eventId);
    }

    @Override
    @Transactional
    public SigningWorkflowDTO finalise(final String workflowId)
    {
        return super.finalise(workflowId);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int expireOverdue()
    {
        return super.expireOverdue();
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int recoverStalled()
    {
        return super.recoverStalled();
    }
}
