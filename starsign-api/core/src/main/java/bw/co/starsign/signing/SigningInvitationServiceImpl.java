// license-header java merge-point
/**
 * This is only generated once! It will never be overwritten.
 * You can (and have to!) safely modify it by hand.
 * TEMPLATE:    SpringServiceImpl.vsl in andromda-spring cartridge
 * MODEL CLASS: AndroMDAModel::backend::bw.co.starsign::signing::SigningInvitationService
 * STEREOTYPE:  Service
 */
package bw.co.starsign.signing;

import bw.co.starsign.audit.AuthenticationMethod;
import bw.co.starsign.messaging.OutboxService;
import bw.co.starsign.audit.AuditTrailService;
import bw.co.starsign.notification.NotificationChannel;
import bw.co.starsign.notification.NotificationEventType;
import bw.co.starsign.notification.NotificationRequestDTO;
import bw.co.starsign.notification.NotificationService;
import bw.co.starsign.organisation.OrganisationSigningPolicy;
import bw.co.starsign.workflow.Signatory;
import bw.co.starsign.workflow.SignatoryMapper;
import bw.co.starsign.workflow.SignatoryRepository;
import bw.co.starsign.workflow.SignatoryStatus;
import bw.co.starsign.workflow.SignatoryType;
import bw.co.starsign.workflow.SigningWorkflow;
import bw.co.starsign.workflow.WorkflowStatus;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * @see bw.co.starsign.signing.SigningInvitationService
 */
