package bw.co.starsign.signing;

import bw.co.starsign.branding.BrandingService;
import bw.co.starsign.document.DocumentPage;
import bw.co.starsign.document.DocumentPageDTO;
import bw.co.starsign.document.DocumentVersion;
import bw.co.starsign.notification.NovuNotifier;
import bw.co.starsign.workflow.Signatory;
import bw.co.starsign.workflow.SignatoryStatus;
import bw.co.starsign.workflow.SigningField;
import bw.co.starsign.workflow.SigningFieldDTO;
import bw.co.starsign.workflow.SigningWorkflow;
import bw.co.starsign.workflow.WorkflowStatus;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Access rules of the external signing link and the signing session shown to a signatory, shared by the
 * invitation, verification and signing services.
 */
@Component
public class SigningSessions
{
    /** What the caller wants to do with a signing link. */
    enum Purpose
    {
        /** Open the session, view the document and verify. */
        VIEW,
        /** Save fields, sign or decline. */
        ACT,
        /** Download the completed copy (allowed after the link was used). */
        DOWNLOAD
    }

    static final Set<InvitationStatus> OPEN = EnumSet.of(InvitationStatus.CREATED, InvitationStatus.SENT,
            InvitationStatus.DELIVERED, InvitationStatus.OPENED);

    private static final Set<SignatoryStatus> CAN_ACT = EnumSet.of(SignatoryStatus.INVITED, SignatoryStatus.VIEWED,
            SignatoryStatus.VERIFIED);

    private final SignatoryInvitationRepository signatoryInvitationRepository;
    private final AccessTokenRepository accessTokenRepository;
    private final SigningFieldValueRepository signingFieldValueRepository;
    private final SigningEventRecorder signingEventRecorder;
    private final BrandingService brandingService;
    private final NovuNotifier novuNotifier;

    public SigningSessions(
        SignatoryInvitationRepository signatoryInvitationRepository,
        AccessTokenRepository accessTokenRepository,
        SigningFieldValueRepository signingFieldValueRepository,
        SigningEventRecorder signingEventRecorder,
        @Lazy BrandingService brandingService,
        NovuNotifier novuNotifier)
    {
        this.signatoryInvitationRepository = signatoryInvitationRepository;
        this.accessTokenRepository = accessTokenRepository;
        this.signingFieldValueRepository = signingFieldValueRepository;
        this.signingEventRecorder = signingEventRecorder;
        this.brandingService = brandingService;
        this.novuNotifier = novuNotifier;
    }

    /** The invitation of a raw link token; only its hash is looked up. */
    Optional<SignatoryInvitation> find(String token)
    {
        if (StringUtils.isBlank(token) || token.length() > 200)
        {
            return Optional.empty();
        }
        return signatoryInvitationRepository.findByTokenHash(SigningTokens.sha256(token.trim()));
    }

    /**
     * Why the link cannot be used for the purpose, in words for the signatory; null when it can. A refusal of
     * a link that exists is recorded (ACCESS_DENIED) in its own transaction.
     */
    String refusal(SignatoryInvitation invitation, Purpose purpose)
    {
        String reason = reason(invitation, purpose);
        if (reason != null)
        {
            signingEventRecorder.recordDenied(invitation.getId(), reason);
        }
        return reason;
    }

    private static String reason(SignatoryInvitation invitation, Purpose purpose)
    {
        LocalDateTime now = LocalDateTime.now();
        SigningWorkflow workflow = invitation.getSignatory().getWorkflow();
        switch (invitation.getStatus())
        {
            case REVOKED:
                return "This signing link has been withdrawn. Ask the sender for a new one.";
            case FAILED:
                return "This signing link can no longer be used. Ask the sender for a new one.";
            case EXPIRED:
                return "This signing link has expired. Ask the sender for a new one.";
            case CONSUMED:
                if (invitation.isSingleUse() && purpose != Purpose.DOWNLOAD)
                {
                    return "This signing link has already been used.";
                }
                break;
            default:
                break;
        }
        if (purpose != Purpose.DOWNLOAD && invitation.getExpiresAt() != null && invitation.getExpiresAt().isBefore(now))
        {
            return "This signing link has expired. Ask the sender for a new one.";
        }
        if (invitation.getMaxAccessCount() != null && invitation.getAccessCount() > invitation.getMaxAccessCount())
        {
            return "This signing link has been opened too many times. Ask the sender for a new one.";
        }
        if (!SigningTokens.ipAllowed(invitation.getAllowedIpRanges(), SigningClient.current().ipAddress()))
        {
            return "This signing link cannot be used from your network.";
        }
        if (workflow.getStatus() == WorkflowStatus.CANCELLED)
        {
            return "This signing request has been cancelled by the sender.";
        }
        if (workflow.getStatus() == WorkflowStatus.EXPIRED)
        {
            return "This signing request has expired.";
        }
        if (workflow.getStatus() == WorkflowStatus.DRAFT || workflow.getStatus() == WorkflowStatus.READY)
        {
            return "This signing request has not been sent yet.";
        }
        return null;
    }

