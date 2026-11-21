// license-header java merge-point
/**
 * This is only generated once! It will never be overwritten.
 * You can (and have to!) safely modify it by hand.
 * TEMPLATE:    SpringServiceImpl.vsl in andromda-spring cartridge
 * MODEL CLASS: AndroMDAModel::backend::bw.co.starsign::storage::StorageService
 * STEREOTYPE:  Service
 */
package bw.co.starsign.storage;

import bw.co.starsign.audit.ActorType;
import bw.co.starsign.audit.AuditEventDTO;
import bw.co.starsign.audit.AuditEventType;
import bw.co.starsign.audit.AuditOutcome;
import bw.co.starsign.audit.AuditTrailService;
import bw.co.starsign.context.CallerContext;
import bw.co.starsign.document.ArtifactType;
import bw.co.starsign.organisation.Organisation;
import bw.co.starsign.organisation.OrganisationRepository;
import jakarta.validation.Valid;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Object storage for every package: the bytes live in SeaweedFS under {@code <tenant>/<namespace>/<objectId>}
 * (see {@link StorageKeys}); a {@link DocumentStorageReference} row records where, with the SHA-256 and size.
 * Callers only ever see the object id.
 * <p>
 * Tenant isolation: a caller with a tenant only reaches its own organisation's objects. Callers without one
 * (platform administrators, background jobs, public and signing-portal requests that carry no user token) are
 * not limited here; their controllers decide. Branding files are public and readable by anyone.
 *
 * @see bw.co.starsign.storage.StorageService
 */