@Service("signingInvitationService")
@Transactional(propagation = Propagation.REQUIRED, readOnly = false)
public class SigningInvitationServiceImpl
    extends SigningInvitationServiceBase
{
    /** Used when neither the workflow nor the organisation policy sets a validity. */
    private static final int DEFAULT_VALIDITY_HOURS = 72;

    private final SigningSessions signingSessions;
    private final SigningEventRecorder signingEventRecorder;
    private final SigningLinks signingLinks;

    public SigningInvitationServiceImpl(
        @Lazy NotificationService notificationService,
        @Lazy AuditTrailService auditTrailService,
        @Lazy OutboxService outboxService,
        SignatoryInvitationRepository signatoryInvitationRepository,
        SignatoryInvitationMapper signatoryInvitationMapper,
        AccessTokenRepository accessTokenRepository,
        AccessTokenMapper accessTokenMapper,
        SignatoryRepository signatoryRepository,
        SignatoryMapper signatoryMapper,
        SigningSessions signingSessions,
        SigningEventRecorder signingEventRecorder,
        SigningLinks signingLinks) {

        super(
            notificationService,
            auditTrailService,
            outboxService,
            signatoryInvitationRepository,
            signatoryInvitationMapper,
            accessTokenRepository,
            accessTokenMapper,
            signatoryRepository,
            signatoryMapper);
        this.signingSessions = signingSessions;
        this.signingEventRecorder = signingEventRecorder;
        this.signingLinks = signingLinks;
    }

    /**
     * @see bw.co.starsign.signing.SigningInvitationService#issue(String)
     */
    @Override
    protected SignatoryInvitationDTO handleIssue(String signatoryId)
        throws Exception
    {
        Signatory signatory = signatoryRepository.findById(uuid(signatoryId, "signatory"))
                .orElseThrow(() -> SigningInvitationServiceException.notFound("The signatory does not exist"));
        SigningWorkflow workflow = signatory.getWorkflow();
        if (workflow.getStatus() != WorkflowStatus.IN_PROGRESS)
        {
            throw new SigningInvitationServiceException("Signing links can only be sent while the signing request is in progress");
        }
        if (signatory.getStatus() == SignatoryStatus.SIGNED || signatory.getStatus() == SignatoryStatus.DECLINED
                || signatory.getStatus() == SignatoryStatus.CANCELLED || signatory.getStatus() == SignatoryStatus.SKIPPED)
        {
            throw new SigningInvitationServiceException(signatory.getFullName() + " no longer needs a signing link");
        }

        // Only the newest link of a signatory works
        for (SignatoryInvitation open : signatoryInvitationRepository.findBySignatory_IdAndStatusIn(
                signatory.getId(), SigningSessions.OPEN))
        {
            revokeOne(open, "Replaced by a new signing link");
        }

        LocalDateTime now = LocalDateTime.now();
        String token = SigningTokens.newToken();
        NotificationChannel channel = !signatory.isNotifyByEmail() && signatory.isNotifyBySms()
                && StringUtils.isNotBlank(signatory.getPhoneNumber())
                ? NotificationChannel.SMS
                : NotificationChannel.EMAIL;

        SignatoryInvitation invitation = SignatoryInvitation.Factory.newInstance();
        invitation.setTokenHash(SigningTokens.sha256(token));
        invitation.setStatus(InvitationStatus.CREATED);
        invitation.setIssuedAt(now);
        invitation.setExpiresAt(expiry(signatory, now));
        invitation.setSingleUse(workflow.isSingleUseInvitations());
        invitation.setAccessCount(0);
        invitation.setDeliveryChannel(channel);
        invitation.setSignatory(signatory);
        invitation.setOrganisation(workflow.getOrganisation());
        invitation.setCreatedAt(now);
        invitation.setCreatedBy("system");
        invitation = signatoryInvitationRepository.save(invitation);

        if (signatory.getStatus() == SignatoryStatus.PENDING || signatory.getStatus() == SignatoryStatus.EXPIRED)
        {
            signatory.setStatus(SignatoryStatus.INVITED);
        }
        signatory.setInvitationSentAt(now);

        String link = signatory.getSignatoryType() == SignatoryType.INTERNAL_USER && signatory.getInternalUser() != null
                ? signingLinks.internalSigningLink(workflow.getId().toString(), signatory.getId().toString())
                : signingLinks.invitationLink(token);
        if (signatory.isNotifyByEmail() || channel == NotificationChannel.EMAIL)
        {
            notify(invitation, NotificationChannel.EMAIL, signatory.getEmailAddress(), link);
        }
        if (signatory.isNotifyBySms() && StringUtils.isNotBlank(signatory.getPhoneNumber()))
        {
            notify(invitation, NotificationChannel.SMS, signatory.getPhoneNumber(), link);
        }
        // The notification is queued in this transaction; delivery receipts move it on to DELIVERED
        invitation.setStatus(InvitationStatus.SENT);
        invitation.setSentAt(now);

        signingEventRecorder.record(SigningEventType.INVITATION_ISSUED, workflow, signatory,
                SigningEventRecorder.Actor.system(), "invitation:" + invitation.getId(),
                "Signing link sent by " + channel + ", valid until " + invitation.getExpiresAt(), null, null);
        return toDTO(invitation);
    }

    /**
     * @see bw.co.starsign.signing.SigningInvitationService#resolve(String, String, String)
     */
    @Override
    protected SigningSessionDTO handleResolve(String token, String ipAddress, String userAgent)
        throws Exception
    {
        // Callers outside a signing request pass the client explicitly
        try (SigningClient bound = SigningClient.current().ipAddress() == null && ipAddress != null
                ? SigningClient.bind(ipAddress, userAgent, null)
                : null)
        {
            SignatoryInvitation invitation = signingSessions.find(token)
                    .orElseThrow(() -> SigningInvitationServiceException.notFound(
                            "This signing link is not valid. Check that you used the whole link from your invitation."));
            String refusal = signingSessions.refusal(invitation, SigningSessions.Purpose.VIEW);
            if (refusal == null && invitation.getMaxAccessCount() != null
                    && invitation.getAccessCount() >= invitation.getMaxAccessCount())
            {
                refusal = "This signing link has been opened too many times. Ask the sender for a new one.";
                signingEventRecorder.recordDenied(invitation.getId(), refusal);
            }
            if (refusal != null)
            {
                throw new SigningInvitationServiceException(refusal);
            }

            LocalDateTime now = LocalDateTime.now();
            SigningClient client = SigningClient.current();
            Signatory signatory = invitation.getSignatory();
            invitation.setAccessCount(invitation.getAccessCount() + 1);
            invitation.setLastAccessIp(client.ipAddress());
            invitation.setLastAccessUserAgent(client.userAgent());
            if (invitation.getOpenedAt() == null)
            {
                invitation.setOpenedAt(now);
                if (SigningSessions.OPEN.contains(invitation.getStatus()))
                {
                    invitation.setStatus(InvitationStatus.OPENED);
                }
                signingEventRecorder.record(SigningEventType.INVITATION_OPENED, signatory.getWorkflow(), signatory,
                        SigningEventRecorder.Actor.signatory(signatory, AuthenticationMethod.INVITATION_TOKEN),
                        "opened:" + invitation.getId(), null, null, null);
            }
            if (signatory.getFirstAccessedAt() == null)
            {
                signatory.setFirstAccessedAt(now);
            }
            signatory.setLastAccessedAt(now);
            if (signatory.getStatus() == SignatoryStatus.INVITED)
            {
                signatory.setStatus(SignatoryStatus.VIEWED);
            }
            return signingSessions.build(signatory, invitation, signingSessions.verified(invitation));
        }
    }

    /**
     * @see bw.co.starsign.signing.SigningInvitationService#findBySignatory(String)
     */
    @Override
    protected List<SignatoryInvitationDTO> handleFindBySignatory(String signatoryId)
        throws Exception
    {
        List<SignatoryInvitationDTO> invitations = new ArrayList<>();
        for (SignatoryInvitation invitation : signatoryInvitationRepository.findBySignatory_IdOrderByIssuedAtDesc(
                uuid(signatoryId, "signatory")))
        {
            invitations.add(toDTO(invitation));
        }
        return invitations;
    }

    /**
     * @see bw.co.starsign.signing.SigningInvitationService#revoke(String, String)
     */
    @Override
    protected void handleRevoke(String invitationId, String reason)
        throws Exception
    {
        SignatoryInvitation invitation = load(invitationId);
        if (invitation.getStatus() == InvitationStatus.REVOKED)
        {
            return;
        }
        if (invitation.getStatus() == InvitationStatus.CONSUMED)
        {
            throw new SigningInvitationServiceException("The signing link has already been used and cannot be withdrawn");
        }
        revokeOne(invitation, StringUtils.defaultIfBlank(reason, "Withdrawn by the sender"));
    }

    /**
     * @see bw.co.starsign.signing.SigningInvitationService#revokeForWorkflow(String, String)
     */
    @Override
    protected int handleRevokeForWorkflow(String workflowId, String reason)
        throws Exception
    {
        int revoked = 0;
        for (SignatoryInvitation invitation : signatoryInvitationRepository.findBySignatory_Workflow_IdAndStatusIn(
                uuid(workflowId, "signing request"), SigningSessions.OPEN))
        {
            revokeOne(invitation, StringUtils.defaultIfBlank(reason, "The signing request was closed"));
            revoked++;
        }
        return revoked;
    }

    /**
     * @see bw.co.starsign.signing.SigningInvitationService#markDelivered(String, LocalDateTime)
     */
    @Override
    protected void handleMarkDelivered(String invitationId, LocalDateTime deliveredAt)
        throws Exception
    {
        SignatoryInvitation invitation = load(invitationId);
        LocalDateTime at = deliveredAt == null ? LocalDateTime.now() : deliveredAt;
        if (invitation.getDeliveredAt() != null)
        {
            return;
        }
        invitation.setDeliveredAt(at);
        // A link already opened stays OPENED: receipts may arrive late
        if (invitation.getStatus() == InvitationStatus.CREATED || invitation.getStatus() == InvitationStatus.SENT)
        {
            invitation.setStatus(InvitationStatus.DELIVERED);
        }
        Signatory signatory = invitation.getSignatory();
        if (signatory.getInvitationDeliveredAt() == null)
        {
            signatory.setInvitationDeliveredAt(at);
        }
        signingEventRecorder.record(SigningEventType.INVITATION_DELIVERED, signatory.getWorkflow(), signatory,
                SigningEventRecorder.Actor.system(), "delivered:" + invitation.getId(), null, null, null);
    }

    /**
     * @see bw.co.starsign.signing.SigningInvitationService#expireOverdue()
     */
    @Override
    protected int handleExpireOverdue()
        throws Exception
    {
        LocalDateTime now = LocalDateTime.now();
        int expired = 0;
        for (SignatoryInvitation invitation : signatoryInvitationRepository.findByStatusInAndExpiresAtBefore(
                SigningSessions.OPEN, now))
        {
            invitation.setStatus(InvitationStatus.EXPIRED);
            invitation.setModifiedAt(now);
            invitation.setModifiedBy("system");
            revokeSessions(invitation, now);
            Signatory signatory = invitation.getSignatory();
            signingEventRecorder.record(SigningEventType.INVITATION_EXPIRED, signatory.getWorkflow(), signatory,
                    SigningEventRecorder.Actor.system(), "expired:" + invitation.getId(), null, null, null);
            expired++;
        }
        return expired;
    }

    private void revokeOne(SignatoryInvitation invitation, String reason)
    {
        LocalDateTime now = LocalDateTime.now();
        invitation.setStatus(InvitationStatus.REVOKED);
        invitation.setRevokedAt(now);
        invitation.setRevocationReason(StringUtils.abbreviate(reason, 255));
        invitation.setModifiedAt(now);
        invitation.setModifiedBy("system");
        revokeSessions(invitation, now);
        Signatory signatory = invitation.getSignatory();
        signingEventRecorder.record(SigningEventType.INVITATION_REVOKED, signatory.getWorkflow(), signatory,
                SigningEventRecorder.Actor.system(), "revoked:" + invitation.getId(), reason, null, null);
    }

    /** Signing sessions opened with a link end with it. */
    private void revokeSessions(SignatoryInvitation invitation, LocalDateTime now)
    {
        for (AccessToken session : accessTokenRepository.findByInvitation_IdAndRevokedAtIsNull(invitation.getId()))
        {
            session.setRevokedAt(now);
        }
    }

    private void notify(SignatoryInvitation invitation, NotificationChannel channel, String address, String link)
    {
        Signatory signatory = invitation.getSignatory();
        SigningWorkflow workflow = signatory.getWorkflow();
        Map<String, Object> model = new HashMap<>();
        model.put("signingUrl", link);
        // The templates (NotificationTemplates and organisation copies) write the link as {{link}}
        model.put("link", link);
        model.put("signatoryName", signatory.getFullName());
        model.put("workflowTitle", workflow.getTitle());
        model.put("workflowReference", workflow.getReferenceNumber());
        model.put("documentTitle", workflow.getDocument() == null ? null : workflow.getDocument().getTitle());
        model.put("organisationName", workflow.getOrganisation() == null ? null : workflow.getOrganisation().getName());
        model.put("message", workflow.getMessage());
        model.put("expiresAt", invitation.getExpiresAt() == null ? null : invitation.getExpiresAt().toString());
        model.put("deadline", signatory.getDeadline() == null ? null : signatory.getDeadline().toString());

        NotificationRequestDTO request = new NotificationRequestDTO();
        request.setOrganisationId(workflow.getOrganisation() == null ? null : workflow.getOrganisation().getId().toString());
        request.setEventType(NotificationEventType.SIGNING_INVITATION);
        request.setChannel(channel);
        request.setRecipientName(signatory.getFullName());
        request.setRecipientAddress(address);
        request.setRecipientUserId(signatory.getInternalUser() == null ? null : signatory.getInternalUser().getKeycloakUserId());
        request.setSignatoryId(signatory.getId().toString());
        request.setWorkflowId(workflow.getId().toString());
        request.setTemplateModel(model);
        request.setDeduplicationKey("signing-invitation:" + invitation.getId() + ":" + channel);
        request.setLocale(signatory.getLocale());
        notificationService.enqueue(request);
    }

    /** Workflow validity, else the organisation's, capped by the workflow expiry and the signatory's deadline. */
    private static LocalDateTime expiry(Signatory signatory, LocalDateTime now)
    {
        SigningWorkflow workflow = signatory.getWorkflow();
        OrganisationSigningPolicy policy = SigningDocuments.policy(workflow);
        int hours = workflow.getInvitationValidityHours() > 0 ? workflow.getInvitationValidityHours()
                : policy != null && policy.getInvitationValidityHours() > 0 ? policy.getInvitationValidityHours()
                : DEFAULT_VALIDITY_HOURS;
        LocalDateTime expiry = now.plusHours(hours);
        if (workflow.getExpiresAt() != null && workflow.getExpiresAt().isAfter(now) && workflow.getExpiresAt().isBefore(expiry))
        {
            expiry = workflow.getExpiresAt();
        }
        if (signatory.getDeadline() != null && signatory.getDeadline().isAfter(now) && signatory.getDeadline().isBefore(expiry))
        {
            expiry = signatory.getDeadline();
        }
        return expiry;
    }

    private static SignatoryInvitationDTO toDTO(SignatoryInvitation invitation)
    {
        SignatoryInvitationDTO dto = new SignatoryInvitationDTO();
        dto.setId(invitation.getId() == null ? null : invitation.getId().toString());
        dto.setSignatoryId(invitation.getSignatory().getId().toString());
        dto.setStatus(invitation.getStatus());
        dto.setIssuedAt(invitation.getIssuedAt());
        dto.setExpiresAt(invitation.getExpiresAt());
        dto.setSentAt(invitation.getSentAt());
        dto.setOpenedAt(invitation.getOpenedAt());
        dto.setConsumedAt(invitation.getConsumedAt());
        dto.setRevokedAt(invitation.getRevokedAt());
        dto.setAccessCount(invitation.getAccessCount());
        dto.setDeliveryChannel(invitation.getDeliveryChannel());
        return dto;
    }

    private SignatoryInvitation load(String id)
    {
        return signatoryInvitationRepository.findById(uuid(id, "signing link"))
                .orElseThrow(() -> SigningInvitationServiceException.notFound("The signing link does not exist"));
    }

    private static UUID uuid(String id, String what)
    {
        try
        {
            return UUID.fromString(id.trim());
        }
        catch (IllegalArgumentException e)
        {
            throw SigningInvitationServiceException.notFound("'" + id + "' is not the id of a " + what);
        }
    }

}
