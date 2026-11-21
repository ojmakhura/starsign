// license-header java merge-point
/**
 * This is only generated once! It will never be overwritten.
 * You can (and have to!) safely modify it by hand.
 * TEMPLATE:    SpringServiceImpl.vsl in andromda-spring cartridge
 * MODEL CLASS: AndroMDAModel::backend::bw.co.starsign::storage::StorageReconciliationService
 * STEREOTYPE:  Service
 */
package bw.co.starsign.storage;

import bw.co.starsign.audit.ActorType;
import bw.co.starsign.audit.AuditEventDTO;
import bw.co.starsign.audit.AuditEventType;
import bw.co.starsign.audit.AuditOutcome;
import bw.co.starsign.audit.AuditTrailService;
import bw.co.starsign.context.CallerContext;
import bw.co.starsign.organisation.Organisation;
import bw.co.starsign.organisation.OrganisationRepository;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Compares the storage references with what is in the bucket and records what does not match as
 * {@link StorageReconciliationIssue}s, which platform administrators resolve.
 *
 * @see bw.co.starsign.storage.StorageReconciliationService
 */
@Service("storageReconciliationService")
@Transactional(propagation = Propagation.REQUIRED, readOnly = false)
public class StorageReconciliationServiceImpl
    extends StorageReconciliationServiceBase
{
    /** runReconciliation's organisation id for the platform-wide files (platform branding). */
    public static final String PLATFORM = "platform";

    /** A PENDING_UPLOAD reference older than this is not going to finish. */
    private static final long STALE_UPLOAD_MINUTES = 60;

    /** Stored objects are re-hashed when they were last verified longer ago than this. */
    private static final long VERIFY_AFTER_DAYS = 7;

    private static final int PAGE_SIZE = 1000;

    private static final Set<StorageStatus> IN_BUCKET = EnumSet.of(StorageStatus.STORED, StorageStatus.VERIFIED,
            StorageStatus.PENDING_DELETION);

    private final StorageProperties properties;
    private final OrganisationRepository organisationRepository;
    private final StorageUploadSessionRepository storageUploadSessionRepository;
    private final CallerContext caller;

    public StorageReconciliationServiceImpl(
        @Lazy ObjectStorageAdapter objectStorageAdapter,
        @Lazy AuditTrailService auditTrailService,
        StorageReconciliationIssueRepository storageReconciliationIssueRepository,
        StorageReconciliationIssueMapper storageReconciliationIssueMapper,
        DocumentStorageReferenceRepository documentStorageReferenceRepository,
        DocumentStorageReferenceMapper documentStorageReferenceMapper,
        StorageProperties properties,
        OrganisationRepository organisationRepository,
        StorageUploadSessionRepository storageUploadSessionRepository,
        CallerContext caller) {

        super(
            objectStorageAdapter,
            auditTrailService,
            storageReconciliationIssueRepository,
            storageReconciliationIssueMapper,
            documentStorageReferenceRepository,
            documentStorageReferenceMapper);
        this.properties = properties;
        this.organisationRepository = organisationRepository;
        this.storageUploadSessionRepository = storageUploadSessionRepository;
        this.caller = caller;
    }

    /**
     * Reconciles one organisation's prefix of the bucket ({@value #PLATFORM} for the platform files).
     * Open issues whose cause has gone away are resolved on the way.
     *
     * @see bw.co.starsign.storage.StorageReconciliationService#runReconciliation(String)
     */
    @Override
    protected ReconciliationRunDTO handleRunReconciliation(String organisationId)
        throws Exception
    {
        ReconciliationRunDTO run = new ReconciliationRunDTO();
        LocalDateTime startedAt = LocalDateTime.now();
        run.setStartedAt(startedAt);

        Organisation organisation = PLATFORM.equalsIgnoreCase(organisationId.trim()) ? null
                : organisationRepository.findById(uuid(organisationId, "organisation"))
                        .orElseThrow(() -> StorageReconciliationServiceException.notFound("The organisation does not exist"));
        String bucket = properties.getBucket();
        String prefix = StorageKeys.tenantPrefix(organisation);

        Set<String> keys = listKeys(bucket, prefix);
        List<DocumentStorageReference> references = organisation == null
                ? documentStorageReferenceRepository.findByOrganisationIsNull()
                : documentStorageReferenceRepository.findByOrganisationId(organisation.getId());

        int detected = 0;
        Set<String> referencedKeys = new HashSet<>();
        LocalDateTime staleBefore = startedAt.minusMinutes(STALE_UPLOAD_MINUTES);
        LocalDateTime verifyBefore = startedAt.minusDays(VERIFY_AFTER_DAYS);
        for (DocumentStorageReference reference : references)
        {
            if (!bucket.equals(reference.getBucket()))
            {
                continue;
            }
            referencedKeys.add(reference.getObjectKey());
            boolean present = keys.contains(reference.getObjectKey());
            StorageStatus status = reference.getStatus();

            if (IN_BUCKET.contains(status) && !present)
            {
                detected += issue(ReconciliationIssueType.MISSING_OBJECT, reference, bucket, reference.getObjectKey(),
                        "The reference is " + status + " but the object is not in the bucket");
            }
            else if ((status == StorageStatus.STORED || status == StorageStatus.VERIFIED)
                    && (reference.getLastVerifiedAt() == null || reference.getLastVerifiedAt().isBefore(verifyBefore)))
            {
                detected += verify(reference);
            }
            else if (status == StorageStatus.PENDING_UPLOAD && reference.getCreatedAt() != null
                    && reference.getCreatedAt().isBefore(staleBefore))
            {
                detected += issue(ReconciliationIssueType.STALE_PENDING_UPLOAD, reference, bucket, reference.getObjectKey(),
                        "The upload started at " + reference.getCreatedAt() + " and never completed"
                                + (present ? " (the object is in the bucket)" : ""));
            }
            else if (status == StorageStatus.DELETED && present)
            {
                detected += issue(ReconciliationIssueType.FAILED_DELETION, reference, bucket, reference.getObjectKey(),
                        "The reference was deleted at " + reference.getDeletedAt() + " but the object is still in the bucket");
            }
        }

        for (String key : keys)
        {
            if (!referencedKeys.contains(key) && documentStorageReferenceRepository.findByBucketAndObjectKey(bucket, key).isEmpty())
            {
                detected += issue(ReconciliationIssueType.ORPHANED_OBJECT, null, bucket, key,
                        "The object " + key + " has no storage reference");
            }
        }

        int resolved = resolveGoneIssues(bucket, prefix, keys);
        expireUploadSessions(startedAt);

        run.setObjectsScanned((long) keys.size());
        run.setReferencesScanned((long) references.size());
        run.setIssuesDetected(detected);
        run.setIssuesResolved(resolved);
        run.setFinishedAt(LocalDateTime.now());

        audit(organisation, organisation == null ? PLATFORM : organisation.getId().toString(),
                "Reconciled " + keys.size() + " object(s) and " + references.size() + " reference(s): "
                        + detected + " new issue(s), " + resolved + " resolved");
        return run;
    }

    /**
     * Completes uploads whose object did reach the bucket (the upload finished but the reference was not
     * updated): the object is accepted when its SHA-256 matches the expected checksum, or, when none was
     * given, when its size matches. Content is not kept after a failed upload, so references without an
     * object stay FAILED until their owner uploads the file again. Returns the number recovered.
     *
     * @see bw.co.starsign.storage.StorageReconciliationService#retryFailedUploads()
     */
    @Override
    protected int handleRetryFailedUploads()
        throws Exception
    {
        LocalDateTime now = LocalDateTime.now();
        List<DocumentStorageReference> candidates = new ArrayList<>(documentStorageReferenceRepository
                .findByStatusInAndCreatedAtBefore(EnumSet.of(StorageStatus.FAILED), now));
        candidates.addAll(documentStorageReferenceRepository
                .findByStatusInAndCreatedAtBefore(EnumSet.of(StorageStatus.PENDING_UPLOAD), now.minusMinutes(STALE_UPLOAD_MINUTES)));

        int recovered = 0;
        for (DocumentStorageReference reference : candidates)
        {
            try
            {
                if (recover(reference))
                {
                    recovered++;
                }
                else
                {
                    if (reference.getStatus() == StorageStatus.PENDING_UPLOAD)
                    {
                        reference.setStatus(StorageStatus.FAILED);
                        reference.setFailureReason("The upload did not complete");
                        touch(reference);
                        documentStorageReferenceRepository.save(reference);
                    }
                    for (StorageReconciliationIssue issue : openIssues(reference))
                    {
                        issue.setAttempts(issue.getAttempts() + 1);
                        touch(issue);
                    }
                }
            }
            catch (RuntimeException e)
            {
                // One unreachable object should not stop the others; it is tried again on the next run
                logger.warn("Could not retry the upload of stored object {}: {}", reference.getObjectId(), e.getMessage());
            }
        }
        if (recovered > 0)
        {
            logger.info("Recovered {} upload(s) whose object was already in the bucket", recovered);
        }
        return recovered;
    }

    /**
     * @see bw.co.starsign.storage.StorageReconciliationService#findIssues(ReconciliationResolution)
     */
    @Override
    protected List<StorageReconciliationIssueDTO> handleFindIssues(ReconciliationResolution resolution)
        throws Exception
    {
        return storageReconciliationIssueRepository.findByResolutionOrderByDetectedAtDesc(resolution)
                .stream().map(this::toDto).toList();
    }

    /**
     * RE_UPLOADED: the object is in the bucket again with the recorded checksum (checked here);
     * OBJECT_DELETED: deletes an orphaned, undeletable or corrupt object; REFERENCE_MARKED_FAILED: the
     * reference is given up (FAILED); IGNORED: nothing is changed.
     *
     * @see bw.co.starsign.storage.StorageReconciliationService#resolveIssue(String, ReconciliationResolution)
     */
    @Override
    protected StorageReconciliationIssueDTO handleResolveIssue(String id, ReconciliationResolution resolution)
        throws Exception
    {
        StorageReconciliationIssue issue = storageReconciliationIssueRepository.findById(uuid(id, "issue"))
                .orElseThrow(() -> StorageReconciliationServiceException.notFound("The issue does not exist"));
        if (issue.getResolution() != ReconciliationResolution.OPEN)
        {
            throw new StorageReconciliationServiceException("The issue was already resolved (" + issue.getResolution() + ")");
        }
        if (resolution == ReconciliationResolution.OPEN)
        {
            throw new StorageReconciliationServiceException("Choose how the issue was resolved");
        }

        ReconciliationIssueType type = issue.getIssueType();
        DocumentStorageReference reference = issue.getStorageReference();
        switch (resolution)
        {
            case RE_UPLOADED ->
            {
                if (reference == null || type == ReconciliationIssueType.FAILED_DELETION)
                {
                    throw new StorageReconciliationServiceException("Only a missing, damaged or unfinished upload can be re-uploaded");
                }
                if (!recover(reference))
                {
                    throw new StorageReconciliationServiceException(
                            "The object is still missing from the bucket or does not match the recorded checksum");
                }
            }
            case OBJECT_DELETED ->
            {
                if (type == ReconciliationIssueType.MISSING_OBJECT || type == ReconciliationIssueType.STALE_PENDING_UPLOAD)
                {
                    throw new StorageReconciliationServiceException("There is no object to delete for this issue");
                }
                objectStorageAdapter.deleteObject(issue.getBucket(), issue.getObjectKey());
                if (reference != null)
                {
                    if (type == ReconciliationIssueType.FAILED_DELETION)
                    {
                        reference.setStatus(StorageStatus.DELETED);
                        reference.setDeletedAt(LocalDateTime.now());
                        reference.setFailureReason(null);
                    }
                    else
                    {
                        reference.setStatus(StorageStatus.FAILED);
                        reference.setFailureReason("The stored object was damaged and has been deleted");
                    }
                    touch(reference);
                    documentStorageReferenceRepository.save(reference);
                }
            }
            case REFERENCE_MARKED_FAILED ->
            {
                if (reference == null || type == ReconciliationIssueType.FAILED_DELETION)
                {
                    throw new StorageReconciliationServiceException("Only a missing, damaged or unfinished upload can be marked failed");
                }
                reference.setStatus(StorageStatus.FAILED);
                reference.setFailureReason(StringUtils.abbreviate("Marked failed by reconciliation: " + type, 255));
                touch(reference);
                documentStorageReferenceRepository.save(reference);
            }
            default -> { }
        }

        issue.setAttempts(issue.getAttempts() + 1);
        resolve(issue, resolution);
        audit(reference == null ? null : reference.getOrganisation(),
                issue.getId().toString(), type + " issue resolved as " + resolution);
        return toDto(issue);
    }

    // ------------------------------------------------------------------------------------------------

    private Set<String> listKeys(String bucket, String prefix)
    {
        Set<String> keys = new LinkedHashSet<>();
        String after = prefix;
        while (true)
        {
            List<String> page = objectStorageAdapter.listObjectKeys(bucket, prefix, after, PAGE_SIZE);
            if (page.isEmpty())
            {
                return keys;
            }
            keys.addAll(page);
            after = page.get(page.size() - 1);
        }
    }

    /** Re-hashes a stored object; a match marks it VERIFIED. Returns the number of issues recorded. */
    private int verify(DocumentStorageReference reference)
    {
        String actual;
        try
        {
            actual = StorageKeys.sha256(objectStorageAdapter.getObject(reference.getBucket(), reference.getObjectKey()));
        }
        catch (IOException e)
        {
            throw new StorageReconciliationServiceException("Could not read stored object " + reference.getObjectId(), e);
        }
        reference.setLastVerifiedAt(LocalDateTime.now());
        touch(reference);
        if (actual.equalsIgnoreCase(reference.getSha256Checksum()))
        {
            reference.setStatus(StorageStatus.VERIFIED);
            documentStorageReferenceRepository.save(reference);
            return 0;
        }
        documentStorageReferenceRepository.save(reference);
        return issue(ReconciliationIssueType.CHECKSUM_MISMATCH, reference, reference.getBucket(), reference.getObjectKey(),
                "Recorded SHA-256 " + reference.getSha256Checksum() + ", object has " + actual);
    }

    /**
     * Accepts the object in the bucket for the reference when it is complete; resolves the reference's open
     * issues as RE_UPLOADED.
     */
    private boolean recover(DocumentStorageReference reference)
    {
        Map<?, ?> head = objectStorageAdapter.headObject(reference.getBucket(), reference.getObjectKey());
        if (head.isEmpty())
        {
            return false;
        }
        String actual;
        try
        {
            actual = StorageKeys.sha256(objectStorageAdapter.getObject(reference.getBucket(), reference.getObjectKey()));
        }
        catch (IOException e)
        {
            throw new StorageReconciliationServiceException("Could not read stored object " + reference.getObjectId(), e);
        }
        Object size = head.get(ObjectStorageAdapterImpl.CONTENT_LENGTH);
        boolean checksumUnknown = StorageKeys.pendingChecksum(reference.getObjectId()).equals(reference.getSha256Checksum());
        boolean complete = checksumUnknown
                ? size instanceof Number number && number.longValue() == reference.getContentLength()
                : actual.equalsIgnoreCase(reference.getSha256Checksum());
        if (!complete)
        {
            return false;
        }

        LocalDateTime now = LocalDateTime.now();
        reference.setSha256Checksum(actual);
        reference.setETag((String) head.get(ObjectStorageAdapterImpl.E_TAG));
        reference.setObjectVersion(StringUtils.defaultIfBlank((String) head.get(ObjectStorageAdapterImpl.VERSION_ID), reference.getETag()));
        reference.setStatus(StorageStatus.VERIFIED);
        reference.setLastVerifiedAt(now);
        if (reference.getUploadedAt() == null)
        {
            reference.setUploadedAt(now);
        }
        reference.setFailureReason(null);
        touch(reference);
        documentStorageReferenceRepository.save(reference);

        for (StorageUploadSession session : storageUploadSessionRepository.findByStorageReference(reference))
        {
            if (session.getStatus() != UploadSessionStatus.COMPLETED)
            {
                session.setStatus(UploadSessionStatus.COMPLETED);
                session.setReceivedBytes(reference.getContentLength());
                session.setCompletedAt(now);
                session.setModifiedAt(now);
                session.setModifiedBy(actorId());
            }
        }
        for (StorageReconciliationIssue issue : openIssues(reference))
        {
            resolve(issue, ReconciliationResolution.RE_UPLOADED);
        }
        return true;
    }

    /** Resolves open issues of this prefix whose cause is gone. */
    private int resolveGoneIssues(String bucket, String prefix, Set<String> keys)
    {
        int resolved = 0;
        for (StorageReconciliationIssue issue : storageReconciliationIssueRepository
                .findByResolutionOrderByDetectedAtDesc(ReconciliationResolution.OPEN))
        {
            if (!bucket.equals(issue.getBucket()) || !issue.getObjectKey().startsWith(prefix))
            {
                continue;
            }
            DocumentStorageReference reference = issue.getStorageReference();
            boolean present = keys.contains(issue.getObjectKey());
            ReconciliationResolution resolution = switch (issue.getIssueType())
            {
                case ORPHANED_OBJECT -> present ? null : ReconciliationResolution.OBJECT_DELETED;
                case FAILED_DELETION -> present ? null : ReconciliationResolution.OBJECT_DELETED;
                case MISSING_OBJECT -> present ? ReconciliationResolution.RE_UPLOADED : null;
                case STALE_PENDING_UPLOAD, CHECKSUM_MISMATCH -> reference != null
                        && reference.getStatus() == StorageStatus.VERIFIED ? ReconciliationResolution.RE_UPLOADED : null;
            };
            if (resolution != null && issue.getResolution() == ReconciliationResolution.OPEN)
            {
                resolve(issue, resolution);
                resolved++;
            }
        }
        return resolved;
    }

    private void expireUploadSessions(LocalDateTime now)
    {
        for (StorageUploadSession session : storageUploadSessionRepository.findByStatusAndExpiresAtBefore(UploadSessionStatus.OPEN, now))
        {
            session.setStatus(UploadSessionStatus.EXPIRED);
            session.setModifiedAt(now);
            session.setModifiedBy(actorId());
        }
    }

    /** Records an issue unless the same one is already open; returns 1 when recorded. */
    private int issue(ReconciliationIssueType type, DocumentStorageReference reference, String bucket, String objectKey,
            String details)
    {
        if (storageReconciliationIssueRepository.existsByIssueTypeAndBucketAndObjectKeyAndResolution(type, bucket,
                objectKey, ReconciliationResolution.OPEN))
        {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        StorageReconciliationIssue issue = StorageReconciliationIssue.Factory.newInstance();
        issue.setIssueType(type);
        issue.setBucket(bucket);
        issue.setObjectKey(objectKey);
        issue.setDetectedAt(now);
        issue.setDetails(details);
        issue.setResolution(ReconciliationResolution.OPEN);
        issue.setAttempts(0);
        issue.setStorageReference(reference);
        issue.setCreatedAt(now);
        issue.setCreatedBy(actorId());
        storageReconciliationIssueRepository.save(issue);
        logger.warn("Storage reconciliation: {} for {}", type, objectKey);
        return 1;
    }

    private List<StorageReconciliationIssue> openIssues(DocumentStorageReference reference)
    {
        return storageReconciliationIssueRepository.findByStorageReferenceAndResolution(reference, ReconciliationResolution.OPEN);
    }

    private void resolve(StorageReconciliationIssue issue, ReconciliationResolution resolution)
    {
        issue.setResolution(resolution);
        issue.setResolvedAt(LocalDateTime.now());
        issue.setResolvedBy(actorId());
        touch(issue);
        storageReconciliationIssueRepository.save(issue);
    }

    private StorageReconciliationIssueDTO toDto(StorageReconciliationIssue issue)
    {
        StorageReconciliationIssueDTO dto = storageReconciliationIssueMapper.toStorageReconciliationIssueDTO(issue);
        dto.setObjectId(issue.getStorageReference() == null ? null : issue.getStorageReference().getObjectId());
        return dto;
    }

    private void touch(bw.co.starsign.common.Auditable entity)
    {
        entity.setModifiedAt(LocalDateTime.now());
        entity.setModifiedBy(actorId());
    }

    private void audit(Organisation organisation, String entityId, String details)
    {
        AuditEventDTO event = new AuditEventDTO();
        event.setEventType(AuditEventType.STORAGE_RECONCILED);
        event.setOutcome(AuditOutcome.SUCCESS);
        event.setOccurredAt(LocalDateTime.now());
        event.setOrganisationId(organisation == null ? null : organisation.getId().toString());
        Optional<String> user = callerUser();
        event.setActorType(user.isEmpty() ? ActorType.SYSTEM : ActorType.PLATFORM_ADMIN);
        event.setActorId(user.orElse("system"));
        event.setEntityType("StorageReconciliation");
        event.setEntityId(entityId);
        event.setDetails(details);
        auditTrailService.record(event);
    }

    private String actorId()
    {
        return callerUser().orElse("system");
    }

    /* CallerContext refuses when nobody is signed in: scheduled runs act as the system. */
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

    private static UUID uuid(String id, String what)
    {
        try
        {
            return UUID.fromString(id.trim());
        }
        catch (IllegalArgumentException e)
        {
            throw StorageReconciliationServiceException.notFound("'" + id + "' is not the id of an " + what);
        }
    }
}
