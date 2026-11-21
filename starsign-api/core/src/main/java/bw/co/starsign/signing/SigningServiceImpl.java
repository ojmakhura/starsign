// license-header java merge-point
/**
 * This is only generated once! It will never be overwritten.
 * You can (and have to!) safely modify it by hand.
 * TEMPLATE:    SpringServiceImpl.vsl in andromda-spring cartridge
 * MODEL CLASS: AndroMDAModel::backend::bw.co.starsign::signing::SigningService
 * STEREOTYPE:  Service
 */
package bw.co.starsign.signing;

import bw.co.starsign.audit.ActorType;
import bw.co.starsign.audit.AuditTrailService;
import bw.co.starsign.audit.AuthenticationMethod;
import bw.co.starsign.branding.BrandingService;
import bw.co.starsign.context.CallerContext;
import bw.co.starsign.document.DocumentArtifact;
import bw.co.starsign.document.DocumentArtifactDTO;
import bw.co.starsign.document.DocumentArtifactRepository;
import bw.co.starsign.document.DocumentPage;
import bw.co.starsign.document.DocumentVersion;
import bw.co.starsign.identity.OrganisationUser;
import bw.co.starsign.messaging.OutboxService;
import bw.co.starsign.storage.DocumentStorageReference;
import bw.co.starsign.storage.DocumentStorageReferenceDTO;
import bw.co.starsign.storage.DocumentStorageReferenceRepository;
import bw.co.starsign.storage.StorageNamespace;
import bw.co.starsign.storage.StorageService;
import bw.co.starsign.storage.StorageWriteRequestDTO;
import bw.co.starsign.workflow.FieldType;
import bw.co.starsign.workflow.Signatory;
import bw.co.starsign.workflow.SignatoryMapper;
import bw.co.starsign.workflow.SignatoryRepository;
import bw.co.starsign.workflow.SignatoryStatus;
import bw.co.starsign.workflow.SigningField;
import bw.co.starsign.workflow.SigningFieldMapper;
import bw.co.starsign.workflow.SigningFieldRepository;
import bw.co.starsign.workflow.SigningWorkflow;
import bw.co.starsign.workflow.SigningWorkflowRepository;
import bw.co.starsign.workflow.StageStatus;
import bw.co.starsign.workflow.WorkflowEngineService;
import bw.co.starsign.workflow.WorkflowStatus;
import jakarta.validation.Valid;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.imageio.ImageIO;
import org.apache.commons.lang3.StringUtils;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * @see bw.co.starsign.signing.SigningService
 */