@Service("storageService")
@Transactional(propagation = Propagation.REQUIRED, readOnly = false)
public class StorageServiceImpl
    extends StorageServiceBase
{
    /** Length of DocumentStorageReference.failureReason (VARCHAR(255)). */
    private static final int MAX_FAILURE_REASON = 255;

    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern CONTENT_TYPE = Pattern.compile("^[\\w.+-]+/[\\w.+-]+(\\s*;.*)?$");
    private static final int MAX_FILE_NAME_LENGTH = 255;

    /** An upload session is expected to finish within this time; reconciliation expires older ones. */
    private static final long UPLOAD_SESSION_MINUTES = 60;

    /** Statuses whose object is in the bucket and may be read. */
    private static final Set<StorageStatus> READABLE = EnumSet.of(StorageStatus.STORED, StorageStatus.VERIFIED);

    /** Statuses that occupy space in the bucket. */
    private static final Set<StorageStatus> OCCUPYING = EnumSet.of(StorageStatus.STORED, StorageStatus.VERIFIED,
            StorageStatus.PENDING_DELETION, StorageStatus.ORPHANED);

    private final StorageProperties properties;
    private final OrganisationRepository organisationRepository;
    private final StorageUploadSessionRepository storageUploadSessionRepository;
    private final StorageReconciliationIssueRepository storageReconciliationIssueRepository;
    private final CallerContext caller;
    private final TransactionTemplate ownTransaction;

    /** The caller's persistence context, to re-read a reference another transaction has changed. */
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;
    private final SecureRandom random = new SecureRandom();

    public StorageServiceImpl(
        @Lazy ObjectStorageAdapter objectStorageAdapter,
        @Lazy AuditTrailService auditTrailService,
        DocumentStorageReferenceRepository documentStorageReferenceRepository,
        DocumentStorageReferenceMapper documentStorageReferenceMapper,
        StorageProperties properties,
        OrganisationRepository organisationRepository,
        StorageUploadSessionRepository storageUploadSessionRepository,
        StorageReconciliationIssueRepository storageReconciliationIssueRepository,
        CallerContext caller,
        PlatformTransactionManager transactionManager) {

        super(
            objectStorageAdapter,
            auditTrailService,
            documentStorageReferenceRepository,
            documentStorageReferenceMapper);
        this.properties = properties;
        this.organisationRepository = organisationRepository;
        this.storageUploadSessionRepository = storageUploadSessionRepository;
        this.storageReconciliationIssueRepository = storageReconciliationIssueRepository;
        this.caller = caller;
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * The reference is written in transactions of its own, so that it survives a rollback of the caller's
     * transaction (the object is in the bucket by then) and a failed upload leaves a FAILED reference for
     * reconciliation and retries.
     *
     * @see bw.co.starsign.storage.StorageService#store(StorageWriteRequestDTO, InputStream)
     */
    @Override
    protected DocumentStorageReferenceDTO handleStore(@Valid StorageWriteRequestDTO request, InputStream content)
        throws Exception
    {
        checkWriteRequest(request);
        Organisation organisation = StringUtils.isBlank(request.getOrganisationId()) ? null
                : organisationRepository.findById(uuid(request.getOrganisationId(), "organisation"))
                        .orElseThrow(() -> StorageServiceException.notFound("The organisation does not exist"));
        checkWriteAccess(organisation);

        String expectedSha256 = request.getExpectedSha256() == null ? null
                : request.getExpectedSha256().trim().toLowerCase();

        // S3 needs the size before the upload starts: content of unknown size is spooled to a temporary file
        Path spooled = null;
        try
        {
            long contentLength;
            InputStream source;
            if (request.getContentLength() == null)
            {
                spooled = Files.createTempFile("starsign-upload-", ".bin");
                try (InputStream in = content; OutputStream out = Files.newOutputStream(spooled))
                {
                    in.transferTo(out);
                }
                contentLength = Files.size(spooled);
                source = Files.newInputStream(spooled);
            }
            else
            {
                contentLength = request.getContentLength();
                source = content;
            }

            String objectId = UUID.randomUUID().toString();
            UUID referenceId = ownTransaction.execute(status ->
                    createPending(objectId, organisation, request, contentLength, expectedSha256));

            try (InputStream in = source)
            {
                return upload(referenceId, in, contentLength, expectedSha256, request.getMetadata());
            }
        }
        finally
        {
            if (spooled != null)
            {
                Files.deleteIfExists(spooled);
            }
        }
    }

    /**
     * @see bw.co.starsign.storage.StorageService#openStream(String)
     */
    @Override
    protected InputStream handleOpenStream(String objectId)
        throws Exception
    {
        DocumentStorageReference reference = loadReadable(objectId);
        return objectStorageAdapter.getObject(reference.getBucket(), reference.getObjectKey());
    }

    /**
     * A {@link StoredObjectResource}: the content with its size, content type and original file name.
     *
     * @see bw.co.starsign.storage.StorageService#asResource(String)
     */
    @Override
    protected Resource handleAsResource(String objectId)
        throws Exception
    {
        DocumentStorageReference reference = loadReadable(objectId);
        InputStream content = objectStorageAdapter.getObject(reference.getBucket(), reference.getObjectKey());
        return new StoredObjectResource(content, reference.getObjectId(), reference.getContentType(),
                reference.getContentLength(), reference.getOriginalFileName(), reference.getSha256Checksum());
    }

    /**
     * @see bw.co.starsign.storage.StorageService#findByObjectId(String)
     */
    @Override
    protected DocumentStorageReferenceDTO handleFindByObjectId(String objectId)
        throws Exception
    {
        DocumentStorageReference reference = load(objectId);
        checkReadAccess(reference);
        return toDto(reference);
    }

    /**
     * A match marks the object VERIFIED; a mismatch or a missing object is recorded as an integrity failure
     * (reconciliation reports it as an issue).
     *
     * @see bw.co.starsign.storage.StorageService#verifyChecksum(String)
     */
    @Override
    protected boolean handleVerifyChecksum(String objectId)
        throws Exception
    {
        DocumentStorageReference reference = loadReadable(objectId);

        boolean matches;
        if (objectStorageAdapter.headObject(reference.getBucket(), reference.getObjectKey()).isEmpty())
        {
            matches = false;
        }
        else
        {
            String actual = StorageKeys.sha256(objectStorageAdapter.getObject(reference.getBucket(), reference.getObjectKey()));
            matches = actual.equalsIgnoreCase(reference.getSha256Checksum());
        }

        reference.setLastVerifiedAt(LocalDateTime.now());
        if (matches)
        {
            reference.setStatus(StorageStatus.VERIFIED);
        }
        else
        {
            logger.warn("Stored object {} failed checksum verification", reference.getObjectId());
            audit(AuditEventType.INTEGRITY_FAILED, AuditOutcome.FAILURE, reference,
                    "The stored object is missing or its SHA-256 no longer matches the recorded checksum");
        }
        touch(reference);
        documentStorageReferenceRepository.save(reference);
        return matches;
    }

    /**
     * @see bw.co.starsign.storage.StorageService#copy(String, StorageNamespace)
     */
    @Override
    protected DocumentStorageReferenceDTO handleCopy(String objectId, StorageNamespace namespace)
        throws Exception
    {
        if (namespace == null)
        {
            throw new StorageServiceException("Say which namespace the copy belongs to");
        }
        DocumentStorageReference source = loadReadable(objectId);
        checkWriteAccess(source.getOrganisation());

        String copyId = UUID.randomUUID().toString();
        String copyKey = StorageKeys.objectKey(source.getOrganisation(), namespace, copyId);
        String version = objectStorageAdapter.copyObject(source.getBucket(), source.getObjectKey(), copyKey);

        LocalDateTime now = LocalDateTime.now();
        DocumentStorageReference copy = DocumentStorageReference.Factory.newInstance();
        copy.setObjectId(copyId);
        copy.setProvider(source.getProvider());
        copy.setBucket(source.getBucket());
        copy.setObjectKey(copyKey);
        copy.setObjectVersion(version);
        copy.setETag(source.getETag());
        copy.setNamespace(namespace);
        copy.setContentType(source.getContentType());
        copy.setContentLength(source.getContentLength());
        copy.setSha256Checksum(source.getSha256Checksum());
        copy.setOriginalFileName(source.getOriginalFileName());
        copy.setUploadedAt(now);
        copy.setStatus(StorageStatus.STORED);
        copy.setEncryptedAtRest(source.isEncryptedAtRest());
        copy.setOrganisation(source.getOrganisation());
        copy.setCreatedAt(now);
        copy.setCreatedBy(actorId());
        return toDto(documentStorageReferenceRepository.save(copy));
    }

    /**
     * @see bw.co.starsign.storage.StorageService#scheduleDeletion(String, LocalDateTime)
     */
    @Override
    protected void handleScheduleDeletion(String objectId, LocalDateTime deleteAfter)
        throws Exception
    {
        DocumentStorageReference reference = load(objectId);
        checkWriteAccess(reference.getOrganisation());

        switch (reference.getStatus())
        {
            case DELETED -> throw new StorageServiceException("The file has already been deleted");
            case PENDING_UPLOAD -> throw new StorageServiceException("The file is still being uploaded and cannot be deleted yet");
            default -> { }
        }
        checkRetention(reference, deleteAfter);

        reference.setStatus(StorageStatus.PENDING_DELETION);
        reference.setDeletionScheduledAt(deleteAfter);
        touch(reference);
        documentStorageReferenceRepository.save(reference);
    }

    /**
     * Deletes the objects whose deletion time has passed. A failed deletion is logged, recorded on the
     * reference and reported as a FAILED_DELETION issue; the next run tries again.
     *
     * @see bw.co.starsign.storage.StorageService#purgeScheduledDeletions()
     */
    @Override
    protected int handlePurgeScheduledDeletions()
        throws Exception
    {
        List<UUID> due = documentStorageReferenceRepository
                .findByStatusAndDeletionScheduledAtLessThanEqual(StorageStatus.PENDING_DELETION, LocalDateTime.now())
                .stream().map(DocumentStorageReference::getId).toList();

        int purged = 0;
        for (UUID id : due)
        {
            Boolean deleted = ownTransaction.execute(status -> purge(id));
            if (Boolean.TRUE.equals(deleted))
            {
                purged++;
            }
        }
        if (purged > 0)
        {
            logger.info("Purged {} stored object(s) scheduled for deletion", purged);
        }
        return purged;
    }

    /**
     * Objects and bytes in the bucket per namespace group; objects pending deletion still count.
     *
     * @see bw.co.starsign.storage.StorageService#getUsage(String)
     */
    @Override
    protected StorageUsageDTO handleGetUsage(String organisationId)
        throws Exception
    {
        Organisation organisation = organisationRepository.findById(uuid(organisationId, "organisation"))
                .orElseThrow(() -> StorageServiceException.notFound("The organisation does not exist"));
        if (!mayAccess(organisation))
        {
            throw StorageServiceException.notFound("The organisation does not exist");
        }

        Map<StorageNamespace, long[]> byNamespace = new HashMap<>();
        for (Object[] row : documentStorageReferenceRepository.usageByNamespace(organisation.getId(), OCCUPYING))
        {
            byNamespace.put((StorageNamespace) row[0],
                    new long[] { ((Number) row[1]).longValue(), ((Number) row[2]).longValue() });
        }

        StorageUsageDTO usage = new StorageUsageDTO();
        usage.setOrganisationId(organisation.getId().toString());
        usage.setObjectCount(byNamespace.values().stream().mapToLong(counts -> counts[0]).sum());
        usage.setTotalBytes(byNamespace.values().stream().mapToLong(counts -> counts[1]).sum());
        usage.setOriginalsBytes(bytes(byNamespace, StorageNamespace.ORIGINALS));
        usage.setArtifactsBytes(bytes(byNamespace, StorageNamespace.ARTIFACTS, StorageNamespace.SIGNATURES));
        usage.setPreviewBytes(bytes(byNamespace, StorageNamespace.PREVIEWS, StorageNamespace.THUMBNAILS));
        usage.setEvidenceBytes(bytes(byNamespace, StorageNamespace.EVIDENCE, StorageNamespace.AUDIT_EXPORTS));
        return usage;
    }

    // ------------------------------------------------------------------------------------------------
    // Upload steps (each in a transaction of its own)
    // ------------------------------------------------------------------------------------------------

    private UUID createPending(String objectId, Organisation organisation, StorageWriteRequestDTO request,
            long contentLength, String expectedSha256)
    {
        LocalDateTime now = LocalDateTime.now();
        String actor = actorId();

        DocumentStorageReference reference = DocumentStorageReference.Factory.newInstance();
        reference.setObjectId(objectId);
        reference.setProvider(properties.getProvider() == null ? StorageProvider.SEAWEEDFS_S3 : properties.getProvider());
        reference.setBucket(properties.getBucket());
        reference.setObjectKey(StorageKeys.objectKey(organisation, request.getNamespace(), objectId));
        reference.setNamespace(request.getNamespace());
        reference.setContentType(request.getContentType().trim());
        reference.setContentLength(contentLength);
        reference.setSha256Checksum(expectedSha256 == null ? StorageKeys.pendingChecksum(objectId) : expectedSha256);
        reference.setOriginalFileName(StringUtils.trimToNull(request.getOriginalFileName()));
        reference.setStatus(StorageStatus.PENDING_UPLOAD);
        reference.setEncryptedAtRest(false);
        reference.setOrganisation(organisation);
        reference.setCreatedAt(now);
        reference.setCreatedBy(actor);
        reference = documentStorageReferenceRepository.save(reference);

        // Sessions belong to an organisation; platform files (branding) have none
        if (organisation != null)
        {
            byte[] token = new byte[32];
            random.nextBytes(token);
            StorageUploadSession session = StorageUploadSession.Factory.newInstance();
            session.setSessionTokenHash(DigestUtils.sha256Hex(token));
            session.setStatus(UploadSessionStatus.OPEN);
            session.setExpectedContentType(reference.getContentType());
            session.setExpectedContentLength(contentLength);
            session.setExpectedSha256(expectedSha256);
            session.setReceivedBytes(0);
            session.setExpiresAt(now.plusMinutes(UPLOAD_SESSION_MINUTES));
            session.setRequestedBy(actor);
            session.setOrganisation(organisation);
            session.setStorageReference(reference);
            session.setCreatedAt(now);
            session.setCreatedBy(actor);
            storageUploadSessionRepository.save(session);
        }
        return reference.getId();
    }

    private DocumentStorageReferenceDTO upload(UUID referenceId, InputStream content, long contentLength,
            String expectedSha256, Map<?, ?> metadata) throws Exception
    {
        DocumentStorageReference pending = documentStorageReferenceRepository.findById(referenceId).orElseThrow();
        String bucket = pending.getBucket();
        String objectKey = pending.getObjectKey();

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        CountingInputStream counted = new CountingInputStream(new DigestInputStream(content, digest));
        String version;
        String actual;
        try
        {
            Map<String, String> objectMetadata = new HashMap<>();
            if (metadata != null)
            {
                metadata.forEach((name, value) -> {
                    if (name != null && value != null)
                    {
                        objectMetadata.put(name.toString(), value.toString());
                    }
                });
            }
            objectMetadata.put("object-id", pending.getObjectId());
            version = objectStorageAdapter.putObject(bucket, objectKey, pending.getContentType(), contentLength,
                    counted, objectMetadata);
            actual = HexFormat.of().formatHex(digest.digest());
        }
        catch (RuntimeException e)
        {
            markFailed(referenceId, "Upload failed: " + StringUtils.abbreviate(String.valueOf(e.getMessage()), 400), 0);
            throw e;
        }

        // The S3 client sends exactly the declared size: anything left over means the size was wrong
        String rejection = null;
        if (counted.count() != contentLength || hasMore(content))
        {
            rejection = "The file is not the size it was declared to be";
        }
        else if (expectedSha256 != null && !expectedSha256.equals(actual))
        {
            rejection = "The file was damaged in transit: its checksum does not match";
        }
        if (rejection != null)
        {
            objectStorageAdapter.deleteObject(bucket, objectKey);
            markFailed(referenceId, rejection, counted.count());
            throw new StorageServiceException(rejection);
        }

        return ownTransaction.execute(status -> markStored(referenceId, actual, version, counted.count()));
    }

    private static boolean hasMore(InputStream content)
    {
        try
        {
            return content.read() != -1;
        }
        catch (IOException closed)
        {
            // The client closes the stream once it has sent the declared size
            return false;
        }
    }

    private DocumentStorageReferenceDTO markStored(UUID referenceId, String sha256, String version, long received)
    {
        LocalDateTime now = LocalDateTime.now();
        DocumentStorageReference reference = documentStorageReferenceRepository.findById(referenceId).orElseThrow();
        reference.setSha256Checksum(sha256);
        reference.setObjectVersion(version);
        reference.setETag(version);
        reference.setUploadedAt(now);
        reference.setStatus(StorageStatus.STORED);
        reference.setFailureReason(null);
        touch(reference);
        reference = documentStorageReferenceRepository.save(reference);

        for (StorageUploadSession session : storageUploadSessionRepository.findByStorageReference(reference))
        {
            session.setStatus(UploadSessionStatus.COMPLETED);
            session.setReceivedBytes(received);
            session.setCompletedAt(now);
            session.setModifiedAt(now);
            session.setModifiedBy(reference.getModifiedBy());
            storageUploadSessionRepository.save(session);
        }
        return toDto(reference);
    }

    private void markFailed(UUID referenceId, String reason, long received)
    {
        ownTransaction.executeWithoutResult(status -> {
            LocalDateTime now = LocalDateTime.now();
            DocumentStorageReference reference = documentStorageReferenceRepository.findById(referenceId).orElseThrow();
            reference.setStatus(StorageStatus.FAILED);
            // FAILURE_REASON is VARCHAR(255): keep the start of the message rather than fail the update
            reference.setFailureReason(StringUtils.abbreviate(reason, MAX_FAILURE_REASON));
            touch(reference);
            documentStorageReferenceRepository.save(reference);

            for (StorageUploadSession session : storageUploadSessionRepository.findByStorageReference(reference))
            {
                session.setStatus(UploadSessionStatus.ABORTED);
                session.setReceivedBytes(received);
                session.setModifiedAt(now);
                session.setModifiedBy(reference.getModifiedBy());
                storageUploadSessionRepository.save(session);
            }
        });
    }

    private boolean purge(UUID id)
    {
        DocumentStorageReference reference = documentStorageReferenceRepository.findById(id).orElse(null);
        if (reference == null || reference.getStatus() != StorageStatus.PENDING_DELETION)
        {
            return false;
        }
        try
        {
            objectStorageAdapter.deleteObject(reference.getBucket(), reference.getObjectKey());
        }
        catch (RuntimeException e)
        {
            logger.warn("Could not delete stored object {}: {}", reference.getObjectId(), e.getMessage());
            reference.setFailureReason(StringUtils.abbreviate("Deletion failed: " + e.getMessage(), MAX_FAILURE_REASON));
            touch(reference);
            documentStorageReferenceRepository.save(reference);
            recordFailedDeletion(reference, e);
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        reference.setStatus(StorageStatus.DELETED);
        reference.setDeletedAt(now);
        reference.setFailureReason(null);
        touch(reference);
        documentStorageReferenceRepository.save(reference);
        return true;
    }

    private void recordFailedDeletion(DocumentStorageReference reference, RuntimeException e)
    {
        if (storageReconciliationIssueRepository.existsByIssueTypeAndBucketAndObjectKeyAndResolution(
                ReconciliationIssueType.FAILED_DELETION, reference.getBucket(), reference.getObjectKey(),
                ReconciliationResolution.OPEN))
        {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        StorageReconciliationIssue issue = StorageReconciliationIssue.Factory.newInstance();
        issue.setIssueType(ReconciliationIssueType.FAILED_DELETION);
        issue.setBucket(reference.getBucket());
        issue.setObjectKey(reference.getObjectKey());
        issue.setDetectedAt(now);
        issue.setDetails("The scheduled deletion failed: " + StringUtils.abbreviate(String.valueOf(e.getMessage()), 400));
        issue.setResolution(ReconciliationResolution.OPEN);
        issue.setAttempts(1);
        issue.setStorageReference(reference);
        issue.setCreatedAt(now);
        issue.setCreatedBy("system");
        storageReconciliationIssueRepository.save(issue);
    }

    // ------------------------------------------------------------------------------------------------
    // Rules
    // ------------------------------------------------------------------------------------------------

    private static void checkWriteRequest(StorageWriteRequestDTO request)
    {
        List<String> errors = new ArrayList<>();
        if (request.getNamespace() == null)
        {
            errors.add("Say which namespace the file belongs to");
        }
        if (StringUtils.isBlank(request.getContentType()) || !CONTENT_TYPE.matcher(request.getContentType().trim()).matches())
        {
            errors.add("Give the file's content type, such as application/pdf");
        }
        if (request.getContentLength() != null && request.getContentLength() < 0)
        {
            errors.add("The size of the file cannot be negative");
        }
        if (request.getExpectedSha256() != null
                && !SHA256.matcher(request.getExpectedSha256().trim().toLowerCase()).matches())
        {
            errors.add("The expected checksum must be a SHA-256 in hexadecimal (64 characters)");
        }
        if (request.getOriginalFileName() != null && request.getOriginalFileName().length() > MAX_FILE_NAME_LENGTH)
        {
            errors.add("The file name is longer than " + MAX_FILE_NAME_LENGTH + " characters");
        }
        if (!errors.isEmpty())
        {
            throw new StorageServiceException(String.join("; ", errors));
        }
    }

    /**
     * Originals and final signed artifacts are kept until their document's retention date; a document
     * without one keeps them indefinitely.
     */
    private void checkRetention(DocumentStorageReference reference, LocalDateTime deleteAfter)
    {
        LocalDate deletionDate = deleteAfter.toLocalDate();
        for (Object[] row : documentStorageReferenceRepository.findArtifactRetention(reference.getId()))
        {
            ArtifactType type = (ArtifactType) row[0];
            LocalDate retentionUntil = (LocalDate) row[1];
            boolean retained = type == ArtifactType.ORIGINAL || type == ArtifactType.FINAL_SIGNED
                    || reference.getNamespace() == StorageNamespace.ORIGINALS;
            if (retained && (retentionUntil == null || deletionDate.isBefore(retentionUntil)))
            {
                throw new StorageServiceException(retentionUntil == null
                        ? "The file is an original or final signed document that is kept indefinitely and cannot be deleted"
                        : "The file is an original or final signed document that must be kept until " + retentionUntil);
            }
        }
    }

    private DocumentStorageReference load(String objectId)
    {
        return documentStorageReferenceRepository.findByObjectId(objectId.trim())
                .orElseThrow(() -> StorageServiceException.notFound("The file does not exist"));
    }

    private DocumentStorageReference loadReadable(String objectId)
    {
        DocumentStorageReference reference = load(objectId);
        checkReadAccess(reference);
        if (reference.getStatus() == StorageStatus.PENDING_UPLOAD && entityManager.contains(reference))
        {
            // The upload is finished in a transaction of its own (see store): a caller whose transaction loaded
            // the reference before that, e.g. processing straight after an upload in the same request, holds a
            // stale PENDING_UPLOAD copy, so read the current state before refusing
            entityManager.refresh(reference);
        }
        if (!READABLE.contains(reference.getStatus()))
        {
            throw new StorageServiceException(switch (reference.getStatus())
            {
                case PENDING_UPLOAD -> "The file is still being uploaded";
                case FAILED -> "The file could not be stored; upload it again";
                case PENDING_DELETION, DELETED -> "The file has been deleted";
                default -> "The file is not available";
            });
        }
        return reference;
    }

    private void checkReadAccess(DocumentStorageReference reference)
    {
        // Branding files are served publicly
        if (reference.getNamespace() == StorageNamespace.BRANDING)
        {
            return;
        }
        if (!mayAccess(reference.getOrganisation()))
        {
            throw StorageServiceException.notFound("The file does not exist");
        }
    }

    private void checkWriteAccess(Organisation organisation)
    {
        if (!mayAccess(organisation))
        {
            throw new StorageServiceException("Files can only be stored for your own organisation");
        }
    }

    /** Callers without a tenant are not limited here; a tenant only reaches its own organisation. */
    private boolean mayAccess(Organisation organisation)
    {
        Optional<String> tenant = callerTenant();
        if (tenant.isEmpty())
        {
            return true;
        }
        if (organisation == null)
        {
            return false;
        }
        return tenant.get().equals(organisation.getTenantId())
                || organisation.getId().toString().equals(callerOrganisation().orElse(null));
    }

    // ------------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------------

    private DocumentStorageReferenceDTO toDto(DocumentStorageReference reference)
    {
        DocumentStorageReferenceDTO dto = documentStorageReferenceMapper.toDocumentStorageReferenceDTO(reference);
        dto.setOrganisationId(reference.getOrganisation() == null ? null : reference.getOrganisation().getId().toString());
        return dto;
    }

    private static long bytes(Map<StorageNamespace, long[]> byNamespace, StorageNamespace... namespaces)
    {
        long total = 0;
        for (StorageNamespace namespace : namespaces)
        {
            long[] counts = byNamespace.get(namespace);
            total += counts == null ? 0 : counts[1];
        }
        return total;
    }

    private void touch(DocumentStorageReference reference)
    {
        reference.setModifiedAt(LocalDateTime.now());
        reference.setModifiedBy(actorId());
    }

    private void audit(AuditEventType type, AuditOutcome outcome, DocumentStorageReference reference, String details)
    {
        AuditEventDTO event = new AuditEventDTO();
        event.setEventType(type);
        event.setOutcome(outcome);
        event.setOccurredAt(LocalDateTime.now());
        event.setOrganisationId(reference.getOrganisation() == null ? null : reference.getOrganisation().getId().toString());
        Optional<String> user = callerUser();
        event.setActorType(user.isEmpty() ? ActorType.SYSTEM
                : callerTenant().isPresent() ? ActorType.ORGANISATION_USER : ActorType.PLATFORM_ADMIN);
        event.setActorId(user.orElse("system"));
        event.setEntityType("DocumentStorageReference");
        event.setEntityId(reference.getObjectId());
        event.setDocumentSha256(reference.getSha256Checksum());
        event.setDetails(details);
        auditTrailService.record(event);
    }

    private String actorId()
    {
        return callerUser().orElse("system");
    }

    /*
     * CallerContext refuses when nobody is signed in: background jobs and the public endpoints, which act
     * as the system.
     */
    private Optional<String> callerUser()
    {
        try
        {
            return Optional.ofNullable(caller.userId());
        }
        catch (RuntimeException notSignedIn)
        {
            return Optional.empty();
        }
    }

    private Optional<String> callerTenant()
    {
        try
        {
            return caller.tenantId();
        }
        catch (RuntimeException notSignedIn)
        {
            return Optional.empty();
        }
    }

    private Optional<String> callerOrganisation()
    {
        try
        {
            return caller.organisationId();
        }
        catch (RuntimeException notSignedIn)
        {
            return Optional.empty();
        }
    }

    private static UUID uuid(String id, String what)
    {
        try
        {
            return UUID.fromString(id.trim());
        }
        catch (IllegalArgumentException e)
        {
            throw StorageServiceException.notFound("'" + id + "' is not the id of an " + what);
        }
    }

    /** Counts the bytes the S3 client reads. */
    private static final class CountingInputStream
        extends FilterInputStream
    {
        private long count;

        CountingInputStream(InputStream in)
        {
            super(in);
        }

        long count()
        {
            return count;
        }

        @Override
        public int read() throws IOException
        {
            int b = super.read();
            if (b >= 0)
            {
                count++;
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException
        {
            int n = super.read(buffer, offset, length);
            if (n > 0)
            {
                count += n;
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException
        {
            long skipped = super.skip(n);
            count += skipped;
            return skipped;
        }
    }
}
