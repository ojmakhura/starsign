// license-header java merge-point
/**
 * This is only generated once! It will never be overwritten.
 * You can (and have to!) safely modify it by hand.
 * TEMPLATE:    SpringServiceImpl.vsl in andromda-spring cartridge
 * MODEL CLASS: AndroMDAModel::backend::bw.co.starsign::audit::AuditTrailService
 * STEREOTYPE:  Service
 */
package bw.co.starsign.audit;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Page;
import bw.co.starsign.common.SortDirection;
import bw.co.starsign.context.CallerContext;
import bw.co.starsign.messaging.OutboxService;
import bw.co.starsign.organisation.Organisation;
import bw.co.starsign.organisation.OrganisationRepository;
import bw.co.starsign.storage.DocumentStorageReference;
import bw.co.starsign.storage.DocumentStorageReferenceDTO;
import bw.co.starsign.storage.DocumentStorageReferenceRepository;
import bw.co.starsign.storage.StorageNamespace;
import bw.co.starsign.storage.StorageService;
import bw.co.starsign.storage.StorageWriteRequestDTO;
import bw.co.starsign.workflow.SigningWorkflow;
import bw.co.starsign.workflow.SigningWorkflowRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.Predicate;
import jakarta.validation.Valid;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tamper-evident, insert-only audit trail.
 * <p>
 * Every tenant has one hash chain (chain key = the organisation's tenant id, {@value #PLATFORM_CHAIN}
 * for platform events). Each event stores a gap-free sequence number, the previous event's hash and
 * SHA-256(previousHash || canonical JSON of itself). Appends take a transaction-level advisory lock on
 * the chain, so concurrent writers cannot fork it. Seals ({@link AuditLogIntegrityRecord}) checkpoint a
 * verified range with a signature, so a rewrite of the whole chain is also detected.
 * <p>
 * Transactions are opened with {@link TransactionTemplate}: the public methods are declared on the
 * generated base class, where a class-level {@code @Transactional} on this class would not apply.
 *
 * @see bw.co.starsign.audit.AuditTrailService
 */
