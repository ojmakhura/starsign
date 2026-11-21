// license-header java merge-point
/**
 * This is only generated once! It will never be overwritten.
 * You can (and have to!) safely modify it by hand.
 * TEMPLATE:    SpringServiceImpl.vsl in andromda-spring cartridge
 * MODEL CLASS: AndroMDAModel::backend::bw.co.starsign::document::DocumentIntegrityService
 * STEREOTYPE:  Service
 */
package bw.co.starsign.document;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import bw.co.starsign.audit.AuditChainVerificationDTO;
import bw.co.starsign.audit.AuditEventDTO;
import bw.co.starsign.audit.AuditEventType;
import bw.co.starsign.audit.AuditOutcome;
import bw.co.starsign.audit.AuditTrailService;
import bw.co.starsign.context.CallerContext;
import bw.co.starsign.signing.DigitalSignatureValidationDTO;
import bw.co.starsign.signing.PdfSigningEngine;
import bw.co.starsign.storage.DocumentStorageReference;
import bw.co.starsign.storage.StorageService;
import bw.co.starsign.storage.StorageStatus;

/**
 * Proves that a document's files are what was recorded: hashes recomputed from storage, version
 * provenance, the audit hash chain and the PAdES signatures of the final file.
 * <p>
 * Transactions are opened here explicitly: the generated base declares the public methods, so a
 * class-level {@code @Transactional} on this subclass would not apply to them.
 *
 * @see bw.co.starsign.document.DocumentIntegrityService
 */
