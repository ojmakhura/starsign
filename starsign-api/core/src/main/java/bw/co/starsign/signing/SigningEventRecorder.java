package bw.co.starsign.signing;

import bw.co.starsign.audit.ActorType;
import bw.co.starsign.audit.AuditEventDTO;
import bw.co.starsign.audit.AuditEventType;
import bw.co.starsign.audit.AuditOutcome;
import bw.co.starsign.audit.AuditTrailService;
import bw.co.starsign.audit.AuthenticationMethod;
import bw.co.starsign.workflow.Signatory;
import bw.co.starsign.workflow.SigningWorkflow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the signing event log of a workflow (append-only, numbered per workflow) and the matching audit
 * trail entry, with the client address and user agent of the current {@link SigningClient}.
 */
@Component
public class SigningEventRecorder
{
    private final SigningEventRepository signingEventRepository;
    private final SignatoryInvitationRepository signatoryInvitationRepository;
    private final AuditTrailService auditTrailService;

    public SigningEventRecorder(
        SigningEventRepository signingEventRepository,
        SignatoryInvitationRepository signatoryInvitationRepository,
        @Lazy AuditTrailService auditTrailService)
    {
        this.signingEventRepository = signingEventRepository;
        this.signatoryInvitationRepository = signatoryInvitationRepository;
        this.auditTrailService = auditTrailService;
    }

    /** Who caused an event. */
    public record Actor(ActorType type, String id, String displayName, AuthenticationMethod authentication)
    {
        static Actor signatory(Signatory signatory, AuthenticationMethod authentication)
        {
            return new Actor(ActorType.SIGNATORY, signatory.getId().toString(), signatory.getFullName(), authentication);
        }

        static Actor system()
        {
            return new Actor(ActorType.SYSTEM, "system", "StarSign", AuthenticationMethod.NONE);
        }
    }

    /**
     * Records the event in the caller's transaction.
     *
     * @param idempotencyKey unique key of the event; null for a random one
     * @param details short plain-text details (never tokens or codes)
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public SigningEvent record(SigningEventType type, SigningWorkflow workflow, Signatory signatory, Actor actor,
        String idempotencyKey, String details, String documentSha256Before, String documentSha256After)
    {
        // Locking the workflow row keeps sequence numbers unique when parallel signatories act at once
        SigningWorkflow locked = signingEventRepository.lockWorkflow(workflow.getId()).orElse(workflow);
        SigningClient client = SigningClient.current();
        LocalDateTime now = LocalDateTime.now();

        SigningEvent event = SigningEvent.Factory.newInstance();
        event.setEventType(type);
        event.setOccurredAt(now);
        event.setIpAddress(client.ipAddress());
        event.setUserAgent(client.userAgent());
        event.setAuthenticationMethod(actor.authentication());
        event.setIdempotencyKey(StringUtils.defaultIfBlank(idempotencyKey, UUID.randomUUID().toString()));
        event.setDetails(jsonDetails(details));
        event.setDocumentSha256Before(documentSha256Before);
        event.setDocumentSha256After(documentSha256After);
        event.setSequenceNumber(signingEventRepository.maxSequenceNumber(locked.getId()) + 1);
        event.setWorkflow(locked);
        event.setSignatory(signatory);
        event.setStage(signatory == null ? null : signatory.getStage());
        event.setCreatedAt(now);
        event.setCreatedBy(actor.id());
        SigningEvent saved = signingEventRepository.saveAndFlush(event);

        AuditEventType auditType = auditType(type);
        if (auditType != null)
        {
            audit(auditType, locked, signatory, actor, details,
                    type == SigningEventType.ACCESS_DENIED || type == SigningEventType.VERIFICATION_FAILED
                            ? AuditOutcome.DENIED
                            : AuditOutcome.SUCCESS,
                    saved.getId().toString());
        }
        return saved;
    }

    /**
     * Records a refused access to a signing link. Runs in its own transaction because the refusal is reported
     * to the caller as an exception, which rolls back the caller's transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordDenied(UUID invitationId, String reason)
    {
        signatoryInvitationRepository.findById(invitationId).ifPresent(invitation -> {
            Signatory signatory = invitation.getSignatory();
            record(SigningEventType.ACCESS_DENIED, signatory.getWorkflow(), signatory,
                    Actor.signatory(signatory, AuthenticationMethod.INVITATION_TOKEN), null, reason, null, null);
        });
    }

    /** An audit trail entry without a signing event (e.g. document generation). */
    @Transactional(propagation = Propagation.REQUIRED)
    public void audit(AuditEventType type, SigningWorkflow workflow, Signatory signatory, Actor actor, String details,
        AuditOutcome outcome, String entityId)
    {
        SigningClient client = SigningClient.current();
        AuditEventDTO audit = new AuditEventDTO();
        audit.setEventType(type);
        audit.setOccurredAt(LocalDateTime.now());
        audit.setOrganisationId(workflow.getOrganisation() == null ? null : workflow.getOrganisation().getId().toString());
        audit.setActorType(actor.type());
        audit.setActorId(actor.id());
        audit.setActorDisplayName(actor.displayName());
        audit.setAuthenticationMethod(actor.authentication());
        audit.setIpAddress(client.ipAddress());
        audit.setUserAgent(client.userAgent());
        audit.setOutcome(outcome);
        audit.setDocumentId(workflow.getDocument() == null ? null : workflow.getDocument().getId().toString());
        audit.setWorkflowId(workflow.getId().toString());
        audit.setSignatoryId(signatory == null ? null : signatory.getId().toString());
        audit.setEntityType(signatory == null ? "SigningWorkflow" : "Signatory");
        audit.setEntityId(entityId);
        audit.setDetails(StringUtils.abbreviate(details, 2000));
        auditTrailService.record(audit);
    }