@Service("auditTrailService")
public class AuditTrailServiceImpl
    extends AuditTrailServiceBase
{
    static final String PLATFORM_CHAIN = "platform";

    private static final int MAX_STRING = 255;
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 500;
    private static final int MAX_EXPORT_ROWS = 50_000;
    private static final int VERIFY_BATCH = 500;
    private static final int EXPORT_VALID_DAYS = 30;
    private static final Set<String> SORT_FIELDS = Set.of(
            "occurredAt", "sequenceNumber", "eventType", "actorId", "outcome", "organisationId", "workflowId", "documentId");
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final CallerContext caller;
    private final OrganisationRepository organisationRepository;
    private final SigningWorkflowRepository signingWorkflowRepository;
    private final DocumentStorageReferenceRepository documentStorageReferenceRepository;
    private final AuditSealSigner sealSigner;
    private final TransactionTemplate required;
    private final TransactionTemplate readOnly;
    private final ObjectMapper json = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    @PersistenceContext
    private EntityManager entityManager;

    public AuditTrailServiceImpl(
        @Lazy StorageService storageService,
        @Lazy OutboxService outboxService,
        AuditEventRepository auditEventRepository,
        AuditEventMapper auditEventMapper,
        AuditLogIntegrityRecordRepository auditLogIntegrityRecordRepository,
        AuditLogIntegrityRecordMapper auditLogIntegrityRecordMapper,
        AuditEvidenceExportRepository auditEvidenceExportRepository,
        AuditEvidenceExportMapper auditEvidenceExportMapper,
        CallerContext caller,
        OrganisationRepository organisationRepository,
        SigningWorkflowRepository signingWorkflowRepository,
        DocumentStorageReferenceRepository documentStorageReferenceRepository,
        AuditSealSigner sealSigner,
        PlatformTransactionManager transactionManager) {

        super(
            storageService,
            outboxService,
            auditEventRepository,
            auditEventMapper,
            auditLogIntegrityRecordRepository,
            auditLogIntegrityRecordMapper,
            auditEvidenceExportRepository,
            auditEvidenceExportMapper);
        this.caller = caller;
        this.organisationRepository = organisationRepository;
        this.signingWorkflowRepository = signingWorkflowRepository;
        this.documentStorageReferenceRepository = documentStorageReferenceRepository;
        this.sealSigner = sealSigner;
        this.required = new TransactionTemplate(transactionManager);
        this.required.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.readOnly.setReadOnly(true);
    }

    /**
     * @see bw.co.starsign.audit.AuditTrailService#record(@Valid AuditEventDTO)
     */
    @Override
    protected AuditEventDTO handleRecord(@Valid AuditEventDTO event)
        throws Exception
    {
        if (event.getEventType() == null)
        {
            throw new AuditTrailServiceException("Say what kind of event is being audited");
        }
        Who who = who();
        return required.execute(status -> auditEventMapper.toAuditEventDTO(append(event, resolveChainKey(event, who), who)));
    }

    /**
     * @see bw.co.starsign.audit.AuditTrailService#search(@Valid AuditEventSearchCriteria)
     */
    @Override
    protected List<AuditEventDTO> handleSearch(@Valid AuditEventSearchCriteria criteria)
        throws Exception
    {
        Who who = who();
        Specification<AuditEvent> filter = filter(criteria, who);
        Pageable page = page(criteria);
        return readOnly.execute(status -> present(auditEventRepository.findAll(filter, page).getContent(), who));
    }

    /**
     * @see bw.co.starsign.audit.AuditTrailService#pagedSearch(AuditEventSearchCriteria)
     */
    @Override
    protected Page<AuditEventDTO> handlePagedSearch(@Valid AuditEventSearchCriteria criteria)
        throws Exception
    {
        Who who = who();
        Specification<AuditEvent> filter = filter(criteria, who);
        Pageable page = page(criteria);
        return readOnly.execute(status -> {
            Page<AuditEvent> events = auditEventRepository.findAll(filter, page);
            return new PageImpl<AuditEventDTO>(present(events.getContent(), who), page, events.getTotalElements());
        });
    }

    /**
     * @see bw.co.starsign.audit.AuditTrailService#findByWorkflow(String)
     */
    @Override
    protected List<AuditEventDTO> handleFindByWorkflow(String workflowId)
        throws Exception
    {
        AuditEventSearchCriteria criteria = new AuditEventSearchCriteria();
        criteria.setWorkflowId(workflowId.trim());
        return timeline(criteria);
    }

    /**
     * @see bw.co.starsign.audit.AuditTrailService#findByDocument(String)
     */
    @Override
    protected List<AuditEventDTO> handleFindByDocument(String documentId)
        throws Exception
    {
        AuditEventSearchCriteria criteria = new AuditEventSearchCriteria();
        criteria.setDocumentId(documentId.trim());
        return timeline(criteria);
    }

    /**
     * @see bw.co.starsign.audit.AuditTrailService#verifyChain(String, Long, Long)
     */
    @Override
    protected AuditChainVerificationDTO handleVerifyChain(String chainKey, Long fromSequence, Long toSequence)
        throws Exception
    {
        String key = chainKey.trim();
        Who who = who();
        checkChainAccess(key, who);
        if (fromSequence != null && toSequence != null && fromSequence > toSequence)
        {
            throw new AuditTrailServiceException("The first sequence number cannot be after the last one");
        }

        return required.execute(status -> {
            AuditChainVerificationDTO result = new AuditChainVerificationDTO();
            result.setChainKey(key);
            result.setVerifiedAt(LocalDateTime.now());

            Optional<AuditEvent> head = auditEventRepository.findTopByChainKeyOrderBySequenceNumberDesc(key);
            if (head.isEmpty())
            {
                result.setFromSequence(fromSequence);
                result.setToSequence(toSequence);
                result.setCheckedEvents(0L);
                result.setValid(true);
                result.setMessage("The audit chain has no events");
                return result;
            }
            long from = Math.max(1L, fromSequence == null ? 1L : fromSequence);
            long to = Math.min(head.get().getSequenceNumber(), toSequence == null ? Long.MAX_VALUE : toSequence);
            if (from > to)
            {
                throw new AuditTrailServiceException("The audit chain ends at event " + head.get().getSequenceNumber());
            }

            ChainCheck check = check(key, from, to);
            result.setFromSequence(from);
            result.setToSequence(to);
            result.setCheckedEvents(check.checked());
            result.setValid(check.valid());
            result.setFirstInvalidSequence(check.firstInvalidSequence());
            String message = check.valid()
                    ? "All " + check.checked() + " events are intact and correctly linked"
                    : check.message();

            if (check.valid())
            {
                Optional<AuditLogIntegrityRecord> seal = auditLogIntegrityRecordRepository
                        .findTopByChainKeyAndToSequenceBetweenOrderByToSequenceDesc(key, from, to);
                if (seal.isPresent())
                {
                    SealCheck sealCheck = checkSeal(seal.get());
                    result.setLastSealVerified(sealCheck.valid());
                    if (Boolean.FALSE.equals(sealCheck.valid()))
                    {
                        // The links are intact but do not match the signed checkpoint: the chain was rewritten
                        result.setValid(false);
                        result.setFirstInvalidSequence(seal.get().getFromSequence());
                    }
                    message = message + "; seal up to event " + seal.get().getToSequence() + ": " + sealCheck.message();
                }
            }
            result.setMessage(message);

            AuditEventDTO audit = new AuditEventDTO();
            audit.setEventType(Boolean.TRUE.equals(result.getValid()) ? AuditEventType.INTEGRITY_VERIFIED : AuditEventType.INTEGRITY_FAILED);
            audit.setOutcome(Boolean.TRUE.equals(result.getValid()) ? AuditOutcome.SUCCESS : AuditOutcome.FAILURE);
            audit.setEntityType("AuditChain");
            audit.setEntityId(key);
            audit.setDetails(details(Map.of(
                    "fromSequence", from,
                    "toSequence", to,
                    "checkedEvents", check.checked(),
                    "message", message)));
            append(audit, key, who);
            return result;
        });
    }

    /**
     * @see bw.co.starsign.audit.AuditTrailService#seal(String)
     */
    @Override
    protected AuditLogIntegrityRecordDTO handleSeal(String chainKey)
        throws Exception
    {
        String key = chainKey.trim();
        Who who = who();
        checkChainAccess(key, who);

        return required.execute(status -> {
            // No appends while the range is checked and sealed
            auditEventRepository.lockChain(key);
            AuditEvent head = auditEventRepository.findTopByChainKeyOrderBySequenceNumberDesc(key)
                    .orElseThrow(() -> AuditTrailServiceException.notFound("The audit chain " + key + " has no events"));
            Optional<AuditLogIntegrityRecord> previous = auditLogIntegrityRecordRepository.findTopByChainKeyOrderByToSequenceDesc(key);
            long from = previous.map(seal -> seal.getToSequence() + 1).orElse(1L);
            long to = head.getSequenceNumber();
            if (from > to)
            {
                throw new AuditTrailServiceException("All events of the audit chain are already sealed");
            }

            ChainCheck check = check(key, from, to);
            if (!check.valid())
            {
                throw new AuditTrailServiceException("The audit chain cannot be sealed: " + check.message());
            }

            LocalDateTime now = LocalDateTime.now();
            AuditLogIntegrityRecord seal = AuditLogIntegrityRecord.Factory.newInstance();
            seal.setChainKey(key);
            seal.setFromSequence(from);
            seal.setToSequence(to);
            seal.setEventCount(to - from + 1);
            seal.setLastEventHash(head.getEventHash());
            seal.setSealHash(sealHash(key, from, to, to - from + 1, head.getEventHash(),
                    previous.map(AuditLogIntegrityRecord::getSealHash).orElse(AuditHashing.GENESIS_HASH)));
            seal.setSignature(sealSigner.sign(seal.getSealHash()));
            seal.setSigningKeyId(sealSigner.keyId());
            seal.setSealedAt(now);
            seal.setVerifiedAt(now);
            seal.setVerificationResult("VALID");
            seal.setCreatedAt(now);
            seal.setCreatedBy(StringUtils.defaultIfBlank(who.userId(), "system"));
            return auditLogIntegrityRecordMapper.toAuditLogIntegrityRecordDTO(auditLogIntegrityRecordRepository.save(seal));
        });
    }

    /**
     * Builds the evidence package (audit events as JSON and CSV, chain proof, final signed PDF when there
     * is one) as a ZIP and stores it in the AUDIT_EXPORTS namespace. The package is generated right away;
     * a failure is kept on the export record (status FAILED) so it can be seen and requested again.
     *
     * @see bw.co.starsign.audit.AuditTrailService#requestEvidenceExport(String)
     */
    @Override
    protected AuditEvidenceExportDTO handleRequestEvidenceExport(String workflowId)
        throws Exception
    {
        Who who = who();
        LocalDateTime requestedAt = LocalDateTime.now();
        ExportSubject subject = required.execute(status -> {
            SigningWorkflow workflow = loadWorkflow(workflowId, who);
            Organisation organisation = workflow.getOrganisation();
            AuditEvidenceExport export = AuditEvidenceExport.Factory.newInstance();
            export.setWorkflowId(workflow.getId().toString());
            export.setOrganisationId(organisation.getId().toString());
            export.setStatus(AuditExportStatus.GENERATING);
            export.setRequestedBy(StringUtils.defaultIfBlank(who.userId(), "system"));
            export.setRequestedAt(requestedAt);
            export.setCreatedAt(requestedAt);
            export.setCreatedBy(export.getRequestedBy());
            export = auditEvidenceExportRepository.save(export);
            String finalObjectId = workflow.getFinalArtifact() != null && workflow.getFinalArtifact().getStorageReference() != null
                    ? workflow.getFinalArtifact().getStorageReference().getObjectId()
                    : null;
            return new ExportSubject(export.getId(), workflow.getId().toString(), organisation.getId().toString(),
                    organisation.getTenantId(), workflow.getReferenceNumber(), workflow.getTitle(), finalObjectId);
        });

        String objectId = null;
        String sha256 = null;
        String failure = null;
        try
        {
            byte[] bundle = buildEvidenceBundle(subject, who);
            sha256 = AuditHashing.sha256Hex(bundle);
            StorageWriteRequestDTO request = new StorageWriteRequestDTO();
            request.setOrganisationId(subject.organisationId());
            request.setNamespace(StorageNamespace.AUDIT_EXPORTS);
            request.setContentType("application/zip");
            request.setContentLength((long) bundle.length);
            request.setOriginalFileName("evidence-" + fileName(subject.reference(), subject.workflowId()) + ".zip");
            request.setExpectedSha256(sha256);
            request.setMetadata(new LinkedHashMap<>(Map.of("workflowId", subject.workflowId(), "kind", "evidence-export")));
            DocumentStorageReferenceDTO stored = storageService.store(request, new ByteArrayInputStream(bundle));
            objectId = stored.getObjectId();
        }
        catch (IOException | RuntimeException e)
        {
            logger.error("Evidence export of workflow {} failed", subject.workflowId(), e);
            failure = StringUtils.abbreviate(StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()), MAX_STRING);
        }

        final String storedObjectId = objectId;
        final String bundleSha256 = sha256;
        final String failureReason = failure;
        return required.execute(status -> {
            AuditEvidenceExport export = auditEvidenceExportRepository.findById(subject.exportId())
                    .orElseThrow(() -> AuditTrailServiceException.notFound("The evidence export does not exist"));
            LocalDateTime now = LocalDateTime.now();
            if (failureReason == null)
            {
                DocumentStorageReference reference = documentStorageReferenceRepository.findByObjectId(storedObjectId).orElse(null);
                export.setStorageReference(reference);
                export.setStatus(reference == null ? AuditExportStatus.FAILED : AuditExportStatus.READY);
                export.setFailureReason(reference == null ? "The stored evidence package could not be found" : null);
                export.setSha256(bundleSha256);
                export.setGeneratedAt(now);
                export.setExpiresAt(now.plusDays(EXPORT_VALID_DAYS));
            }
            else
            {
                export.setStatus(AuditExportStatus.FAILED);
                export.setFailureReason(failureReason);
            }
            export.setModifiedAt(now);
            export.setModifiedBy(export.getRequestedBy());
            export = auditEvidenceExportRepository.save(export);

            AuditEventDTO audit = new AuditEventDTO();
            audit.setEventType(AuditEventType.AUDIT_EXPORTED);
            audit.setOutcome(export.getStatus() == AuditExportStatus.READY ? AuditOutcome.SUCCESS : AuditOutcome.FAILURE);
            audit.setOrganisationId(subject.organisationId());
            audit.setWorkflowId(subject.workflowId());
            audit.setEntityType("AuditEvidenceExport");
            audit.setEntityId(export.getId().toString());
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("action", "generate");
            details.put("sha256", bundleSha256);
            details.put("failureReason", export.getFailureReason());
            audit.setDetails(details(details));
            append(audit, chainKeyOf(subject.tenantId()), who);
            return auditEvidenceExportMapper.toAuditEvidenceExportDTO(export);
        });
    }

    /**
     * @see bw.co.starsign.audit.AuditTrailService#getEvidenceExport(String)
     */
    @Override
    protected AuditEvidenceExportDTO handleGetEvidenceExport(String workflowId)
        throws Exception
    {
        Who who = who();
        return required.execute(status -> {
            SigningWorkflow workflow = loadWorkflow(workflowId, who);
            AuditEvidenceExport export = auditEvidenceExportRepository
                    .findTopByWorkflowIdOrderByRequestedAtDesc(workflow.getId().toString())
                    .orElseThrow(() -> AuditTrailServiceException.notFound("No evidence export has been requested for this workflow"));
            return auditEvidenceExportMapper.toAuditEvidenceExportDTO(expireIfDue(export));
        });
    }

    /**
     * @see bw.co.starsign.audit.AuditTrailService#downloadEvidenceExport(String)
     */
    @Override
    protected Resource handleDownloadEvidenceExport(String workflowId)
        throws Exception
    {
        Who who = who();
        String objectId = required.execute(status -> {
            SigningWorkflow workflow = loadWorkflow(workflowId, who);
            AuditEvidenceExport export = auditEvidenceExportRepository
                    .findTopByWorkflowIdOrderByRequestedAtDesc(workflow.getId().toString())
                    .orElseThrow(() -> AuditTrailServiceException.notFound("No evidence export has been requested for this workflow"));
            export = expireIfDue(export);
            switch (export.getStatus())
            {
                case READY:
                    break;
                case EXPIRED:
                    throw new AuditTrailServiceException("The evidence export has expired; request a new one");
                case FAILED:
                    throw new AuditTrailServiceException("The evidence export failed; request a new one");
                default:
                    throw new AuditTrailServiceException("The evidence export is not ready yet");
            }
            if (export.getStorageReference() == null)
            {
                throw new AuditTrailServiceException("The evidence export has no stored package; request a new one");
            }

            AuditEventDTO audit = new AuditEventDTO();
            audit.setEventType(AuditEventType.AUDIT_EXPORTED);
            audit.setOrganisationId(export.getOrganisationId());
            audit.setWorkflowId(export.getWorkflowId());
            audit.setEntityType("AuditEvidenceExport");
            audit.setEntityId(export.getId().toString());
            audit.setDetails(details(Map.of("action", "download", "sha256", StringUtils.defaultString(export.getSha256()))));
            append(audit, chainKeyOf(workflow.getOrganisation().getTenantId()), who);
            return export.getStorageReference().getObjectId();
        });
        return storageService.asResource(objectId);
    }

    /**
     * @see bw.co.starsign.audit.AuditTrailService#exportCsv(@Valid AuditEventSearchCriteria)
     */
    @Override
    protected Resource handleExportCsv(@Valid AuditEventSearchCriteria criteria)
        throws Exception
    {
        Who who = who();
        Specification<AuditEvent> filter = filter(criteria, who);
        List<AuditEventDTO> events = readOnly.execute(status -> present(auditEventRepository.findAll(filter,
                PageRequest.of(0, MAX_EXPORT_ROWS, Sort.by("occurredAt", "sequenceNumber"))).getContent(), who));

        AuditEventDTO audit = new AuditEventDTO();
        audit.setEventType(AuditEventType.AUDIT_EXPORTED);
        audit.setEntityType("AuditEvent");
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("action", "csv");
        details.put("rows", events.size());
        details.put("workflowId", criteria.getWorkflowId());
        details.put("documentId", criteria.getDocumentId());
        details.put("from", criteria.getFrom() == null ? null : criteria.getFrom().toString());
        details.put("to", criteria.getTo() == null ? null : criteria.getTo().toString());
        audit.setDetails(details(details));
        required.execute(status -> append(audit, resolveChainKey(audit, who), who));

        return new NamedResource(toCsv(events), "audit-events-" + FILE_STAMP.format(LocalDateTime.now()) + ".csv");
    }

    // ------------------------------------------------------------------ appending

    /**
     * Appends one event to the chain under the chain's advisory lock. Must run inside a transaction.
     */
    private AuditEvent append(AuditEventDTO event, String chainKey, Who who)
    {
        LocalDateTime now = LocalDateTime.now();
        AuditEvent entity = AuditEvent.Factory.newInstance();
        entity.setEventType(event.getEventType());
        entity.setOccurredAt(AuditHashing.truncate(event.getOccurredAt() == null ? now : event.getOccurredAt()));
        entity.setOrganisationId(clip(StringUtils.defaultIfBlank(event.getOrganisationId(),
                who.tenantId() != null ? who.organisationId() : null)));
        entity.setActorType(event.getActorType() != null ? event.getActorType()
                : !who.signedIn() ? ActorType.SYSTEM
                : who.tenantId() == null ? ActorType.PLATFORM_ADMIN
                : ActorType.ORGANISATION_USER);
        entity.setActorId(clip(StringUtils.defaultIfBlank(event.getActorId(), who.userId())));
        entity.setActorDisplayName(clip(event.getActorDisplayName()));
        entity.setAuthenticationMethod(event.getAuthenticationMethod() != null ? event.getAuthenticationMethod()
                : who.signedIn() ? AuthenticationMethod.KEYCLOAK_PASSWORD : AuthenticationMethod.NONE);
        entity.setIpAddress(clip(event.getIpAddress()));
        entity.setUserAgent(clip(event.getUserAgent()));
        entity.setOutcome(event.getOutcome() == null ? AuditOutcome.SUCCESS : event.getOutcome());
        entity.setDocumentId(clip(event.getDocumentId()));
        entity.setWorkflowId(clip(event.getWorkflowId()));
        entity.setSignatoryId(clip(event.getSignatoryId()));
        entity.setEntityType(clip(event.getEntityType()));
        entity.setEntityId(clip(event.getEntityId()));
        entity.setDocumentSha256(clip(event.getDocumentSha256()));
        entity.setCorrelationId(clip(event.getCorrelationId()));
        entity.setDetails(AuditHashing.normaliseDetails(event.getDetails()));
        entity.setCreatedAt(now);
        entity.setCreatedBy(StringUtils.defaultIfBlank(entity.getActorId(), "system"));

        auditEventRepository.lockChain(chainKey);
        Optional<AuditEvent> head = auditEventRepository.findTopByChainKeyOrderBySequenceNumberDesc(chainKey);
        entity.setChainKey(chainKey);
        entity.setSequenceNumber(head.map(last -> last.getSequenceNumber() + 1).orElse(1L));
        entity.setPreviousHash(head.map(AuditEvent::getEventHash).orElse(AuditHashing.GENESIS_HASH));
        entity.setEventHash(AuditHashing.eventHash(entity));
        return auditEventRepository.save(entity);
    }

    /**
     * The chain is the tenant of the event's organisation; events without one go to the caller's tenant,
     * or to the platform chain.
     */
    private String resolveChainKey(AuditEventDTO event, Who who)
    {
        if (StringUtils.isNotBlank(event.getOrganisationId()))
        {
            UUID organisationId = uuidOrNull(event.getOrganisationId());
            Optional<String> tenant = organisationId == null ? Optional.empty()
                    : organisationRepository.findById(organisationId).map(Organisation::getTenantId).filter(StringUtils::isNotBlank);
            if (tenant.isPresent())
            {
                return tenant.get();
            }
        }
        if (who.tenantId() != null)
        {
            return who.tenantId();
        }
        return StringUtils.isNotBlank(event.getChainKey()) ? event.getChainKey().trim() : PLATFORM_CHAIN;
    }

    private static String chainKeyOf(String tenantId)
    {
        return StringUtils.isBlank(tenantId) ? PLATFORM_CHAIN : tenantId;
    }

    // ------------------------------------------------------------------ verification

    /**
     * Recomputes every hash in [from, to] and checks the links, including the link to event from - 1.
     */
    private ChainCheck check(String chainKey, long from, long to)
    {
        String expectedPrevious;
        if (from == 1)
        {
            expectedPrevious = AuditHashing.GENESIS_HASH;
        }
        else
        {
            Optional<AuditEvent> before = auditEventRepository.findByChainKeyAndSequenceNumber(chainKey, from - 1);
            if (before.isEmpty())
            {
                return new ChainCheck(false, from - 1, 0, "Event " + (from - 1) + " is missing from the chain");
            }
            expectedPrevious = before.get().getEventHash();
        }

        long expectedSequence = from;
        long checked = 0;
        while (expectedSequence <= to)
        {
            long batchEnd = Math.min(to, expectedSequence + VERIFY_BATCH - 1);
            List<AuditEvent> batch = auditEventRepository
                    .findByChainKeyAndSequenceNumberBetweenOrderBySequenceNumberAsc(chainKey, expectedSequence, batchEnd);
            for (AuditEvent event : batch)
            {
                if (event.getSequenceNumber() != expectedSequence)
                {
                    return new ChainCheck(false, expectedSequence, checked, "Event " + expectedSequence + " is missing from the chain");
                }
                if (!expectedPrevious.equals(event.getPreviousHash()))
                {
                    return new ChainCheck(false, expectedSequence, checked,
                            "Event " + expectedSequence + " does not link to the event before it");
                }
                if (!AuditHashing.eventHash(event).equals(event.getEventHash()))
                {
                    return new ChainCheck(false, expectedSequence, checked, "Event " + expectedSequence + " has been altered");
                }
                expectedPrevious = event.getEventHash();
                expectedSequence++;
                checked++;
                // Events are never changed; keep long chains from filling the persistence context
                entityManager.detach(event);
            }
            if (expectedSequence <= batchEnd)
            {
                return new ChainCheck(false, expectedSequence, checked, "Event " + expectedSequence + " is missing from the chain");
            }
        }
        return new ChainCheck(true, null, checked, null);
    }

    /**
     * Recomputes the seal hash, compares it with the sealed event and checks the signature. Records the
     * outcome on the seal.
     */
    private SealCheck checkSeal(AuditLogIntegrityRecord seal)
    {
        String previousSealHash = seal.getFromSequence() <= 1 ? AuditHashing.GENESIS_HASH
                : auditLogIntegrityRecordRepository
                        .findTopByChainKeyAndToSequenceOrderBySealedAtDesc(seal.getChainKey(), seal.getFromSequence() - 1)
                        .map(AuditLogIntegrityRecord::getSealHash)
                        .orElse(null);
        String sealedEventHash = auditEventRepository.findByChainKeyAndSequenceNumber(seal.getChainKey(), seal.getToSequence())
                .map(AuditEvent::getEventHash)
                .orElse(null);

        Boolean valid;
        String message;
        if (previousSealHash == null)
        {
            valid = false;
            message = "the seal before it is missing";
        }
        else if (!seal.getLastEventHash().equals(sealedEventHash))
        {
            valid = false;
            message = "the sealed event no longer matches";
        }
        else if (!sealHash(seal.getChainKey(), seal.getFromSequence(), seal.getToSequence(), seal.getEventCount(),
                seal.getLastEventHash(), previousSealHash).equals(seal.getSealHash()))
        {
            valid = false;
            message = "the seal has been altered";
        }
        else
        {
            try
            {
                valid = sealSigner.verify(seal.getSealHash(), seal.getSignature(), seal.getSigningKeyId());
                message = valid ? "valid" : "the signature does not match the current signing key";
            }
            catch (AuditTrailServiceException e)
            {
                logger.warn("Seal signature of chain {} could not be checked: {}", seal.getChainKey(), e.getMessage());
                valid = null;
                message = "hash valid, signature not checked (" + e.getMessage() + ")";
            }
        }
        seal.setVerifiedAt(LocalDateTime.now());
        seal.setVerificationResult(StringUtils.abbreviate(Boolean.TRUE.equals(valid) ? "VALID"
                : valid == null ? "UNCHECKED: " + message : "INVALID: " + message, MAX_STRING));
        auditLogIntegrityRecordRepository.save(seal);
        return new SealCheck(valid, message);
    }

    private static String sealHash(String chainKey, long from, long to, long count, String lastEventHash, String previousSealHash)
    {
        return AuditHashing.sha256Hex(String.join("|", chainKey, Long.toString(from), Long.toString(to),
                Long.toString(count), lastEventHash, previousSealHash));
    }

    // ------------------------------------------------------------------ evidence

    private byte[] buildEvidenceBundle(ExportSubject subject, Who who) throws IOException
    {
        String chainKey = chainKeyOf(subject.tenantId());
        Specification<AuditEvent> ofWorkflow = (root, query, cb) -> cb.and(
                cb.equal(root.get("chainKey"), chainKey),
                cb.equal(root.get("workflowId"), subject.workflowId()));

        Map<String, Object> proof = new LinkedHashMap<>();
        List<AuditEventDTO> events = readOnly.execute(status -> {
            List<AuditEvent> found = auditEventRepository.findAll(ofWorkflow, Sort.by("sequenceNumber"));
            List<AuditEventDTO> dtos = auditEventMapper.toAuditEventDTOCollection(found);
            List<Map<String, Object>> links = new ArrayList<>();
            for (AuditEvent event : found)
            {
                Map<String, Object> link = new LinkedHashMap<>();
                link.put("sequenceNumber", event.getSequenceNumber());
                link.put("previousHash", event.getPreviousHash());
                link.put("eventHash", event.getEventHash());
                link.put("canonical", AuditHashing.canonicalEvent(event));
                links.add(link);
            }
            proof.put("chainKey", chainKey);
            proof.put("algorithm", "eventHash = SHA-256(previousHash || canonical JSON); genesis previousHash = 64 zeros");
            proof.put("events", links);
            if (!found.isEmpty())
            {
                long first = found.get(0).getSequenceNumber();
                long last = found.get(found.size() - 1).getSequenceNumber();
                ChainCheck check = check(chainKey, first, last);
                Map<String, Object> verification = new LinkedHashMap<>();
                verification.put("fromSequence", first);
                verification.put("toSequence", last);
                verification.put("checkedEvents", check.checked());
                verification.put("valid", check.valid());
                verification.put("firstInvalidSequence", check.firstInvalidSequence());
                verification.put("message", check.message());
                verification.put("verifiedAt", LocalDateTime.now().toString());
                proof.put("verification", verification);
                auditLogIntegrityRecordRepository.findTopByChainKeyAndToSequenceBetweenOrderByToSequenceDesc(chainKey, last, Long.MAX_VALUE)
                        .or(() -> auditLogIntegrityRecordRepository.findTopByChainKeyOrderByToSequenceDesc(chainKey))
                        .map(auditLogIntegrityRecordMapper::toAuditLogIntegrityRecordDTO)
                        .ifPresent(seal -> proof.put("seal", seal));
            }
            return dtos;
        });

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Map<String, String> files = new LinkedHashMap<>();
        try (ZipOutputStream zip = new ZipOutputStream(buffer))
        {
            files.put("audit-events.json", entry(zip, "audit-events.json", json.writeValueAsBytes(events)));
            files.put("audit-events.csv", entry(zip, "audit-events.csv", toCsv(events)));
            files.put("chain-proof.json", entry(zip, "chain-proof.json", json.writeValueAsBytes(proof)));
            if (subject.finalObjectId() != null)
            {
                try (InputStream pdf = storageService.openStream(subject.finalObjectId()))
                {
                    files.put("final-document.pdf", entry(zip, "final-document.pdf", pdf.readAllBytes()));
                }
            }
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("workflowId", subject.workflowId());
            manifest.put("referenceNumber", subject.reference());
            manifest.put("title", subject.title());
            manifest.put("organisationId", subject.organisationId());
            manifest.put("generatedAt", LocalDateTime.now().toString());
            manifest.put("generatedBy", StringUtils.defaultIfBlank(who.userId(), "system"));
            manifest.put("eventCount", events.size());
            manifest.put("sha256", files);
            entry(zip, "manifest.json", json.writeValueAsBytes(manifest));
        }
        return buffer.toByteArray();
    }

    private static String entry(ZipOutputStream zip, String name, byte[] content) throws IOException
    {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
        return AuditHashing.sha256Hex(content);
    }

    private AuditEvidenceExport expireIfDue(AuditEvidenceExport export)
    {
        if (export.getStatus() == AuditExportStatus.READY && export.getExpiresAt() != null
                && export.getExpiresAt().isBefore(LocalDateTime.now()))
        {
            export.setStatus(AuditExportStatus.EXPIRED);
            export.setModifiedAt(LocalDateTime.now());
            export.setModifiedBy("system");
            return auditEvidenceExportRepository.save(export);
        }
        return export;
    }

    private SigningWorkflow loadWorkflow(String workflowId, Who who)
    {
        UUID id = uuidOrNull(workflowId);
        SigningWorkflow workflow = id == null ? null : signingWorkflowRepository.findById(id).orElse(null);
        // Another tenant's workflow is reported as missing, so its existence is not revealed
        if (workflow == null || (who.tenantId() != null && !who.tenantId().equals(workflow.getOrganisation().getTenantId())))
        {
            throw AuditTrailServiceException.notFound("The workflow does not exist");
        }
        return workflow;
    }

    // ------------------------------------------------------------------ searching

    private List<AuditEventDTO> timeline(AuditEventSearchCriteria criteria)
    {
        Who who = who();
        Specification<AuditEvent> filter = filter(criteria, who);
        return readOnly.execute(status -> present(auditEventRepository.findAll(filter,
                Sort.by("occurredAt", "sequenceNumber")), who));
    }

    private static Specification<AuditEvent> filter(AuditEventSearchCriteria criteria, Who who)
    {
        if (criteria.getFrom() != null && criteria.getTo() != null && criteria.getFrom().isAfter(criteria.getTo()))
        {
            throw new AuditTrailServiceException("The start of the period cannot be after its end");
        }
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            // Tenant isolation: organisation users only ever see their own tenant's chain
            if (who.tenantId() != null)
            {
                predicates.add(cb.equal(root.get("chainKey"), who.tenantId()));
            }
            if (StringUtils.isNotBlank(criteria.getOrganisationId()))
            {
                predicates.add(cb.equal(root.get("organisationId"), criteria.getOrganisationId().trim()));
            }
            if (criteria.getEventTypes() != null && !criteria.getEventTypes().isEmpty())
            {
                predicates.add(root.get("eventType").in(criteria.getEventTypes()));
            }
            if (StringUtils.isNotBlank(criteria.getActorId()))
            {
                predicates.add(cb.equal(root.get("actorId"), criteria.getActorId().trim()));
            }
            if (StringUtils.isNotBlank(criteria.getDocumentId()))
            {
                predicates.add(cb.equal(root.get("documentId"), criteria.getDocumentId().trim()));
            }
            if (StringUtils.isNotBlank(criteria.getWorkflowId()))
            {
                predicates.add(cb.equal(root.get("workflowId"), criteria.getWorkflowId().trim()));
            }
            if (criteria.getOutcome() != null)
            {
                predicates.add(cb.equal(root.get("outcome"), criteria.getOutcome()));
            }
            if (criteria.getFrom() != null)
            {
                predicates.add(cb.greaterThanOrEqualTo(root.<LocalDateTime>get("occurredAt"), criteria.getFrom()));
            }
            if (criteria.getTo() != null)
            {
                predicates.add(cb.lessThanOrEqualTo(root.<LocalDateTime>get("occurredAt"), criteria.getTo()));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private static Pageable page(AuditEventSearchCriteria criteria)
    {
        int number = criteria.getPageNumber() == null ? 0 : Math.max(0, criteria.getPageNumber());
        int size = criteria.getPageSize() == null || criteria.getPageSize() < 1 ? DEFAULT_PAGE_SIZE
                : Math.min(MAX_PAGE_SIZE, criteria.getPageSize());
        String field = StringUtils.isNotBlank(criteria.getSortField()) && SORT_FIELDS.contains(criteria.getSortField().trim())
                ? criteria.getSortField().trim() : "occurredAt";
        Sort.Direction direction = criteria.getSortDirection() == SortDirection.ASC ? Sort.Direction.ASC : Sort.Direction.DESC;
        return PageRequest.of(number, size, Sort.by(direction, field).and(Sort.by(direction, "sequenceNumber")));
    }

    /**
     * Platform administrators see other tenants' events without details, network data or hashes of
     * documents: they get the facts of what happened, not the tenant's content.
     */
    private List<AuditEventDTO> present(List<AuditEvent> events, Who who)
    {
        List<AuditEventDTO> dtos = auditEventMapper.toAuditEventDTOCollection(events);
        if (who.platformAdministrator())
        {
            for (AuditEventDTO dto : dtos)
            {
                if (!PLATFORM_CHAIN.equals(dto.getChainKey()))
                {
                    dto.setDetails(null);
                    dto.setIpAddress(null);
                    dto.setUserAgent(null);
                    dto.setDocumentSha256(null);
                }
            }
        }
        return dtos;
    }

    private static byte[] toCsv(List<AuditEventDTO> events)
    {
        CsvWriter csv = new CsvWriter().row("Chain", "Sequence", "Occurred at", "Event type", "Outcome", "Organisation",
                "Actor type", "Actor id", "Actor name", "Authentication", "IP address", "Document", "Workflow",
                "Signatory", "Entity type", "Entity id", "Document SHA-256", "Correlation id", "Details",
                "Previous hash", "Event hash");
        for (AuditEventDTO e : events)
        {
            csv.row(e.getChainKey(), e.getSequenceNumber(), e.getOccurredAt(), e.getEventType(), e.getOutcome(),
                    e.getOrganisationId(), e.getActorType(), e.getActorId(), e.getActorDisplayName(),
                    e.getAuthenticationMethod(), e.getIpAddress(), e.getDocumentId(), e.getWorkflowId(),
                    e.getSignatoryId(), e.getEntityType(), e.getEntityId(), e.getDocumentSha256(),
                    e.getCorrelationId(), e.getDetails(), e.getPreviousHash(), e.getEventHash());
        }
        return csv.toBytes();
    }

    // ------------------------------------------------------------------ helpers

    private void checkChainAccess(String chainKey, Who who)
    {
        if (who.tenantId() != null && !who.tenantId().equals(chainKey))
        {
            throw new AuditTrailServiceException("You can only use your own organisation's audit trail");
        }
    }

    /**
     * The signed-in caller. Audit events are also written where nobody is signed in (scheduled jobs,
     * signing links); those run as the system, so the missing sign-in is expected here, not an error.
     */
    private Who who()
    {
        String userId;
        try
        {
            userId = caller.userId();
        }
        catch (RuntimeException notSignedIn)
        {
            logger.trace("Auditing as the system: {}", notSignedIn.getMessage());
            return Who.SYSTEM;
        }
        return new Who(userId, caller.tenantId().orElse(null), caller.organisationId().orElse(null), true);
    }

    private String details(Map<String, ?> values)
    {
        try
        {
            return json.writeValueAsString(values);
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException e)
        {
            throw new AuditTrailServiceException("The audit details could not be written", e);
        }
    }

    private static String clip(String value)
    {
        return StringUtils.isBlank(value) ? null : StringUtils.left(value.trim(), MAX_STRING);
    }

    private static UUID uuidOrNull(String id)
    {
        try
        {
            return id == null ? null : UUID.fromString(id.trim());
        }
        catch (IllegalArgumentException e)
        {
            return null;
        }
    }

    private static String fileName(String reference, String fallback)
    {
        String name = StringUtils.defaultIfBlank(reference, fallback);
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private record Who(String userId, String tenantId, String organisationId, boolean signedIn)
    {
        static final Who SYSTEM = new Who(null, null, null, false);

        boolean platformAdministrator()
        {
            return signedIn && tenantId == null;
        }
    }

    private record ChainCheck(boolean valid, Long firstInvalidSequence, long checked, String message)
    {
    }

    private record SealCheck(Boolean valid, String message)
    {
    }

    private record ExportSubject(UUID exportId, String workflowId, String organisationId, String tenantId,
            String reference, String title, String finalObjectId)
    {
    }

    /** In-memory download with a file name, so controllers can set Content-Disposition. */
    static final class NamedResource extends ByteArrayResource
    {
        private final String fileName;

        NamedResource(byte[] content, String fileName)
        {
            super(content);
            this.fileName = fileName;
        }

        @Override
        public String getFilename()
        {
            return fileName;
        }

        @Override
        public boolean equals(Object other)
        {
            return super.equals(other) && other instanceof NamedResource named && fileName.equals(named.fileName);
        }

        @Override
        public int hashCode()
        {
            return 31 * super.hashCode() + fileName.hashCode();
        }
    }
}