@Service("documentIntegrityService")
public class DocumentIntegrityServiceImpl
    extends DocumentIntegrityServiceBase
{
    /** Page images are derived from the PDF and can be rendered again: they are not part of the proof. */
    private static final Set<ArtifactType> DERIVED = Set.of(ArtifactType.PAGE_PREVIEW, ArtifactType.THUMBNAIL);
    private static final Set<StorageStatus> GONE = Set.of(StorageStatus.DELETED, StorageStatus.PENDING_DELETION,
            StorageStatus.FAILED, StorageStatus.PENDING_UPLOAD);
    /** Worst first: the overall result is the worst one found. */
    private static final List<IntegrityResult> SEVERITY = List.of(IntegrityResult.MISSING_ARTIFACT,
            IntegrityResult.HASH_MISMATCH, IntegrityResult.STORAGE_UNAVAILABLE, IntegrityResult.AUDIT_CHAIN_BROKEN,
            IntegrityResult.DIGITAL_SIGNATURE_INVALID, IntegrityResult.VALID);
    private static final List<ArtifactType> VERIFIABLE_FILES = List.of(ArtifactType.FINAL_SIGNED,
            ArtifactType.INTERMEDIATE_SIGNED, ArtifactType.PREPARATION, ArtifactType.ORIGINAL);

    private final DocumentArtifacts artifacts;
    private final CallerContext caller;
    private final TransactionTemplate write;

    public DocumentIntegrityServiceImpl(
        @Lazy StorageService storageService,
        @Lazy AuditTrailService auditTrailService,
        @Lazy PdfSigningEngine pdfSigningEngine,
        DocumentRepository documentRepository,
        DocumentMapper documentMapper,
        DocumentArtifactRepository documentArtifactRepository,
        DocumentArtifactMapper documentArtifactMapper,
        DocumentArtifacts artifacts,
        @Lazy CallerContext caller,
        PlatformTransactionManager transactionManager) {

        super(
            storageService,
            auditTrailService,
            pdfSigningEngine,
            documentRepository,
            documentMapper,
            documentArtifactRepository,
            documentArtifactMapper);
        this.artifacts = artifacts;
        this.caller = caller;
        this.write = new TransactionTemplate(transactionManager);
    }

    /**
     * @see bw.co.starsign.document.DocumentIntegrityService#verifyDocument(String)
     */
    @Override
    protected DocumentIntegrityReportDTO handleVerifyDocument(String id)
        throws Exception
    {
        return write.execute(status -> verify(load(id)));
    }

    /**
     * Matches the file's SHA-256 against the signed, prepared and original files of the caller's
     * organisation. A file changed in any way after signing matches nothing.
     *
     * @see bw.co.starsign.document.DocumentIntegrityService#verifyFile(MultipartFile)
     */
    @Override
    protected DocumentIntegrityReportDTO handleVerifyFile(MultipartFile file)
        throws Exception
    {
        if (file.isEmpty())
        {
            throw new DocumentIntegrityServiceException("Choose the PDF file to verify");
        }
        String sha256;
        try (InputStream in = file.getInputStream())
        {
            sha256 = sha256(in);
        }

        DocumentIntegrityReportDTO report = report();
        Optional<String> tenantId = caller.tenantId();
        if (tenantId.isEmpty())
        {
            report.setOverallResult(IntegrityResult.UNKNOWN_DOCUMENT);
            report.getMessages().add("Files can only be verified against the documents of your organisation");
            return report;
        }

        return write.execute(status -> {
            Optional<DocumentArtifact> match = documentArtifactRepository
                    .findByHashInTenant(sha256, VERIFIABLE_FILES, tenantId.get()).stream()
                    .min(Comparator.comparingInt(artifact -> VERIFIABLE_FILES.indexOf(artifact.getArtifactType())));
            if (match.isEmpty())
            {
                report.setOverallResult(IntegrityResult.UNKNOWN_DOCUMENT);
                report.getMessages().add("The file does not match any document of your organisation:"
                        + " it is not a StarSign file or it was changed after it was produced");
                return report;
            }

            DocumentArtifact artifact = match.get();
            Document document = artifact.getVersion().getDocument();
            report.setDocumentId(document.getId().toString());
            report.setReferenceNumber(document.getReferenceNumber());
            report.setOriginalSha256(document.getOriginalSha256());
            report.setAssuranceLevel(artifact.getAssuranceLevel());
            report.getArtifacts().add(result(artifact, sha256, IntegrityResult.VALID));
            report.getMessages().add(switch (artifact.getArtifactType())
            {
                case FINAL_SIGNED -> "The file is the final signed copy of document " + document.getReferenceNumber();
                case INTERMEDIATE_SIGNED -> "The file is a partly signed copy of document " + document.getReferenceNumber()
                        + ": signing was not complete when it was produced";
                case PREPARATION -> "The file is an unsigned prepared copy of document " + document.getReferenceNumber();
                default -> "The file is the original, unsigned upload of document " + document.getReferenceNumber();
            });

            IntegrityResult overall = IntegrityResult.VALID;
            if (artifact.getArtifactType() == ArtifactType.FINAL_SIGNED)
            {
                report.setFinalSha256(artifact.getSha256());
                overall = signatures(report, artifact);
            }
            report.setOverallResult(overall);

            Map<String, Object> details = new LinkedHashMap<String, Object>();
            details.put("source", "uploaded file");
            details.put("artifactType", artifact.getArtifactType().getValue());
            details.put("sha256", sha256);
            details.put("result", overall.getValue());
            auditTrailService.record(artifacts.event(
                    overall == IntegrityResult.VALID ? AuditEventType.INTEGRITY_VERIFIED : AuditEventType.INTEGRITY_FAILED,
                    document, overall == IntegrityResult.VALID ? AuditOutcome.SUCCESS : AuditOutcome.FAILURE, details));
            return report;
        });
    }

    /**
     * There is no job queue, so the verification runs once the caller's transaction commits (or now, when
     * there is none). Its outcome is recorded in the audit trail; failures are logged, never thrown at the
     * caller, whose work has already been committed.
     *
     * @see bw.co.starsign.document.DocumentIntegrityService#scheduleVerification(String)
     */
    @Override
    protected void handleScheduleVerification(String id)
        throws Exception
    {
        UUID key = uuid(id);
        Runnable verification = () -> {
            try
            {
                write.executeWithoutResult(status -> verify(documentRepository.findById(key)
                        .filter(document -> document.getDeletedAt() == null)
                        .orElseThrow(() -> DocumentIntegrityServiceException.notFound("The document does not exist"))));
            }
            catch (RuntimeException e)
            {
                logger.error("Scheduled integrity verification of document {} failed", id, e);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive())
        {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization()
            {
                @Override
                public void afterCommit()
                {
                    verification.run();
                }
            });
        }
        else
        {
            verification.run();
        }
    }

    private DocumentIntegrityReportDTO verify(Document document)
    {
        DocumentIntegrityReportDTO report = report();
        report.setDocumentId(document.getId().toString());
        report.setReferenceNumber(document.getReferenceNumber());
        report.setOriginalSha256(document.getOriginalSha256());
        List<IntegrityResult> results = new ArrayList<IntegrityResult>();

        List<DocumentVersion> versions = new ArrayList<DocumentVersion>(document.getVersions());
        versions.sort(Comparator.comparingInt(DocumentVersion::getVersionNumber));
        Set<String> knownHashes = versions.stream()
                .flatMap(version -> version.getArtifacts().stream())
                .map(DocumentArtifact::getSha256)
                .collect(Collectors.toSet());

        for (DocumentVersion version : versions)
        {
            for (DocumentArtifact artifact : version.getArtifacts())
            {
                if (DERIVED.contains(artifact.getArtifactType()))
                {
                    continue;
                }
                ArtifactIntegrityDTO checked = check(artifact);
                report.getArtifacts().add(checked);
                results.add(checked.getResult());
                if (checked.getResult() != IntegrityResult.VALID)
                {
                    report.getMessages().add(describe(artifact, checked.getResult()));
                }
                if (artifact.getPreviousArtifactSha256() != null && !knownHashes.contains(artifact.getPreviousArtifactSha256()))
                {
                    results.add(IntegrityResult.HASH_MISMATCH);
                    report.getMessages().add("The " + label(artifact) + " of version " + version.getVersionNumber()
                            + " was produced from a file that is not part of the document");
                }
            }
            provenance(document, version, report, results);
        }

        Optional<DocumentArtifact> finalArtifact = versions.stream()
                .flatMap(version -> version.getArtifacts().stream())
                .filter(artifact -> artifact.getArtifactType() == ArtifactType.FINAL_SIGNED)
                .max(Comparator.comparing(DocumentArtifact::getGeneratedAt));
        if (finalArtifact.isPresent())
        {
            report.setFinalSha256(finalArtifact.get().getSha256());
            report.setAssuranceLevel(finalArtifact.get().getAssuranceLevel());
            results.add(signatures(report, finalArtifact.get()));
        }
        else if (document.getStatus() == DocumentStatus.COMPLETED)
        {
            results.add(IntegrityResult.MISSING_ARTIFACT);
            report.getMessages().add("The document is completed but its final signed file is missing");
        }

        results.add(auditChain(document, report));

        IntegrityResult overall = SEVERITY.stream().filter(results::contains).findFirst().orElse(IntegrityResult.VALID);
        report.setOverallResult(overall);
        if (overall == IntegrityResult.VALID)
        {
            report.getMessages().add("All files of the document match their recorded fingerprints");
        }

        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("result", overall.getValue());
        details.put("artifactsChecked", report.getArtifacts().size());
        details.put("messages", report.getMessages());
        auditTrailService.record(artifacts.event(
                overall == IntegrityResult.VALID ? AuditEventType.INTEGRITY_VERIFIED : AuditEventType.INTEGRITY_FAILED,
                document, overall == IntegrityResult.VALID ? AuditOutcome.SUCCESS : AuditOutcome.FAILURE, details));
        return report;
    }

    /** Recomputes the artifact's hash from storage. */
    private ArtifactIntegrityDTO check(DocumentArtifact artifact)
    {
        DocumentStorageReference reference = artifact.getStorageReference();
        if (reference == null || reference.getDeletedAt() != null || GONE.contains(reference.getStatus()))
        {
            return result(artifact, null, IntegrityResult.MISSING_ARTIFACT);
        }
        try (InputStream in = artifacts.open(artifact))
        {
            String computed = sha256(in);
            return result(artifact, computed,
                    computed.equalsIgnoreCase(artifact.getSha256()) ? IntegrityResult.VALID : IntegrityResult.HASH_MISMATCH);
        }
        catch (IOException | RuntimeException e)
        {
            logger.warn("Artifact {} of document {} could not be read for verification", artifact.getId(),
                    artifact.getVersion().getDocument().getId(), e);
            return result(artifact, null, IntegrityResult.STORAGE_UNAVAILABLE);
        }
    }

    /** A version's file is the one it recorded, and it descends from a version of the document. */
    private static void provenance(Document document, DocumentVersion version, DocumentIntegrityReportDTO report,
            List<IntegrityResult> results)
    {
        Optional<DocumentArtifact> pdf = DocumentArtifacts.pdf(version);
        if (pdf.isEmpty())
        {
            results.add(IntegrityResult.MISSING_ARTIFACT);
            report.getMessages().add("Version " + version.getVersionNumber() + " has no PDF file");
            return;
        }
        if (!pdf.get().getSha256().equalsIgnoreCase(version.getSha256()))
        {
            results.add(IntegrityResult.HASH_MISMATCH);
            report.getMessages().add("The file of version " + version.getVersionNumber()
                    + " is not the one the version recorded");
        }
        if (version.getVersionNumber() == 1 && !version.getSha256().equalsIgnoreCase(document.getOriginalSha256()))
        {
            results.add(IntegrityResult.HASH_MISMATCH);
            report.getMessages().add("The first version is not the original upload");
        }
        Integer source = version.getSourceVersionNumber();
        if (source != null && document.getVersions().stream().noneMatch(other -> other.getVersionNumber() == source))
        {
            results.add(IntegrityResult.HASH_MISMATCH);
            report.getMessages().add("Version " + version.getVersionNumber() + " was made from version " + source
                    + ", which does not exist");
        }
    }

    private IntegrityResult signatures(DocumentIntegrityReportDTO report, DocumentArtifact artifact)
    {
        DigitalSignatureValidationDTO validation = pdfSigningEngine.validateDigitalSignatures(artifact.getId().toString());
        report.setSignatureCount(validation.getSignatureCount() == null ? 0 : validation.getSignatureCount());
        if (report.getSignatureCount() == 0)
        {
            // Electronic signatures without a PAdES signature: nothing to validate
            return IntegrityResult.VALID;
        }
        boolean valid = Boolean.TRUE.equals(validation.getValid());
        report.setDigitalSignaturesValid(valid);
        if (validation.getMessages() != null)
        {
            report.getMessages().addAll(validation.getMessages());
        }
        if (!valid)
        {
            report.getMessages().add("The digital signatures of the final signed file are not valid");
        }
        return valid ? IntegrityResult.VALID : IntegrityResult.DIGITAL_SIGNATURE_INVALID;
    }

    /** Verifies every audit hash chain the document's events are in. */
    private IntegrityResult auditChain(Document document, DocumentIntegrityReportDTO report)
    {
        List<AuditEventDTO> events = auditTrailService.findByDocument(document.getId().toString());
        Set<String> chains = events.stream().map(AuditEventDTO::getChainKey).filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        boolean valid = true;
        for (String chain : chains)
        {
            AuditChainVerificationDTO verification = auditTrailService.verifyChain(chain, null, null);
            if (!Boolean.TRUE.equals(verification.getValid()))
            {
                valid = false;
                report.getMessages().add("The audit trail has been tampered with"
                        + (verification.getFirstInvalidSequence() == null ? ""
                                : " from event " + verification.getFirstInvalidSequence()));
            }
        }
        report.setAuditChainValid(valid);
        return valid ? IntegrityResult.VALID : IntegrityResult.AUDIT_CHAIN_BROKEN;
    }

    private Document load(String id)
    {
        String tenantId = caller.tenantId().orElseThrow(
                () -> new DocumentIntegrityServiceException("Only users of an organisation can verify documents"));
        return documentRepository.findById(uuid(id))
                .filter(document -> document.getDeletedAt() == null)
                .filter(document -> tenantId.equals(document.getOrganisation().getTenantId()))
                .orElseThrow(() -> DocumentIntegrityServiceException.notFound("The document does not exist"));
    }

    private static UUID uuid(String id)
    {
        try
        {
            return UUID.fromString(id.trim());
        }
        catch (IllegalArgumentException e)
        {
            throw DocumentIntegrityServiceException.notFound("'" + id + "' is not the id of a document");
        }
    }

    private static DocumentIntegrityReportDTO report()
    {
        DocumentIntegrityReportDTO report = new DocumentIntegrityReportDTO();
        report.setVerifiedAt(LocalDateTime.now());
        report.setArtifacts(new ArrayList<ArtifactIntegrityDTO>());
        report.setMessages(new ArrayList<String>());
        return report;
    }

    private static ArtifactIntegrityDTO result(DocumentArtifact artifact, String computed, IntegrityResult result)
    {
        ArtifactIntegrityDTO dto = new ArtifactIntegrityDTO();
        dto.setArtifactId(artifact.getId().toString());
        dto.setArtifactType(artifact.getArtifactType());
        dto.setVersionNumber(artifact.getVersion().getVersionNumber());
        dto.setRecordedSha256(artifact.getSha256());
        dto.setComputedSha256(computed);
        dto.setResult(result);
        return dto;
    }

    private static String describe(DocumentArtifact artifact, IntegrityResult result)
    {
        String what = "The " + label(artifact) + " of version " + artifact.getVersion().getVersionNumber();
        return switch (result)
        {
            case MISSING_ARTIFACT -> what + " is missing from storage";
            case HASH_MISMATCH -> what + " has been changed since it was stored";
            case STORAGE_UNAVAILABLE -> what + " could not be read from storage";
            default -> what + ": " + result.getValue();
        };
    }

    private static String label(DocumentArtifact artifact)
    {
        return artifact.getArtifactType().getValue().toLowerCase().replace('_', ' ') + " file";
    }

    private static String sha256(InputStream in) throws IOException
    {
        MessageDigest digest;
        try
        {
            digest = MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1)
        {
            digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