    /** An audit trail entry about a document produced by the signing engine. */
    @Transactional(propagation = Propagation.REQUIRED)
    public void auditDocument(AuditEventType type, bw.co.starsign.document.Document document, SigningWorkflow workflow,
        String details, String entityType, String entityId)
    {
        SigningClient client = SigningClient.current();
        AuditEventDTO audit = new AuditEventDTO();
        audit.setEventType(type);
        audit.setOccurredAt(LocalDateTime.now());
        audit.setOrganisationId(document.getOrganisation() == null ? null : document.getOrganisation().getId().toString());
        audit.setActorType(ActorType.SYSTEM);
        audit.setActorId("system");
        audit.setActorDisplayName("StarSign");
        audit.setAuthenticationMethod(AuthenticationMethod.NONE);
        audit.setIpAddress(client.ipAddress());
        audit.setUserAgent(client.userAgent());
        audit.setOutcome(AuditOutcome.SUCCESS);
        audit.setDocumentId(document.getId().toString());
        audit.setWorkflowId(workflow == null ? null : workflow.getId().toString());
        audit.setEntityType(entityType);
        audit.setEntityId(entityId);
        audit.setDetails(StringUtils.abbreviate(details, 2000));
        auditTrailService.record(audit);
    }

    private static AuditEventType auditType(SigningEventType type)
    {
        return switch (type)
        {
            case INVITATION_ISSUED, INVITATION_SENT -> AuditEventType.SIGNING_INVITATION_SENT;
            case INVITATION_OPENED -> AuditEventType.SIGNING_INVITATION_OPENED;
            case INVITATION_REVOKED -> AuditEventType.SIGNING_INVITATION_REVOKED;
            case ACCESS_GRANTED -> AuditEventType.SIGNING_ACCESS_GRANTED;
            case ACCESS_DENIED -> AuditEventType.SIGNING_ACCESS_DENIED;
            case DOCUMENT_VIEWED -> AuditEventType.DOCUMENT_VIEWED;
            case VERIFICATION_PASSED -> AuditEventType.SIGNATORY_VERIFIED;
            case VERIFICATION_FAILED -> AuditEventType.SIGNATORY_VERIFICATION_FAILED;
            case SIGNATURE_CAPTURED -> AuditEventType.SIGNATURE_CAPTURED;
            case SIGNATURE_SUBMITTED -> AuditEventType.SIGNATURE_SUBMITTED;
            case SIGNATURE_DECLINED -> AuditEventType.SIGNATURE_DECLINED;
            case STAGE_COMPLETED -> AuditEventType.SIGNING_STAGE_COMPLETED;
            case STAGE_ACTIVATED -> AuditEventType.SIGNING_STAGE_ACTIVATED;
            // Delivery receipts, verification starts and draft fields are kept in the signing log only
            default -> null;
        };
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * SIGNING_EVENT.DETAILS is jsonb, which rejects free text: plain-text details are stored as
     * {"text": ...} (as the audit trail does), JSON is kept as it is.
     */
    static String jsonDetails(String details)
    {
        if (StringUtils.isBlank(details))
        {
            return null;
        }
        try
        {
            JSON.readTree(details);
            return details;
        }
        catch (JsonProcessingException notJson)
        {
            return JSON.createObjectNode().put("text", StringUtils.abbreviate(details, 2000)).toString();
        }
    }

    /** The readable text of stored details: the "text" of {"text": ...}, other JSON as it is. */
    public static String detailsText(String details)
    {
        if (StringUtils.isBlank(details))
        {
            return null;
        }
        try
        {
            com.fasterxml.jackson.databind.JsonNode node = JSON.readTree(details);
            return node != null && node.isObject() && node.size() == 1 && node.path("text").isTextual()
                    ? node.get("text").asText()
                    : details;
        }
        catch (JsonProcessingException notJson)
        {
            return details;
        }
    }
}