    /**
     * Why the signatory cannot sign, decline or fill fields now; null when they can. Used for the signing link
     * and for internal signatories alike.
     */
    static String actingRefusal(Signatory signatory)
    {
        SigningWorkflow workflow = signatory.getWorkflow();
        if (signatory.getStatus() == SignatoryStatus.SIGNED)
        {
            return "You have already signed this document.";
        }
        if (signatory.getStatus() == SignatoryStatus.DECLINED)
        {
            return "You have declined to sign this document.";
        }
        if (workflow.getStatus() != WorkflowStatus.IN_PROGRESS)
        {
            return switch (workflow.getStatus())
            {
                case COMPLETING, COMPLETED -> "This document has already been completed.";
                case DECLINED -> "This signing request was declined and is closed.";
                case CANCELLED -> "This signing request has been cancelled by the sender.";
                case EXPIRED -> "This signing request has expired.";
                default -> "This signing request is not open for signing.";
            };
        }
        if (signatory.getStage() != null && signatory.getStage().getStatus() != bw.co.starsign.workflow.StageStatus.ACTIVE)
        {
            return "It is not your turn to sign yet. You will be notified when it is.";
        }
        if (!CAN_ACT.contains(signatory.getStatus()))
        {
            return "You cannot sign this document any more.";
        }
        if (signatory.getDeadline() != null && signatory.getDeadline().isBefore(LocalDateTime.now()))
        {
            return "The deadline for signing this document has passed.";
        }
        return null;
    }

    /** Verification still to be passed on the signing link before acting. */
    static boolean verificationRequired(VerificationMethod method)
    {
        return method != VerificationMethod.EMAIL_LINK;
    }

    /**
     * Whether the browser has passed the signatory's verification: for one-time codes and identity providers
     * it must present the signing session cookie issued on success. The link itself proves email possession.
     */
    boolean verified(SignatoryInvitation invitation)
    {
        VerificationMethod method = SigningDocuments.verificationMethod(invitation.getSignatory());
        if (!verificationRequired(method))
        {
            return true;
        }
        if (method == VerificationMethod.INTERNAL_ACCOUNT)
        {
            return false;
        }
        String session = SigningClient.current().sessionToken();
        if (StringUtils.isBlank(session) || invitation.getSignatory().getVerifiedAt() == null)
        {
            return false;
        }
        return accessTokenRepository.findByTokenHash(SigningTokens.sha256(session.trim()))
                .filter(token -> token.getInvitation().getId().equals(invitation.getId()))
                .filter(token -> token.getRevokedAt() == null)
                .filter(token -> token.getExpiresAt() != null && token.getExpiresAt().isAfter(LocalDateTime.now()))
                .map(token -> {
                    token.setLastUsedAt(LocalDateTime.now());
                    return true;
                })
                .orElse(false);
    }

    /** A visual signature is at most ELECTRONIC_VERIFIED; digital levels come from certificates only. */
    static SignatureAssuranceLevel assurance(VerificationMethod method, boolean verified)
    {
        return verified && method != VerificationMethod.EMAIL_LINK
                ? SignatureAssuranceLevel.ELECTRONIC_VERIFIED
                : SignatureAssuranceLevel.ELECTRONIC_BASIC;
    }

    /**
     * The session of a signatory: their own fields only (rule 6).
     *
     * @param invitation the signing link, or null on the internal path
     */
    SigningSessionDTO build(Signatory signatory, SignatoryInvitation invitation, boolean verified)
    {
        SigningWorkflow workflow = signatory.getWorkflow();
        VerificationMethod method = invitation == null ? VerificationMethod.INTERNAL_ACCOUNT
                : SigningDocuments.verificationMethod(signatory);
        DocumentVersion version = SigningDocuments.currentVersion(workflow);

        SigningSessionDTO session = new SigningSessionDTO();
        session.setInvitationStatus(invitation == null ? null : invitation.getStatus());
        session.setWorkflowReference(workflow.getReferenceNumber());
        session.setWorkflowTitle(workflow.getTitle());
        session.setMessage(workflow.getMessage());
        session.setDocumentTitle(workflow.getDocument() == null ? null : workflow.getDocument().getTitle());
        session.setPageCount(version == null ? null : version.getPageCount());
        if (workflow.getOrganisation() != null)
        {
            session.setOrganisationName(workflow.getOrganisation().getName());
            session.setBranding(brandingService.getForOrganisation(workflow.getOrganisation().getId().toString()));
        }
        session.setSignatoryName(signatory.getFullName());
        session.setSignatoryEmailMasked(SigningTokens.maskEmail(signatory.getEmailAddress()));
        session.setSignatoryStatus(signatory.getStatus());
        session.setVerificationRequired(invitation != null && verificationRequired(method));
        // The method the signatory will use: an SMS code comes by email while text messages are off
        session.setVerificationMethod(SigningDocuments.deliverable(method, novuNotifier.isSmsEnabled()));
        session.setVerified(verified);
        session.setExpiresAt(invitation != null ? invitation.getExpiresAt()
                : signatory.getDeadline() != null ? signatory.getDeadline() : workflow.getExpiresAt());
        session.setCanDecline(SigningDocuments.canDecline(signatory) && actingRefusal(signatory) == null);
        session.setRequireDeclineReason(workflow.isRequireDeclineReason());
        session.setConsentText(SigningDocuments.consentText(workflow));
        session.setAllowedCaptureMethods(SigningDocuments.allowedCaptureMethods(workflow));
        session.setFields(fields(signatory));
        session.setPages(pages(workflow, version));
        session.setAlreadySigned(signatory.getStatus() == SignatoryStatus.SIGNED);
        return session;
    }