@Service("signingService")
@Transactional(propagation = Propagation.REQUIRED, readOnly = false)
public class SigningServiceImpl
    extends SigningServiceBase
{
    private static final int MAX_SIGNATURE_IMAGE_BYTES = 1024 * 1024;
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final int PREVIEW_DPI = 110;
    private static final int MAX_DECLINE_REASON = 1000;

    private final CallerContext caller;
    private final SigningSessions signingSessions;
    private final SigningEventRecorder signingEventRecorder;
    private final SigningWorkflowRepository signingWorkflowRepository;
    private final DocumentArtifactRepository documentArtifactRepository;
    private final DocumentStorageReferenceRepository documentStorageReferenceRepository;

    public SigningServiceImpl(
        @Lazy SigningInvitationService signingInvitationService,
        @Lazy WorkflowEngineService workflowEngineService,
        @Lazy PdfSigningEngine pdfSigningEngine,
        @Lazy StorageService storageService,
        @Lazy AuditTrailService auditTrailService,
        @Lazy OutboxService outboxService,
        @Lazy BrandingService brandingService,
        SignatoryRepository signatoryRepository,
        SignatoryMapper signatoryMapper,
        SigningFieldRepository signingFieldRepository,
        SigningFieldMapper signingFieldMapper,
        SigningFieldValueRepository signingFieldValueRepository,
        SigningFieldValueMapper signingFieldValueMapper,
        SigningEventRepository signingEventRepository,
        SigningEventMapper signingEventMapper,
        SignatureEvidenceRepository signatureEvidenceRepository,
        SignatureEvidenceMapper signatureEvidenceMapper,
        SignatoryInvitationRepository signatoryInvitationRepository,
        SignatoryInvitationMapper signatoryInvitationMapper,
        AccessTokenRepository accessTokenRepository,
        AccessTokenMapper accessTokenMapper,
        CallerContext caller,
        SigningSessions signingSessions,
        SigningEventRecorder signingEventRecorder,
        SigningWorkflowRepository signingWorkflowRepository,
        DocumentArtifactRepository documentArtifactRepository,
        DocumentStorageReferenceRepository documentStorageReferenceRepository) {

        super(
            signingInvitationService,
            workflowEngineService,
            pdfSigningEngine,
            storageService,
            auditTrailService,
            outboxService,
            brandingService,
            signatoryRepository,
            signatoryMapper,
            signingFieldRepository,
            signingFieldMapper,
            signingFieldValueRepository,
            signingFieldValueMapper,
            signingEventRepository,
            signingEventMapper,
            signatureEvidenceRepository,
            signatureEvidenceMapper,
            signatoryInvitationRepository,
            signatoryInvitationMapper,
            accessTokenRepository,
            accessTokenMapper);
        this.caller = caller;
        this.signingSessions = signingSessions;
        this.signingEventRecorder = signingEventRecorder;
        this.signingWorkflowRepository = signingWorkflowRepository;
        this.documentArtifactRepository = documentArtifactRepository;
        this.documentStorageReferenceRepository = documentStorageReferenceRepository;
    }

    /**
     * The signatory and how they were authenticated, for the signing link and the internal path alike.
     *
     * @param invitation the signing link; null on the internal path
     */
    private record Signer(Signatory signatory, SignatoryInvitation invitation, VerificationMethod verificationMethod,
        boolean verified, SigningEventRecorder.Actor actor)
    {
    }

    /**
     * @see bw.co.starsign.signing.SigningService#getSession(String)
     */
    @Override
    protected SigningSessionDTO handleGetSession(String token)
        throws Exception
    {
        SigningClient client = SigningClient.current();
        try
        {
            return signingInvitationService.resolve(token, client.ipAddress(), client.userAgent());
        }
        catch (SigningInvitationServiceException e)
        {
            // Same words and status for the signatory, whichever service checked the link
            throw e.isNotFound() ? SigningServiceException.notFound(e.getMessage()) : new SigningServiceException(e.getMessage());
        }
    }

    /**
     * @see bw.co.starsign.signing.SigningService#getDocument(String)
     */
    @Override
    protected Resource handleGetDocument(String token)
        throws Exception
    {
        return document(viewer(token));
    }

    /**
     * @see bw.co.starsign.signing.SigningService#getInternalDocument(String, String)
     */
    @Override
    protected Resource handleGetInternalDocument(String workflowId, String signatoryId)
        throws Exception
    {
        return document(internal(workflowId, signatoryId, false));
    }

    /** The current PDF of the signatory's workflow; viewing it moves an INVITED signatory to VIEWED. */
    private Resource document(Signer signer)
        throws Exception
    {
        Signatory signatory = signer.signatory();
        SigningWorkflow workflow = signatory.getWorkflow();
        DocumentArtifact pdf = SigningDocuments.pdfArtifact(SigningDocuments.currentVersion(workflow));
        if (pdf == null)
        {
            throw SigningServiceException.notFound("The document is not available yet");
        }
        byte[] bytes = read(pdf.getStorageReference().getObjectId());

        if (signatory.getStatus() == SignatoryStatus.INVITED)
        {
            signatory.setStatus(SignatoryStatus.VIEWED);
        }
        signatory.setLastAccessedAt(LocalDateTime.now());
        signingEventRecorder.record(SigningEventType.DOCUMENT_VIEWED, workflow, signatory, signer.actor(), null,
                "Viewed version with SHA-256 " + pdf.getSha256(), pdf.getSha256(), null);
        return named(bytes, fileName(workflow, null));
    }

    /**
     * @see bw.co.starsign.signing.SigningService#getPagePreview(String, int)
     */
    @Override
    protected Resource handleGetPagePreview(String token, int pageNumber)
        throws Exception
    {
        return pagePreview(viewer(token), pageNumber);
    }

    /**
     * @see bw.co.starsign.signing.SigningService#getInternalPagePreview(String, String, int)
     */
    @Override
    protected Resource handleGetInternalPagePreview(String workflowId, String signatoryId, int pageNumber)
        throws Exception
    {
        return pagePreview(internal(workflowId, signatoryId, false), pageNumber);
    }

    /** A page of the signatory's current document: the stored preview, or rendered from the PDF. */
    private Resource pagePreview(Signer signer, int pageNumber)
        throws Exception
    {
        SigningWorkflow workflow = signer.signatory().getWorkflow();
        DocumentVersion version = SigningDocuments.currentVersion(workflow);
        if (version == null || pageNumber < 1 || pageNumber > version.getPageCount())
        {
            throw SigningServiceException.notFound("The document has no page " + pageNumber);
        }
        Optional<DocumentPage> page = version.getPages().stream()
                .filter(candidate -> candidate.getPageNumber() == pageNumber)
                .findFirst();
        if (page.isPresent() && page.get().getPreviewArtifact() != null
                && page.get().getPreviewArtifact().getStorageReference() != null)
        {
            DocumentArtifact preview = page.get().getPreviewArtifact();
            return named(read(preview.getStorageReference().getObjectId()), "page-" + pageNumber + ".png");
        }

        // Versions produced by signing have no stored previews: render the page of the current PDF
        DocumentArtifact pdf = SigningDocuments.pdfArtifact(version);
        if (pdf == null)
        {
            throw SigningServiceException.notFound("The document is not available yet");
        }
        try (PDDocument document = Loader.loadPDF(read(pdf.getStorageReference().getObjectId())))
        {
            if (pageNumber > document.getNumberOfPages())
            {
                throw SigningServiceException.notFound("The document has no page " + pageNumber);
            }
            BufferedImage image = new PDFRenderer(document).renderImageWithDPI(pageNumber - 1, PREVIEW_DPI, ImageType.RGB);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(image, "png", png);
            return named(png.toByteArray(), "page-" + pageNumber + ".png");
        }
    }

    /**
     * @see bw.co.starsign.signing.SigningService#saveFieldValues(String, @Valid List<SigningFieldValueDTO>)
     */
    @Override
    protected List<SigningFieldValueDTO> handleSaveFieldValues(String token, @Valid List<SigningFieldValueDTO> values)
        throws Exception
    {
        Signer signer = actor(lockedInvitation(token));
        Signatory signatory = signer.signatory();
        String refusal = SigningSessions.actingRefusal(signatory);
        if (refusal != null)
        {
            throw new SigningServiceException(refusal);
        }
        Map<UUID, SigningField> assigned = new LinkedHashMap<>();
        SigningDocuments.fieldsOf(signatory).forEach(field -> assigned.put(field.getId(), field));

        List<String> errors = new ArrayList<>();
        List<FieldInput> inputs = new ArrayList<>();
        for (SigningFieldValueDTO value : values == null ? List.<SigningFieldValueDTO>of() : values)
        {
            SigningField field = assignedField(value, assigned, errors);
            if (field == null)
            {
                continue;
            }
            if (SigningDocuments.isSignature(field))
            {
                errors.add("Signatures and initials are captured when you sign");
                continue;
            }
            if (field.isReadOnly())
            {
                errors.add(label(field) + " cannot be changed");
                continue;
            }
            FieldInput input = input(field, value.getValueText(), value.getValueBoolean(), value.getValueDate(), errors);
            if (input != null)
            {
                // A draft may be incomplete: required fields are checked when signing
                checkFormat(input, errors);
                inputs.add(input);
            }
        }
        if (!errors.isEmpty())
        {
            throw new SigningServiceException(String.join("; ", errors));
        }

        LocalDateTime now = LocalDateTime.now();
        for (FieldInput input : inputs)
        {
            store(signatory, input, SigningSessions.DRAFT_KEY + signatory.getId(), now);
        }
        return toValueDTOs(signingFieldValueRepository.findBySignatory_Id(signatory.getId()));
    }

    /**
     * @see bw.co.starsign.signing.SigningService#sign(String, @Valid SignatureSubmissionDTO)
     */
    @Override
    protected SigningResultDTO handleSign(String token, @Valid SignatureSubmissionDTO submission)
        throws Exception
    {
        SignatoryInvitation invitation = lockedInvitation(token);
        // A retried submission gets the first outcome, even once a single-use link is spent
        if (retried(invitation.getSignatory(), "sign:", submission.getIdempotencyKey()))
        {
            return result(invitation.getSignatory(), "Your signature has already been recorded.");
        }
        return sign(actor(invitation), submission);
    }

    /**
     * @see bw.co.starsign.signing.SigningService#decline(String, @Valid DeclineRequestDTO)
     */
    @Override
    protected SigningResultDTO handleDecline(String token, @Valid DeclineRequestDTO request)
        throws Exception
    {
        SignatoryInvitation invitation = lockedInvitation(token);
        if (retried(invitation.getSignatory(), "decline:", request == null ? null : request.getIdempotencyKey()))
        {
            return result(invitation.getSignatory(), "You have declined to sign this document.");
        }
        return decline(actor(invitation), request);
    }

    /**
     * @see bw.co.starsign.signing.SigningService#downloadCompletedCopy(String)
     */
    @Override
    protected Resource handleDownloadCompletedCopy(String token)
        throws Exception
    {
        SignatoryInvitation invitation = invitation(token);
        String refusal = signingSessions.refusal(invitation, SigningSessions.Purpose.DOWNLOAD);
        if (refusal != null)
        {
            throw new SigningServiceException(refusal);
        }
        Signatory signatory = invitation.getSignatory();
        SigningWorkflow workflow = signatory.getWorkflow();
        if (workflow.getStatus() != WorkflowStatus.COMPLETED || workflow.getFinalArtifact() == null)
        {
            throw new SigningServiceException("The document is not complete yet. You will be notified when it is.");
        }
        if (!signatory.isReceiveCompletedCopy())
        {
            throw new SigningServiceException("The sender has not shared the completed document with you.");
        }
        DocumentArtifact finalArtifact = workflow.getFinalArtifact();
        byte[] bytes = read(finalArtifact.getStorageReference().getObjectId());
        signingEventRecorder.audit(bw.co.starsign.audit.AuditEventType.DOCUMENT_DOWNLOADED, workflow, signatory,
                SigningEventRecorder.Actor.signatory(signatory, AuthenticationMethod.INVITATION_TOKEN),
                "Completed copy downloaded by the signatory", bw.co.starsign.audit.AuditOutcome.SUCCESS,
                finalArtifact.getId().toString());
        return named(bytes, fileName(workflow, "signed"));
    }

    /**
     * @see bw.co.starsign.signing.SigningService#getPendingForCurrentUser()
     */
    @Override
    protected List<PendingSignatureDTO> handleGetPendingForCurrentUser()
        throws Exception
    {
        List<PendingSignatureDTO> pending = new ArrayList<>();
        for (Signatory signatory : signatoryInvitationRepository.findPendingSignatories(caller.userId(),
                EnumSet.of(SignatoryStatus.INVITED, SignatoryStatus.VIEWED, SignatoryStatus.VERIFIED),
                WorkflowStatus.IN_PROGRESS, StageStatus.ACTIVE, caller.tenantId().orElse(null)))
        {
            SigningWorkflow workflow = signatory.getWorkflow();
            PendingSignatureDTO dto = new PendingSignatureDTO();
            dto.setWorkflowId(workflow.getId().toString());
            dto.setSignatoryId(signatory.getId().toString());
            dto.setWorkflowTitle(workflow.getTitle());
            dto.setDocumentTitle(workflow.getDocument() == null ? null : workflow.getDocument().getTitle());
            dto.setRequestedBy(displayName(workflow.getOwner(), workflow.getInitiatedBy()));
            dto.setRequestedAt(workflow.getInitiatedAt() != null ? workflow.getInitiatedAt() : signatory.getInvitationSentAt());
            dto.setDeadline(signatory.getDeadline() != null ? signatory.getDeadline() : workflow.getExpiresAt());
            dto.setFieldCount(SigningDocuments.fieldsOf(signatory).size());
            pending.add(dto);
        }
        return pending;
    }

    /**
     * @see bw.co.starsign.signing.SigningService#getInternalSession(String, String)
     */
    @Override
    protected SigningSessionDTO handleGetInternalSession(String workflowId, String signatoryId)
        throws Exception
    {
        Signer signer = internal(workflowId, signatoryId, false);
        Signatory signatory = signer.signatory();
        LocalDateTime now = LocalDateTime.now();
        if (signatory.getFirstAccessedAt() == null)
        {
            signatory.setFirstAccessedAt(now);
        }
        signatory.setLastAccessedAt(now);
        if (signatory.getStatus() == SignatoryStatus.INVITED)
        {
            signatory.setStatus(SignatoryStatus.VIEWED);
        }
        return signingSessions.build(signatory, null, true);
    }

    /**
     * @see bw.co.starsign.signing.SigningService#signAsInternalUser(String, String, @Valid SignatureSubmissionDTO)
     */
    @Override
    protected SigningResultDTO handleSignAsInternalUser(String workflowId, String signatoryId, @Valid SignatureSubmissionDTO submission)
        throws Exception
    {
        return sign(internal(workflowId, signatoryId, true), submission);
    }

    /**
     * @see bw.co.starsign.signing.SigningService#declineAsInternalUser(String, String, @Valid DeclineRequestDTO)
     */
    @Override
    protected SigningResultDTO handleDeclineAsInternalUser(String workflowId, String signatoryId, @Valid DeclineRequestDTO request)
        throws Exception
    {
        return decline(internal(workflowId, signatoryId, true), request);
    }

    /**
     * @see bw.co.starsign.signing.SigningService#getEvidence(String)
     */
    @Override
    protected List<SignatureEvidenceDTO> handleGetEvidence(String workflowId)
        throws Exception
    {
        SigningWorkflow workflow = signingWorkflowRepository.findById(uuid(workflowId, "signing request"))
                .orElseThrow(() -> SigningServiceException.notFound("The signing request does not exist"));
        checkTenant(workflow);
        if (caller.ownRecordsOnly("workflows:view") && !ownsOrSigns(workflow))
        {
            throw SigningServiceException.notFound("The signing request does not exist");
        }
        List<SignatureEvidenceDTO> evidence = new ArrayList<>();
        workflow.getSignatories().stream()
                .filter(signatory -> signatory.getEvidence() != null)
                .sorted(Comparator.comparingInt(Signatory::getSigningOrder))
                .forEach(signatory -> evidence.add(toEvidenceDTO(signatory.getEvidence(), signatory)));
        return evidence;
    }

    // ---------------------------------------------------------------- signing and declining

    private SigningResultDTO sign(Signer signer, SignatureSubmissionDTO submission)
    {
        Signatory signatory = signer.signatory();
        SigningWorkflow workflow = signatory.getWorkflow();
        String key = StringUtils.trimToNull(submission.getIdempotencyKey());
        String eventKey = "sign:" + signatory.getId() + ":" + (key == null ? UUID.randomUUID() : key);

        // A retried submission gets the outcome of the first one
        if (signingEventRepository.findByIdempotencyKey(eventKey).isPresent())
        {
            return result(signatory, "Your signature has already been recorded.");
        }
        String refusal = SigningSessions.actingRefusal(signatory);
        if (refusal != null)
        {
            throw new SigningServiceException(refusal);
        }

        List<String> errors = new ArrayList<>();
        String documentSha256 = SigningDocuments.currentSha256(workflow);
        if (!Boolean.TRUE.equals(submission.getConsentAccepted()))
        {
            errors.add("Accept the electronic signature disclosure");
        }
        if (!Boolean.TRUE.equals(submission.getIntentConfirmed()))
        {
            errors.add("Confirm that you intend to sign this document");
        }
        if (StringUtils.isBlank(submission.getDocumentSha256Viewed()))
        {
            errors.add("Say which version of the document you viewed");
        }
        else if (documentSha256 == null || !documentSha256.equalsIgnoreCase(submission.getDocumentSha256Viewed().trim()))
        {
            errors.add("The document has changed since you opened it: reload it and sign again");
        }

        SignatureCaptureMethod captureMethod = submission.getCaptureMethod();
        byte[] image = null;
        String typedName = StringUtils.trimToNull(submission.getTypedName());
        if (captureMethod == null)
        {
            errors.add("Say how you captured your signature");
        }
        else if (!SigningDocuments.allowedCaptureMethods(workflow).contains(captureMethod))
        {
            errors.add("This signing request does not accept " + captureMethod.name().toLowerCase().replace('_', ' ')
                    + " signatures");
        }
        else if (captureMethod == SignatureCaptureMethod.TYPED)
        {
            if (typedName == null)
            {
                errors.add("Type your name to sign");
            }
            else if (typedName.length() > 200)
            {
                errors.add("The typed name can have at most 200 characters");
            }
        }
        else
        {
            image = signatureImage(submission.getSignatureImage(), errors);
        }

        List<FieldInput> inputs = fieldInputs(signatory, submission.getFieldValues(), errors);
        if (!errors.isEmpty())
        {
            throw new SigningServiceException(String.join("; ", errors));
        }

        LocalDateTime now = LocalDateTime.now();
        String signatureSha256 = image != null ? SigningTokens.sha256(image) : SigningTokens.sha256("typed:" + typedName);

        // The image is stored before anything refers to it
        DocumentStorageReference imageReference = null;
        if (image != null)
        {
            StorageWriteRequestDTO write = new StorageWriteRequestDTO();
            write.setOrganisationId(workflow.getOrganisation().getId().toString());
            write.setNamespace(StorageNamespace.SIGNATURES);
            write.setContentType("image/png");
            write.setContentLength((long) image.length);
            write.setOriginalFileName("signature-" + signatory.getId() + ".png");
            write.setExpectedSha256(signatureSha256);
            DocumentStorageReferenceDTO stored = storageService.store(write, new ByteArrayInputStream(image));
            imageReference = documentStorageReferenceRepository.findByObjectId(stored.getObjectId()).orElse(null);
        }

        String submissionKey = key == null ? eventKey : key;
        List<String> valueHashes = new ArrayList<>();
        for (FieldInput input : inputs)
        {
            if (SigningDocuments.isSignature(input.field()))
            {
                input = new FieldInput(input.field(), image != null ? "signature:" + signatureSha256 : typedName, null, null);
            }
            valueHashes.add(store(signatory, input, submissionKey, now).getValueSha256());
        }
        valueHashes.sort(Comparator.naturalOrder());
        String fieldValuesSha256 = SigningTokens.sha256(String.join("\n", valueHashes));

        SigningEvent event = signingEventRecorder.record(SigningEventType.SIGNATURE_SUBMITTED, workflow, signatory,
                signer.actor(), eventKey, "Signed with a " + captureMethod.name().toLowerCase().replace('_', ' ')
                        + " signature", documentSha256, null);

        SignatureEvidence evidence = SignatureEvidence.Factory.newInstance();
        evidence.setCaptureMethod(captureMethod);
        evidence.setConsentText(SigningDocuments.consentText(workflow));
        evidence.setConsentAcceptedAt(now);
        evidence.setIntentConfirmedAt(now);
        evidence.setTypedName(captureMethod == SignatureCaptureMethod.TYPED ? typedName : null);
        evidence.setSignatureFont(captureMethod == SignatureCaptureMethod.TYPED
                ? StringUtils.abbreviate(StringUtils.trimToNull(submission.getSignatureFont()), 100)
                : null);
        evidence.setSignatureImageSha256(signatureSha256);
        evidence.setDocumentSha256AtSigning(documentSha256);
        evidence.setFieldValuesSha256(fieldValuesSha256);
        // A visual signature is an electronic signature, never a digital one (see SignatureAssuranceLevel)
        evidence.setAssuranceLevel(SigningSessions.assurance(signer.verificationMethod(), signer.verified()));
        evidence.setVerificationMethod(signer.verificationMethod());
        evidence.setVerificationReference(signer.invitation() == null
                ? "user:" + signer.actor().id()
                : "invitation:" + signer.invitation().getId());
        evidence.setIpAddress(SigningClient.current().ipAddress());
        evidence.setUserAgent(SigningClient.current().userAgent());
        evidence.setTimeZone(StringUtils.abbreviate(StringUtils.trimToNull(submission.getTimeZone()), 64));
        evidence.setSignerEmail(signatory.getEmailAddress());
        evidence.setSignerName(signatory.getFullName());
        evidence.setRevocationChecked(false);
        evidence.setDigitalSignatureStatus(DigitalSignatureStatus.NOT_APPLIED);
        evidence.setSigningEvent(event);
        evidence.setSignatureImage(imageReference);
        evidence.setEvidenceSha256(SigningTokens.sha256(String.join("|",
                signatory.getId().toString(), signatory.getEmailAddress(), signatory.getFullName(),
                captureMethod.name(), evidence.getConsentText(), now.toString(), documentSha256, fieldValuesSha256,
                signatureSha256, evidence.getAssuranceLevel().name(), signer.verificationMethod().name(),
                StringUtils.defaultString(evidence.getIpAddress()), StringUtils.defaultString(evidence.getUserAgent()),
                StringUtils.defaultString(evidence.getTimeZone()), event.getId().toString())));
        evidence.setCreatedAt(now);
        evidence.setCreatedBy(signer.actor().id());
        evidence = signatureEvidenceRepository.save(evidence);

        signatory.setEvidence(evidence);
        signatory.setStatus(SignatoryStatus.SIGNED);
        signatory.setSignedAt(now);
        signatory.setModifiedAt(now);
        signatory.setModifiedBy(signer.actor().id());
        signatoryRepository.saveAndFlush(signatory);

        // Stamp the visible signature and values: a new version of the document
        DocumentArtifactDTO signed = pdfSigningEngine.renderFields(workflow.getId().toString(), signatory.getId().toString());
        if (signed != null && signed.getId() != null)
        {
            documentArtifactRepository.findById(UUID.fromString(signed.getId())).ifPresent(evidence::setSignedArtifact);
            evidence.setSignedArtifactSha256(signed.getSha256());
            event.setDocumentSha256After(signed.getSha256());
        }

        consume(signer.invitation(), now);
        workflowEngineService.onSignatorySigned(signatory.getId().toString(), event.getId().toString());
        return result(signatory, workflow.getStatus() == WorkflowStatus.COMPLETED
                ? "Thank you, your signature has been recorded. The document is now complete."
                : "Thank you, your signature has been recorded.");
    }

    private SigningResultDTO decline(Signer signer, DeclineRequestDTO request)
    {
        Signatory signatory = signer.signatory();
        SigningWorkflow workflow = signatory.getWorkflow();
        String key = request == null ? null : StringUtils.trimToNull(request.getIdempotencyKey());
        String eventKey = "decline:" + signatory.getId() + ":" + (key == null ? UUID.randomUUID() : key);
        if (signingEventRepository.findByIdempotencyKey(eventKey).isPresent())
        {
            return result(signatory, "You have declined to sign this document.");
        }
        String refusal = SigningSessions.actingRefusal(signatory);
        if (refusal != null)
        {
            throw new SigningServiceException(refusal);
        }
        if (!SigningDocuments.canDecline(signatory))
        {
            throw new SigningServiceException("The sender does not allow you to decline this signing request.");
        }
        String reason = request == null ? null : StringUtils.trimToNull(request.getReason());
        if (reason == null && workflow.isRequireDeclineReason())
        {
            throw new SigningServiceException("Say why you decline to sign");
        }
        if (reason != null && reason.length() > MAX_DECLINE_REASON)
        {
            throw new SigningServiceException("The reason can have at most " + MAX_DECLINE_REASON + " characters");
        }

        LocalDateTime now = LocalDateTime.now();
        signatory.setStatus(SignatoryStatus.DECLINED);
        signatory.setDeclinedAt(now);
        signatory.setDeclineReason(reason);
        signatory.setModifiedAt(now);
        signatory.setModifiedBy(signer.actor().id());
        SigningEvent event = signingEventRecorder.record(SigningEventType.SIGNATURE_DECLINED, workflow, signatory,
                signer.actor(), eventKey, reason == null ? "Declined without a reason" : "Declined: " + reason,
                SigningDocuments.currentSha256(workflow), null);

        consume(signer.invitation(), now);
        // The engine applies the workflow's decline rule (terminate or carry on)
        workflowEngineService.onSignatoryDeclined(signatory.getId().toString(), event.getId().toString());
        return result(signatory, "You have declined to sign this document. The sender has been told.");
    }

    /** A used link: closed for good when single-use, along with its signing sessions. */
    private void consume(SignatoryInvitation invitation, LocalDateTime now)
    {
        if (invitation == null)
        {
            return;
        }
        invitation.setConsumedAt(now);
        invitation.setStatus(InvitationStatus.CONSUMED);
        invitation.setModifiedAt(now);
        invitation.setModifiedBy(invitation.getSignatory().getId().toString());
        if (invitation.isSingleUse())
        {
            accessTokenRepository.findByInvitation_IdAndRevokedAtIsNull(invitation.getId())
                    .forEach(session -> session.setRevokedAt(now));
        }
    }

    private SigningResultDTO result(Signatory signatory, String message)
    {
        SigningWorkflow workflow = signatory.getWorkflow();
        SigningResultDTO result = new SigningResultDTO();
        result.setSignatoryStatus(signatory.getStatus());
        result.setWorkflowStatus(workflow.getStatus());
        result.setSignedAt(signatory.getSignedAt() != null ? signatory.getSignedAt() : signatory.getDeclinedAt());
        result.setDocumentSha256(SigningDocuments.currentSha256(workflow));
        result.setEvidenceSha256(signatory.getEvidence() == null ? null : signatory.getEvidence().getEvidenceSha256());
        result.setMessage(message);
        result.setCompletedCopyAvailable(workflow.getStatus() == WorkflowStatus.COMPLETED
                && workflow.getFinalArtifact() != null && signatory.isReceiveCompletedCopy());
        return result;
    }

    // ---------------------------------------------------------------- who is signing

    /** A signatory who may view the document through the link (verified when their method requires it). */
    private Signer viewer(String token)
    {
        SignatoryInvitation invitation = invitation(token);
        String refusal = signingSessions.refusal(invitation, SigningSessions.Purpose.VIEW);
        if (refusal != null)
        {
            throw new SigningServiceException(refusal);
        }
        return linkSigner(invitation);
    }

    /** The link's invitation, with its signatory's row locked so that concurrent submissions queue up. */
    private SignatoryInvitation lockedInvitation(String token)
    {
        SignatoryInvitation invitation = invitation(token);
        // Locked before the signatory's state is first read
        signingEventRepository.lockSignatory(invitation.getSignatory().getId());
        return invitation;
    }

    /** A signatory who may sign, decline or fill fields through the link. */
    private Signer actor(SignatoryInvitation invitation)
    {
        String refusal = signingSessions.refusal(invitation, SigningSessions.Purpose.ACT);
        if (refusal != null)
        {
            throw new SigningServiceException(refusal);
        }
        return linkSigner(invitation);
    }

    /** Whether a submission with this idempotency key was already recorded for the signatory. */
    private boolean retried(Signatory signatory, String prefix, String idempotencyKey)
    {
        String key = StringUtils.trimToNull(idempotencyKey);
        return key != null && signingEventRepository.findByIdempotencyKey(prefix + signatory.getId() + ":" + key).isPresent();
    }

    private Signer linkSigner(SignatoryInvitation invitation)
    {
        Signatory signatory = invitation.getSignatory();
        VerificationMethod method = SigningDocuments.verificationMethod(signatory);
        boolean verified = signingSessions.verified(invitation);
        if (!verified)
        {
            throw new SigningServiceException(method == VerificationMethod.INTERNAL_ACCOUNT
                    ? "Sign in to StarSign with your account to sign this document."
                    : "Verify your identity before you continue.");
        }
        AuthenticationMethod authentication = switch (method)
        {
            case EMAIL_OTP -> AuthenticationMethod.INVITATION_TOKEN_EMAIL_OTP;
            case SMS_OTP -> AuthenticationMethod.INVITATION_TOKEN_SMS_OTP;
            case EXTERNAL_IDENTITY_PROVIDER -> AuthenticationMethod.EXTERNAL_IDENTITY_PROVIDER;
            default -> AuthenticationMethod.INVITATION_TOKEN;
        };
        return new Signer(signatory, invitation, method, true,
                SigningEventRecorder.Actor.signatory(signatory, authentication));
    }

    private SignatoryInvitation invitation(String token)
    {
        return signingSessions.find(token)
                .orElseThrow(() -> SigningServiceException.notFound(
                        "This signing link is not valid. Check that you used the whole link from your invitation."));
    }

    /** An internal signatory: the signed-in user must be the signatory's linked account. */
    private Signer internal(String workflowId, String signatoryId, boolean lock)
    {
        UUID id = uuid(signatoryId, "signatory");
        Optional<Signatory> found = lock ? signingEventRepository.lockSignatory(id) : signatoryRepository.findById(id);
        Signatory signatory = found
                .filter(candidate -> candidate.getWorkflow().getId().equals(uuid(workflowId, "signing request")))
                .filter(candidate -> candidate.getInternalUser() != null
                        && caller.userId().equals(candidate.getInternalUser().getKeycloakUserId()))
                // Not telling other users whether the request exists
                .orElseThrow(() -> SigningServiceException.notFound("The signing request does not exist"));
        checkTenant(signatory.getWorkflow());
        SigningEventRecorder.Actor actor = new SigningEventRecorder.Actor(ActorType.ORGANISATION_USER, caller.userId(),
                signatory.getFullName(), AuthenticationMethod.KEYCLOAK_PASSWORD);
        return new Signer(signatory, null, VerificationMethod.INTERNAL_ACCOUNT, true, actor);
    }

    private void checkTenant(SigningWorkflow workflow)
    {
        Optional<String> tenant = caller.tenantId();
        if (tenant.isPresent() && (workflow.getOrganisation() == null
                || !tenant.get().equals(workflow.getOrganisation().getTenantId())))
        {
            throw SigningServiceException.notFound("The signing request does not exist");
        }
    }

    private boolean ownsOrSigns(SigningWorkflow workflow)
    {
        String user = caller.userId();
        if (workflow.getOwner() != null && user.equals(workflow.getOwner().getKeycloakUserId()))
        {
            return true;
        }
        return workflow.getSignatories().stream()
                .anyMatch(signatory -> signatory.getInternalUser() != null
                        && user.equals(signatory.getInternalUser().getKeycloakUserId()));
    }

    // ---------------------------------------------------------------- field values

    /** A field value as entered, normalised to its field type. */
    private record FieldInput(SigningField field, String text, Boolean bool, LocalDate date)
    {
    }

    /**
     * The values to record when signing: every field of the signatory, from the submission, the saved draft
     * or the field's default; required ones must be filled. Signature fields are filled with the signature.
     */
    private List<FieldInput> fieldInputs(Signatory signatory, List<SigningFieldValueDTO> submitted, List<String> errors)
    {
        Map<UUID, SigningField> assigned = new LinkedHashMap<>();
        SigningDocuments.fieldsOf(signatory).forEach(field -> assigned.put(field.getId(), field));
        Map<UUID, SigningFieldValueDTO> values = new HashMap<>();
        for (SigningFieldValueDTO value : submitted == null ? List.<SigningFieldValueDTO>of() : submitted)
        {
            SigningField field = assignedField(value, assigned, errors);
            if (field != null)
            {
                values.put(field.getId(), value);
            }
        }
        Map<UUID, SigningFieldValue> drafts = new HashMap<>();
        signingFieldValueRepository.findBySignatory_Id(signatory.getId())
                .forEach(draft -> drafts.put(draft.getField().getId(), draft));

        List<FieldInput> inputs = new ArrayList<>();
        for (SigningField field : assigned.values())
        {
            if (SigningDocuments.isSignature(field))
            {
                inputs.add(new FieldInput(field, null, null, null));
                continue;
            }
            FieldInput input;
            SigningFieldValueDTO value = values.get(field.getId());
            SigningFieldValue draft = drafts.get(field.getId());
            if (field.isReadOnly())
            {
                input = input(field, field.getDefaultValue(), null, null, errors);
            }
            else if (value != null)
            {
                input = input(field, value.getValueText(), value.getValueBoolean(), value.getValueDate(), errors);
            }
            else if (draft != null)
            {
                input = new FieldInput(field, draft.getValueText(), draft.isValueBoolean(), draft.getValueDate());
            }
            else
            {
                input = input(field, defaultValue(field, signatory), null, null, errors);
            }
            if (input == null)
            {
                continue;
            }
            checkFormat(input, errors);
            if (field.isRequired() && isEmpty(input))
            {
                errors.add(field.getFieldType() == FieldType.CHECKBOX
                        ? "Tick " + label(field)
                        : "Fill in " + label(field));
                continue;
            }
            if (!isEmpty(input))
            {
                inputs.add(input);
            }
        }
        return inputs;
    }

    private static SigningField assignedField(SigningFieldValueDTO value, Map<UUID, SigningField> assigned, List<String> errors)
    {
        if (value == null || StringUtils.isBlank(value.getFieldId()))
        {
            errors.add("Say which field each value is for");
            return null;
        }
        SigningField field = null;
        try
        {
            field = assigned.get(UUID.fromString(value.getFieldId().trim()));
        }
        catch (IllegalArgumentException e)
        {
            // Reported below like any field that is not the signatory's
        }
        if (field == null)
        {
            errors.add("The field " + value.getFieldId() + " is not one of your fields");
        }
        return field;
    }

    /** What a field the signatory left empty is filled with: the signing date, their name, organisation... */
    private static String defaultValue(SigningField field, Signatory signatory)
    {
        String fallback = switch (field.getFieldType())
        {
            case DATE -> LocalDate.now().toString();
            case FULL_NAME -> signatory.getFullName();
            case ORGANISATION -> signatory.getOrganisationName();
            case JOB_TITLE -> signatory.getDesignation();
            default -> null;
        };
        return StringUtils.defaultIfBlank(field.getDefaultValue(), fallback);
    }

    /** Normalises a value to the field type; null (with an error) when it does not fit. */
    private static FieldInput input(SigningField field, String text, Boolean bool, LocalDate date, List<String> errors)
    {
        switch (field.getFieldType())
        {
            case CHECKBOX:
                boolean ticked = bool != null ? bool : "true".equalsIgnoreCase(StringUtils.trim(text));
                return new FieldInput(field, null, ticked, null);
            case DATE:
                if (date == null && StringUtils.isNotBlank(text))
                {
                    try
                    {
                        date = LocalDate.parse(text.trim());
                    }
                    catch (DateTimeParseException e)
                    {
                        errors.add(label(field) + " must be a date (YYYY-MM-DD)");
                        return null;
                    }
                }
                return new FieldInput(field, date == null ? null : date.toString(), null, date);
            default:
                return new FieldInput(field, StringUtils.trimToNull(text), null, null);
        }
    }

    private static boolean isEmpty(FieldInput input)
    {
        return switch (input.field().getFieldType())
        {
            case CHECKBOX -> !Boolean.TRUE.equals(input.bool());
            case DATE -> input.date() == null;
            default -> input.text() == null;
        };
    }

    private static void checkFormat(FieldInput input, List<String> errors)
    {
        SigningField field = input.field();
        String text = input.text();
        if (text == null || field.getFieldType() == FieldType.DATE || field.getFieldType() == FieldType.CHECKBOX)
        {
            return;
        }
        if (field.getMaxLength() != null && text.length() > field.getMaxLength())
        {
            errors.add(label(field) + " can have at most " + field.getMaxLength() + " characters");
        }
        if (field.getFieldType() == FieldType.RADIO)
        {
            List<String> options = SigningDocuments.options(field.getOptionsJson());
            if (!options.isEmpty() && !options.contains(text))
            {
                errors.add("Choose one of the options of " + label(field));
            }
        }
        if (StringUtils.isNotBlank(field.getValidationRegex()))
        {
            try
            {
                if (!Pattern.compile(field.getValidationRegex()).matcher(text).matches())
                {
                    errors.add(label(field) + " is not in the expected format");
                }
            }
            catch (PatternSyntaxException e)
            {
                errors.add(label(field) + " has an invalid format rule; ask the sender to correct it");
            }
        }
    }

    /** Creates or updates the signatory's value of a field. */
    private SigningFieldValue store(Signatory signatory, FieldInput input, String submissionKey, LocalDateTime now)
    {
        SigningField field = input.field();
        SigningFieldValue value = signingFieldValueRepository.findBySignatory_IdAndField_Id(signatory.getId(), field.getId())
                .orElseGet(() -> {
                    SigningFieldValue created = SigningFieldValue.Factory.newInstance();
                    created.setField(field);
                    created.setSignatory(signatory);
                    created.setCreatedAt(now);
                    created.setCreatedBy(signatory.getId().toString());
                    return created;
                });
        value.setValueText(input.text());
        value.setValueBoolean(Boolean.TRUE.equals(input.bool()));
        value.setValueDate(input.date());
        value.setCompletedAt(now);
        value.setSubmissionKey(submissionKey);
        String canonical = field.getFieldType() == FieldType.CHECKBOX ? String.valueOf(Boolean.TRUE.equals(input.bool()))
                : StringUtils.defaultString(input.text());
        value.setValueSha256(SigningTokens.sha256(signatory.getId() + "|" + field.getId() + "|" + canonical));
        if (value.getId() != null)
        {
            value.setModifiedAt(now);
            value.setModifiedBy(signatory.getId().toString());
        }
        if (!submissionKey.startsWith(SigningSessions.DRAFT_KEY) && field.getAssignment() != null)
        {
            field.getAssignment().setCompleted(true);
            field.getAssignment().setCompletedAt(now);
        }
        return signingFieldValueRepository.save(value);
    }

    private static List<SigningFieldValueDTO> toValueDTOs(List<SigningFieldValue> values)
    {
        List<SigningFieldValueDTO> result = new ArrayList<>();
        for (SigningFieldValue value : values)
        {
            SigningFieldValueDTO dto = new SigningFieldValueDTO();
            dto.setFieldId(value.getField().getId().toString());
            dto.setValueText(SigningDocuments.isSignature(value.getField()) ? null : value.getValueText());
            dto.setValueBoolean(value.isValueBoolean());
            dto.setValueDate(value.getValueDate());
            result.add(dto);
        }
        return result;
    }

    /** The drawn or uploaded signature: a base64 PNG (data URL accepted) of at most 1 MB. */
    private static byte[] signatureImage(String data, List<String> errors)
    {
        if (StringUtils.isBlank(data))
        {
            errors.add("Draw or upload your signature");
            return null;
        }
        String base64 = data.trim();
        if (base64.startsWith("data:"))
        {
            if (!base64.startsWith("data:image/png"))
            {
                errors.add("The signature must be a PNG image");
                return null;
            }
            base64 = StringUtils.substringAfter(base64, ",");
        }
        if (base64.length() > MAX_SIGNATURE_IMAGE_BYTES * 4 / 3 + 8)
        {
            errors.add("The signature image can be at most 1 MB");
            return null;
        }
        byte[] image;
        try
        {
            image = Base64.getMimeDecoder().decode(base64);
        }
        catch (IllegalArgumentException e)
        {
            errors.add("The signature image could not be read");
            return null;
        }
        if (image.length > MAX_SIGNATURE_IMAGE_BYTES)
        {
            errors.add("The signature image can be at most 1 MB");
            return null;
        }
        if (image.length < PNG_MAGIC.length || !java.util.Arrays.equals(java.util.Arrays.copyOf(image, PNG_MAGIC.length), PNG_MAGIC))
        {
            errors.add("The signature must be a PNG image");
            return null;
        }
        return image;
    }

    // ---------------------------------------------------------------- helpers

    private SignatureEvidenceDTO toEvidenceDTO(SignatureEvidence evidence, Signatory signatory)
    {
        SignatureEvidenceDTO dto = new SignatureEvidenceDTO();
        dto.setId(evidence.getId().toString());
        dto.setCreatedAt(evidence.getCreatedAt());
        dto.setCreatedBy(evidence.getCreatedBy());
        dto.setSignatoryId(signatory.getId().toString());
        dto.setSignerName(evidence.getSignerName());
        dto.setSignerEmail(evidence.getSignerEmail());
        dto.setCaptureMethod(evidence.getCaptureMethod());
        dto.setConsentAcceptedAt(evidence.getConsentAcceptedAt());
        dto.setIntentConfirmedAt(evidence.getIntentConfirmedAt());
        dto.setDocumentSha256AtSigning(evidence.getDocumentSha256AtSigning());
        dto.setSignedArtifactSha256(evidence.getSignedArtifactSha256());
        dto.setAssuranceLevel(evidence.getAssuranceLevel());
        dto.setVerificationMethod(evidence.getVerificationMethod());
        dto.setIpAddress(evidence.getIpAddress());
        dto.setUserAgent(evidence.getUserAgent());
        dto.setCertificateSubject(evidence.getCertificateSubject());
        dto.setCertificateIssuer(evidence.getCertificateIssuer());
        dto.setCertificateSerialNumber(evidence.getCertificateSerialNumber());
        dto.setTimestampAuthority(evidence.getTimestampAuthority());
        dto.setTimestampedAt(evidence.getTimestampedAt());
        dto.setDigitalSignatureStatus(evidence.getDigitalSignatureStatus());
        dto.setEvidenceSha256(evidence.getEvidenceSha256());
        return dto;
    }

    private byte[] read(String objectId) throws java.io.IOException
    {
        try (InputStream in = storageService.openStream(objectId))
        {
            return in.readAllBytes();
        }
    }

    private static Resource named(byte[] bytes, String fileName)
    {
        return new ByteArrayResource(bytes)
        {
            @Override
            public String getFilename()
            {
                return fileName;
            }
        };
    }

    private static String fileName(SigningWorkflow workflow, String suffix)
    {
        String title = workflow.getDocument() == null ? null : workflow.getDocument().getTitle();
        String base = StringUtils.defaultIfBlank(title, StringUtils.defaultIfBlank(workflow.getReferenceNumber(), "document"))
                .replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        base = StringUtils.removeEndIgnoreCase(StringUtils.abbreviate(base, 100), ".pdf");
        return base + (suffix == null ? "" : "-" + suffix) + ".pdf";
    }

    private static String displayName(OrganisationUser user, String fallback)
    {
        if (user == null)
        {
            return fallback;
        }
        String name = StringUtils.trimToNull(StringUtils.defaultString(user.getFirstName()) + " "
                + StringUtils.defaultString(user.getLastName()));
        return name != null ? name : StringUtils.defaultIfBlank(user.getEmailAddress(), fallback);
    }

    private static String label(SigningField field)
    {
        return StringUtils.isNotBlank(field.getLabel())
                ? "'" + field.getLabel() + "'"
                : "the " + field.getFieldType().name().toLowerCase().replace('_', ' ') + " field on page " + field.getPageNumber();
    }

    private static UUID uuid(String id, String what)
    {
        try
        {
            return UUID.fromString(id.trim());
        }
        catch (IllegalArgumentException e)
        {
            throw SigningServiceException.notFound("'" + id + "' is not the id of a " + what);
        }
    }

}