    private List<SigningFieldDTO> fields(Signatory signatory)
    {
        Map<UUID, SigningFieldValue> values = signingFieldValueRepository.findBySignatory_Id(signatory.getId()).stream()
                .collect(Collectors.toMap(value -> value.getField().getId(), Function.identity(), (a, b) -> b));
        List<SigningFieldDTO> fields = new ArrayList<>();
        for (SigningField field : SigningDocuments.fieldsOf(signatory))
        {
            fields.add(toFieldDTO(field, signatory, values.get(field.getId())));
        }
        return fields;
    }

    static SigningFieldDTO toFieldDTO(SigningField field, Signatory signatory, SigningFieldValue value)
    {
        SigningFieldDTO dto = new SigningFieldDTO();
        dto.setId(field.getId().toString());
        dto.setWorkflowId(field.getWorkflow() == null ? null : field.getWorkflow().getId().toString());
        dto.setSignatoryId(signatory.getId().toString());
        dto.setSignatoryName(signatory.getFullName());
        dto.setFieldType(field.getFieldType());
        dto.setLabel(field.getLabel());
        dto.setPageNumber(field.getPageNumber());
        dto.setXRatio(field.getXRatio());
        dto.setYRatio(field.getYRatio());
        dto.setWidthRatio(field.getWidthRatio());
        dto.setHeightRatio(field.getHeightRatio());
        dto.setPageWidthPoints(field.getPageWidthPoints());
        dto.setPageHeightPoints(field.getPageHeightPoints());
        dto.setPageRotation(field.getPageRotation());
        dto.setRequired(field.isRequired());
        dto.setReadOnly(field.isReadOnly());
        dto.setPlaceholder(field.getPlaceholder());
        // A saved draft is offered back as the field's starting value
        dto.setDefaultValue(value != null && value.getValueText() != null && !SigningDocuments.isSignature(field)
                ? value.getValueText()
                : field.getDefaultValue());
        dto.setFormatPattern(field.getFormatPattern());
        dto.setValidationRegex(field.getValidationRegex());
        dto.setMaxLength(field.getMaxLength());
        dto.setFontSize(field.getFontSize());
        dto.setRadioGroup(field.getRadioGroup());
        dto.setOptions(SigningDocuments.options(field.getOptionsJson()));
        dto.setTabOrder(field.getTabOrder());
        dto.setCompleted(field.getAssignment() != null && field.getAssignment().isCompleted()
                || value != null && value.getSubmissionKey() != null && !value.getSubmissionKey().startsWith(DRAFT_KEY));
        dto.setColour(signatory.getColour());
        return dto;
    }

    /** Submission key of draft field values (signed values carry the submission's idempotency key). */
    static final String DRAFT_KEY = "draft:";

    private static List<DocumentPageDTO> pages(SigningWorkflow workflow, DocumentVersion version)
    {
        List<DocumentPage> pages = version == null || version.getPages().isEmpty()
                ? workflow.getSourceVersion() == null ? List.of() : workflow.getSourceVersion().getPages()
                : version.getPages();
        List<DocumentPageDTO> result = new ArrayList<>();
        pages.stream().sorted(Comparator.comparingInt(DocumentPage::getPageNumber)).forEach(page -> {
            DocumentPageDTO dto = new DocumentPageDTO();
            dto.setPageNumber(page.getPageNumber());
            dto.setWidthPoints(page.getWidthPoints());
            dto.setHeightPoints(page.getHeightPoints());
            dto.setRotation(page.getRotation());
            // Pages without a stored preview are rendered on request
            dto.setPreviewAvailable(true);
            dto.setThumbnailAvailable(page.getThumbnailArtifact() != null);
            result.add(dto);
        });
        return result;
    }
}
