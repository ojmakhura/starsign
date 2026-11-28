// license-header java merge-point
/**
 * This is only generated once! It will never be overwritten.
 * You can (and have to!) safely modify it by hand.
 * TEMPLATE:    SpringServiceImpl.vsl in andromda-spring cartridge
 * MODEL CLASS: AndroMDAModel::backend::bw.co.starsign::document::DocumentService
 * STEREOTYPE:  Service
 */
package bw.co.starsign.document;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.Resource;
import bw.co.starsign.common.Pages;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import bw.co.starsign.audit.AuditEventType;
import bw.co.starsign.audit.AuditOutcome;
import bw.co.starsign.audit.AuditTrailService;
import bw.co.starsign.common.SortDirection;
import bw.co.starsign.context.CallerContext;
import bw.co.starsign.identity.OrganisationUser;
import bw.co.starsign.identity.OrganisationUserRepository;
import bw.co.starsign.identity.UserStatus;
import bw.co.starsign.messaging.OutboxEventDTO;
import bw.co.starsign.messaging.OutboxService;
import bw.co.starsign.organisation.Organisation;
import bw.co.starsign.organisation.OrganisationRepository;
import bw.co.starsign.organisation.OrganisationSigningPolicy;
import bw.co.starsign.organisation.OrganisationStatus;
import bw.co.starsign.storage.StorageNamespace;
import bw.co.starsign.storage.StorageService;
import bw.co.starsign.subscription.SubscriptionService;
import bw.co.starsign.subscription.UsageMetric;
import bw.co.starsign.workflow.Signatory;
import bw.co.starsign.workflow.SigningWorkflow;
import bw.co.starsign.workflow.WorkflowStatus;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import jakarta.validation.Valid;

/**
 * Documents of the caller's organisation. Every operation is limited to the caller's tenant: a document of
 * another tenant is reported as not existing.
 * <p>
 * Transactions are opened here explicitly: the generated base declares the public methods, so a
 * class-level {@code @Transactional} on this subclass would not apply to them.
 *
 * @see bw.co.starsign.document.DocumentService
 */
@Service("documentService")
public class DocumentServiceImpl
    extends DocumentServiceBase
{
    private static final long MB = 1024L * 1024L;
    private static final int DEFAULT_UPLOAD_LIMIT_MB = 25;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_TAG_LENGTH = 50;
    private static final Pattern REFERENCE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9/._-]{0,99}$");
    private static final DateTimeFormatter REFERENCE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String REFERENCE_CHARACTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Browsers and HTTP clients label PDFs in different ways. */
    private static final Set<String> PDF_CONTENT_TYPES = Set.of(
            "application/pdf", "application/x-pdf", "application/acrobat", "application/octet-stream");

    private static final Set<DocumentStatus> ARCHIVABLE = Set.of(DocumentStatus.COMPLETED, DocumentStatus.DECLINED,
            DocumentStatus.CANCELLED, DocumentStatus.EXPIRED, DocumentStatus.DRAFT);
    private static final Set<DocumentStatus> REMOVABLE = Set.of(DocumentStatus.UPLOADED, DocumentStatus.DRAFT,
            DocumentStatus.IN_PREPARATION, DocumentStatus.READY_FOR_SIGNING);
    private static final Set<DocumentStatus> EDITABLE = Set.of(DocumentStatus.UPLOADED, DocumentStatus.DRAFT,
            DocumentStatus.IN_PREPARATION, DocumentStatus.READY_FOR_SIGNING);
    /** Statuses after which nothing changes any more, so retention can run out. */
    private static final Set<DocumentStatus> FINISHED = Set.of(DocumentStatus.COMPLETED, DocumentStatus.DECLINED,
            DocumentStatus.CANCELLED, DocumentStatus.EXPIRED, DocumentStatus.ARCHIVED);
    private static final Set<WorkflowStatus> ACTIVE_WORKFLOW = Set.of(WorkflowStatus.DRAFT, WorkflowStatus.READY,
            WorkflowStatus.IN_PROGRESS, WorkflowStatus.COMPLETING);
    private static final Set<WorkflowStatus> UNSENT_WORKFLOW = Set.of(WorkflowStatus.DRAFT, WorkflowStatus.READY);
    /** Artifacts listed with a version; page images are reached through the pages. */
    private static final Set<ArtifactType> PAGE_IMAGES = Set.of(ArtifactType.PAGE_PREVIEW, ArtifactType.THUMBNAIL);

    private static final Map<String, String> SORT_FIELDS = Map.of(
            "createdat", "createdAt",
            "modifiedat", "modifiedAt",
            "completedat", "completedAt",
            "title", "title",
            "referencenumber", "referenceNumber",
            "status", "status",
            "pagecount", "pageCount");

    private final CallerContext caller;
    private final OrganisationRepository organisationRepository;
    private final OrganisationUserRepository organisationUserRepository;
    private final DocumentProcessingService documentProcessingService;
    private final PdfInspector pdfInspector;
    private final DocumentArtifacts artifacts;
    private final TransactionTemplate read;
    private final TransactionTemplate write;

    public DocumentServiceImpl(
        @Lazy StorageService storageService,
        @Lazy AuditTrailService auditTrailService,
        @Lazy OutboxService outboxService,
        @Lazy SubscriptionService subscriptionService,
        DocumentRepository documentRepository,
        DocumentMapper documentMapper,
        DocumentVersionRepository documentVersionRepository,
        DocumentVersionMapper documentVersionMapper,
        DocumentArtifactRepository documentArtifactRepository,
        DocumentArtifactMapper documentArtifactMapper,
        DocumentPageRepository documentPageRepository,
        DocumentPageMapper documentPageMapper,
        DocumentMetadataRepository documentMetadataRepository,
        DocumentMetadataMapper documentMetadataMapper,
        @Lazy CallerContext caller,
        OrganisationRepository organisationRepository,
        OrganisationUserRepository organisationUserRepository,
        @Lazy DocumentProcessingService documentProcessingService,
        PdfInspector pdfInspector,
        DocumentArtifacts artifacts,
        PlatformTransactionManager transactionManager) {

        super(
            storageService,
            auditTrailService,
            outboxService,
            subscriptionService,
            documentRepository,
            documentMapper,
            documentVersionRepository,
            documentVersionMapper,
            documentArtifactRepository,
            documentArtifactMapper,
            documentPageRepository,
            documentPageMapper,
            documentMetadataRepository,
            documentMetadataMapper);
        this.caller = caller;
        this.organisationRepository = organisationRepository;
        this.organisationUserRepository = organisationUserRepository;
        this.documentProcessingService = documentProcessingService;
        this.pdfInspector = pdfInspector;
        this.artifacts = artifacts;
        this.read = new TransactionTemplate(transactionManager);
        this.read.setReadOnly(true);
        this.write = new TransactionTemplate(transactionManager);
    }

    /**
     * The file is checked and stored before the document is created, then processed (metadata, pages,
     * previews) once the document is committed; a failed processing leaves the document UPLOADED.
     *
     * @see bw.co.starsign.document.DocumentService#upload(MultipartFile, DocumentUploadDTO)
     */
    @Override
    protected DocumentDTO handleUpload(MultipartFile file, @Valid DocumentUploadDTO details)
        throws Exception
    {
        String tenantId = tenant();
        Organisation organisation = organisationRepository.findByTenantId(tenantId)
                .filter(found -> found.getDeletedAt() == null)
                .orElseThrow(() -> DocumentServiceException.notFound("Your organisation is not registered"));
        if (organisation.getStatus() != OrganisationStatus.ACTIVE)
        {
            throw new DocumentServiceException("Documents can only be uploaded once your organisation is active");
        }
        OrganisationUser owner = organisationUserRepository.findByKeycloakUserId(caller.userId())
                .filter(user -> user.getDeletedAt() == null && user.getOrganisation() != null
                        && organisation.getId().equals(user.getOrganisation().getId()))
                .orElseThrow(() -> new DocumentServiceException("Your user account is not part of the organisation"));
        if (owner.getStatus() != UserStatus.ACTIVE)
        {
            throw new DocumentServiceException("Your user account is not active");
        }

        List<String> errors = checkDetails(details);
        String fileName = fileName(file.getOriginalFilename());
        long limitMb = uploadLimitMb(organisation);
        if (file.isEmpty())
        {
            errors.add("Choose a PDF file to upload");
        }
        else if (file.getSize() > limitMb * MB)
        {
            errors.add("The file is larger than the " + limitMb + " MB your organisation allows");
        }
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".pdf")
                || (file.getContentType() != null && !PDF_CONTENT_TYPES.contains(baseType(file.getContentType()))))
        {
            errors.add("Only PDF files can be uploaded");
        }
        
        String reference = StringUtils.trimToNull(details.getReferenceNumber());
        if (reference != null && documentRepository.existsByReferenceNumberIgnoreCase(reference))
        {
            errors.add("Another document already has the reference number " + reference);
        }
        if (!errors.isEmpty())
        {
            throw new DocumentServiceException(String.join("; ", errors));
        }

        String organisationId = organisation.getId().toString();
        if (!subscriptionService.checkLimit(organisationId, UsageMetric.DOCUMENTS_UPLOADED, 1))
        {
            throw new DocumentServiceException(
                    "Your subscription's document limit has been reached: upgrade the plan to upload more documents");
        }
        if (!subscriptionService.checkLimit(organisationId, UsageMetric.STORAGE_BYTES, file.getSize()))
        {
            throw new DocumentServiceException(
                    "Your subscription's storage is full: upgrade the plan or archive documents to upload more");
        }

        byte[] content = file.getBytes();
        PdfValidationResultDTO validation = pdfInspector.inspect(content, fileName);
        if (!Boolean.TRUE.equals(validation.getValid()))
        {
            throw new DocumentServiceException("The file cannot be accepted: " + String.join("; ", validation.getIssues()));
        }

        UUID documentId = write.execute(status -> create(organisation.getId(), owner.getId(), details, reference,
                fileName, content, validation.getPageCount()));

        try
        {
            documentProcessingService.processUploadedVersion(documentId.toString(), 1);
        }
        catch (RuntimeException e)
        {
            // The upload itself succeeded and is kept: the document stays UPLOADED and is not signable
            logger.error("Processing of uploaded document {} failed", documentId, e);
            write.executeWithoutResult(status -> auditTrailService.record(artifacts.event(
                    AuditEventType.DOCUMENT_PROCESSED, documentRepository.findById(documentId).orElseThrow(),
                    AuditOutcome.FAILURE, Map.of("versionNumber", 1, "error", String.valueOf(e.getMessage())))));
        }
        return read.execute(status -> dto(documentRepository.findById(documentId).orElseThrow()));
    }

    /** Creates the document, its first version and ORIGINAL artifact; records audit, usage and the event. */
    private UUID create(UUID organisationId, UUID ownerId, DocumentUploadDTO details, String reference,
            String fileName, byte[] content, Integer pageCount)
    {
        Organisation organisation = organisationRepository.getReferenceById(organisationId);
        OrganisationUser owner = organisationUserRepository.getReferenceById(ownerId);
        String userId = caller.userId();
        LocalDateTime now = LocalDateTime.now();
        String sha256 = PdfInspector.sha256(content);

        Document document = Document.Factory.newInstance();
        document.setReferenceNumber(reference == null ? newReference() : reference);
        document.setTitle(details.getTitle().strip());
        document.setDescription(StringUtils.trimToNull(details.getDescription()));
        document.setStatus(DocumentStatus.UPLOADED);
        document.setOriginalFileName(fileName);
        document.setOriginalSha256(sha256);
        document.setFileSizeBytes(content.length);
        document.setPageCount(pageCount == null ? 0 : pageCount);
        document.setCurrentVersionNumber(1);
        document.setRetentionUntil(details.getRetentionUntil());
        document.setTags(tags(details.getTags()));
        document.setOrganisation(organisation);
        document.setOwner(owner);
        document.setCreatedAt(now);
        document.setCreatedBy(userId);

        DocumentVersion version = DocumentVersion.Factory.newInstance();
        version.setVersionNumber(1);
        version.setSha256(sha256);
        version.setReason("Original upload");
        version.setPageCount(document.getPageCount());
        version.setGeneratedBy(userId);
        version.setImmutable(true);
        version.setCreatedAt(now);
        version.setCreatedBy(userId);
        version.setDocument(document);
        document.addVersions(version);
        artifacts.store(version, ArtifactType.ORIGINAL, StorageNamespace.ORIGINALS, content, PdfInspector.PDF,
                fileName, null, true);

        Document saved = documentRepository.save(document);
        String documentId = saved.getId().toString();
        String orgId = organisationId.toString();

        Map<String, Object> audit = new LinkedHashMap<String, Object>();
        audit.put("referenceNumber", saved.getReferenceNumber());
        audit.put("fileName", fileName);
        audit.put("sizeBytes", content.length);
        audit.put("sha256", sha256);
        auditTrailService.record(artifacts.event(AuditEventType.DOCUMENT_UPLOADED, saved, AuditOutcome.SUCCESS, audit));
        subscriptionService.recordUsage(orgId, UsageMetric.DOCUMENTS_UPLOADED, 1, documentId);
        subscriptionService.recordUsage(orgId, UsageMetric.STORAGE_BYTES, content.length, documentId);

        OutboxEventDTO event = new OutboxEventDTO();
        event.setAggregateType("Document");
        event.setAggregateId(documentId);
        event.setEventType("DOCUMENT_PROCESSING");
        event.setOrganisationId(orgId);
        event.setRoutingKey("document.processing");
        event.setPayload(DocumentArtifacts.json(Map.of("documentId", documentId, "versionNumber", 1)));
        event.setIdempotencyKey("document-processing:" + documentId + ":1");
        outboxService.enqueue(event);
        return saved.getId();
    }

    /**
     * @see bw.co.starsign.document.DocumentService#findById(String)
     */
    @Override
    protected DocumentDTO handleFindById(String id)
        throws Exception
    {
        return read.execute(status -> dto(load(id)));
    }

    /**
     * Owner-scoped callers ({@code documents:list-own}) see only documents they own or sign.
     *
     * @see bw.co.starsign.document.DocumentService#search(DocumentSearchCriteria)
     */
    @Override
    protected List<DocumentListDTO> handleSearch(@Valid DocumentSearchCriteria criteria)
        throws Exception
    {
        Specification<Document> specification = searchSpecification(criteria);
        PageRequest request = searchRequest(criteria);
        return read.execute(status -> documentRepository.findListItems(specification, request, ACTIVE_WORKFLOW));
    }

    /**
     * @see bw.co.starsign.document.DocumentService#pagedSearch(DocumentSearchCriteria)
     */
    @Override
    protected Page<DocumentListDTO> handlePagedSearch(@Valid DocumentSearchCriteria criteria)
        throws Exception
    {
        Specification<Document> specification = searchSpecification(criteria);
        PageRequest request = searchRequest(criteria);
        return read.execute(status -> documentRepository.findListItemPage(specification, request, ACTIVE_WORKFLOW));
    }

    /** The search's specification, limited to the caller's tenant and, for owner-scoped callers, their own. */
    private Specification<Document> searchSpecification(DocumentSearchCriteria criteria)
    {
        boolean ownOnly = caller.ownRecordsOnly("documents:list");
        return specification(criteria, tenant(), ownOnly ? caller.userId() : null);
    }

    private static PageRequest searchRequest(DocumentSearchCriteria criteria)
    {
        String sortField = SORT_FIELDS.getOrDefault(
                StringUtils.defaultString(criteria.getSortField()).toLowerCase(Locale.ROOT), "createdAt");
        Sort.Direction direction = criteria.getSortDirection() == SortDirection.ASC ? Sort.Direction.ASC : Sort.Direction.DESC;
        return Pages.request(criteria, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE,
                Sort.by(direction, sortField).and(Sort.by("id")));
    }

    /**
     * @see bw.co.starsign.document.DocumentService#updateDetails(String, DocumentUploadDTO)
     */
    @Override
    protected DocumentDTO handleUpdateDetails(String id, @Valid DocumentUploadDTO details)
        throws Exception
    {
        return write.execute(status -> {
            Document document = load(id);
            if (document.getStatus() == DocumentStatus.ARCHIVED)
            {
                throw new DocumentServiceException("An archived document cannot be changed");
            }
            List<String> errors = checkDetails(details);
            String reference = StringUtils.trimToNull(details.getReferenceNumber());
            if (reference != null && !reference.equalsIgnoreCase(document.getReferenceNumber()))
            {
                if (!EDITABLE.contains(document.getStatus()))
                {
                    errors.add("The reference number cannot change once the document is sent for signing");
                }
                else if (documentRepository.existsByReferenceNumberIgnoreCase(reference))
                {
                    errors.add("Another document already has the reference number " + reference);
                }
            }
            if (!errors.isEmpty())
            {
                throw new DocumentServiceException(String.join("; ", errors));
            }

            document.setTitle(details.getTitle().strip());
            document.setDescription(StringUtils.trimToNull(details.getDescription()));
            if (reference != null)
            {
                document.setReferenceNumber(reference);
            }
            document.setRetentionUntil(details.getRetentionUntil());
            document.setTags(tags(details.getTags()));
            touch(document);
            return dto(documentRepository.save(document));
        });
    }

    /**
     * @see bw.co.starsign.document.DocumentService#getVersions(String)
     */
    @Override
    protected List<DocumentVersionDTO> handleGetVersions(String id)
        throws Exception
    {
        return read.execute(status -> {
            Document document = load(id);
            return document.getVersions().stream()
                    .sorted(Comparator.comparingInt(DocumentVersion::getVersionNumber))
                    .map(version -> versionDto(document, version))
                    .toList();
        });
    }

    /**
     * @see bw.co.starsign.document.DocumentService#getMetadata(String, Integer)
     */
    @Override
    protected DocumentMetadataDTO handleGetMetadata(String id, Integer versionNumber)
        throws Exception
    {
        return read.execute(status -> {
            DocumentVersion version = version(load(id), versionNumber);
            if (version.getMetadata() == null)
            {
                throw DocumentServiceException.notFound("The document has not been processed yet");
            }
            return documentMetadataMapper.toDocumentMetadataDTO(version.getMetadata());
        });
    }

    /**
     * @see bw.co.starsign.document.DocumentService#getPages(String, Integer)
     */
    @Override
    protected List<DocumentPageDTO> handleGetPages(String id, Integer versionNumber)
        throws Exception
    {
        return read.execute(status -> version(load(id), versionNumber).getPages().stream()
                .sorted(Comparator.comparingInt(DocumentPage::getPageNumber))
                .map(page -> {
                    DocumentPageDTO dto = documentPageMapper.toDocumentPageDTO(page);
                    dto.setPreviewAvailable(page.getPreviewArtifact() != null);
                    dto.setThumbnailAvailable(page.getThumbnailArtifact() != null);
                    return dto;
                })
                .toList());
    }

    /**
     * A PNG of the page; rendered on the fly when the stored preview is missing (e.g. processing failed).
     *
     * @see bw.co.starsign.document.DocumentService#getPreview(String, int, Integer)
     */
    @Override
    protected Resource handleGetPreview(String id, int pageNumber, Integer versionNumber)
        throws Exception
    {
        return read.execute(status -> pageImage(version(load(id), versionNumber), pageNumber, true));
    }

    /**
     * @see bw.co.starsign.document.DocumentService#getThumbnail(String, int)
     */
    @Override
    protected Resource handleGetThumbnail(String id, int pageNumber)
        throws Exception
    {
        return read.execute(status -> pageImage(version(load(id), null), pageNumber, false));
    }

    /**
     * @see bw.co.starsign.document.DocumentService#streamForPreparation(String)
     */
    @Override
    protected Resource handleStreamForPreparation(String id)
        throws Exception
    {
        return read.execute(status -> {
            Document document = load(id);
            DocumentArtifact pdf = pdf(version(document, null));
            return file(pdf, document.getOriginalFileName());
        });
    }

    /**
     * @see bw.co.starsign.document.DocumentService#downloadOriginal(String)
     */
    @Override
    protected Resource handleDownloadOriginal(String id)
        throws Exception
    {
        return write.execute(status -> {
            Document document = load(id);
            DocumentArtifact original = document.getVersions().stream()
                    .flatMap(version -> version.getArtifacts().stream())
                    .filter(artifact -> artifact.getArtifactType() == ArtifactType.ORIGINAL)
                    .findFirst()
                    .orElseThrow(() -> DocumentServiceException.notFound("The original file of the document is missing"));
            downloaded(document, original);
            return file(original, document.getOriginalFileName());
        });
    }

    /**
     * @see bw.co.starsign.document.DocumentService#download(String)
     */
    @Override
    protected Resource handleDownload(String id)
        throws Exception
    {
        return write.execute(status -> {
            Document document = load(id);
            Optional<DocumentArtifact> signed = finalArtifact(document);
            DocumentArtifact pdf;
            String name;
            if (document.getStatus() == DocumentStatus.COMPLETED && signed.isPresent())
            {
                pdf = signed.get();
                name = baseName(document.getOriginalFileName()) + "-signed.pdf";
            }
            else if (caller.has("documents:download-draft"))
            {
                pdf = pdf(version(document, null));
                name = document.getOriginalFileName();
            }
            else
            {
                throw new DocumentServiceException(
                        "The document is not signed yet: only completed documents can be downloaded");
            }
            downloaded(document, pdf);
            return file(pdf, name);
        });
    }

    /**
     * @see bw.co.starsign.document.DocumentService#downloadArtifact(String, String)
     */
    @Override
    protected Resource handleDownloadArtifact(String id, String artifactId)
        throws Exception
    {
        UUID key = uuid(artifactId, "file");
        return write.execute(status -> {
            Document document = load(id);
            DocumentArtifact artifact = document.getVersions().stream()
                    .flatMap(version -> version.getArtifacts().stream())
                    .filter(candidate -> key.equals(candidate.getId()))
                    .findFirst()
                    .orElseThrow(() -> DocumentServiceException.notFound("The file does not belong to the document"));
            downloaded(document, artifact);
            return file(artifact, artifactName(document, artifact));
        });
    }

    /**
     * @see bw.co.starsign.document.DocumentService#archive(String)
     */
    @Override
    protected DocumentDTO handleArchive(String id)
        throws Exception
    {
        return write.execute(status -> {
            Document document = load(id);
            if (document.getStatus() == DocumentStatus.ARCHIVED)
            {
                throw new DocumentServiceException("The document is already archived");
            }
            if (!ARCHIVABLE.contains(document.getStatus()))
            {
                throw new DocumentServiceException("A document can only be archived when it is a draft or its signing has ended");
            }
            DocumentStatus previous = document.getStatus();
            document.setStatus(DocumentStatus.ARCHIVED);
            document.setArchivedAt(LocalDateTime.now());
            touch(document);
            Document saved = documentRepository.save(document);
            auditTrailService.record(artifacts.event(AuditEventType.DOCUMENT_ARCHIVED, saved, AuditOutcome.SUCCESS,
                    Map.of("previousStatus", previous.getValue())));
            return dto(saved);
        });
    }

    /**
     * Soft delete: the record and its files are kept (for audit and retention), the document disappears.
     *
     * @see bw.co.starsign.document.DocumentService#remove(String)
     */
    @Override
    protected boolean handleRemove(String id)
        throws Exception
    {
        return write.execute(status -> {
            Document document = load(id);
            boolean sent = document.getWorkflows().stream()
                    .anyMatch(workflow -> workflow.getInitiatedAt() != null || !UNSENT_WORKFLOW.contains(workflow.getStatus()));
            if (sent || !REMOVABLE.contains(document.getStatus()))
            {
                throw new DocumentServiceException(
                        "The document has been sent for signing and cannot be deleted: archive it instead");
            }
            document.setDeletedAt(LocalDateTime.now());
            touch(document);
            Document saved = documentRepository.save(document);
            auditTrailService.record(artifacts.event(AuditEventType.DOCUMENT_DELETED, saved, AuditOutcome.SUCCESS,
                    Map.of("referenceNumber", saved.getReferenceNumber())));
            return true;
        });
    }

    /**
     * Deletes finished documents whose retention has run out: the document's own retention date, otherwise
     * the organisation's retention days counted from completion, archiving or upload. Each document is
     * handled in its own transaction so one failure does not stop the run.
     *
     * @return the number of documents deleted
     * @see bw.co.starsign.document.DocumentService#applyRetentionPolicies()
     */
    @Override
    protected int handleApplyRetentionPolicies()
        throws Exception
    {
        LocalDate today = LocalDate.now();
        List<UUID> candidates = read.execute(status -> documentRepository.findRetentionCandidates(FINISHED));
        int deleted = 0;
        for (UUID id : candidates)
        {
            try
            {
                Boolean done = write.execute(status -> {
                    Document document = documentRepository.findById(id).orElse(null);
                    if (document == null || document.getDeletedAt() != null)
                    {
                        return false;
                    }
                    LocalDate until = retentionUntil(document);
                    if (until == null || !until.isBefore(today))
                    {
                        return false;
                    }
                    LocalDateTime now = LocalDateTime.now();
                    document.setDeletedAt(now);
                    document.setModifiedAt(now);
                    document.setModifiedBy(DocumentArtifacts.SYSTEM);
                    for (DocumentVersion version : document.getVersions())
                    {
                        for (DocumentArtifact artifact : version.getArtifacts())
                        {
                            storageService.scheduleDeletion(artifact.getStorageReference().getObjectId(), now);
                        }
                    }
                    Document saved = documentRepository.save(document);
                    auditTrailService.record(artifacts.event(AuditEventType.DOCUMENT_DELETED, saved,
                            AuditOutcome.SUCCESS, Map.of("reason", "retention", "retentionUntil", until.toString())));
                    return true;
                });
                if (Boolean.TRUE.equals(done))
                {
                    deleted++;
                }
            }
            catch (RuntimeException e)
            {
                logger.error("Retention could not delete document {}", id, e);
            }
        }
        return deleted;
    }

    /* ---------------------------------------------------------------- loading and tenancy */

    private String tenant()
    {
        return caller.tenantId().orElseThrow(
                () -> new DocumentServiceException("Only users of an organisation can work with documents"));
    }

    /** The live document of the caller's tenant; other tenants' documents do not exist for the caller. */
    private Document load(String id)
    {
        String tenantId = tenant();
        return documentRepository.findById(uuid(id, "document"))
                .filter(document -> document.getDeletedAt() == null)
                .filter(document -> tenantId.equals(document.getOrganisation().getTenantId()))
                .orElseThrow(() -> DocumentServiceException.notFound("The document does not exist"));
    }

    private static UUID uuid(String id, String what)
    {
        try
        {
            return UUID.fromString(id.trim());
        }
        catch (IllegalArgumentException | NullPointerException e)
        {
            throw DocumentServiceException.notFound("'" + id + "' is not the id of a " + what);
        }
    }

    private static DocumentVersion version(Document document, Integer versionNumber)
    {
        return DocumentArtifacts.version(document, versionNumber)
                .orElseThrow(() -> DocumentServiceException.notFound(
                        "Version " + versionNumber + " of the document does not exist"));
    }

    private static DocumentArtifact pdf(DocumentVersion version)
    {
        return DocumentArtifacts.pdf(version)
                .orElseThrow(() -> DocumentServiceException.notFound("The document's file is missing"));
    }

    private void touch(Document document)
    {
        document.setModifiedAt(LocalDateTime.now());
        document.setModifiedBy(caller.userId());
    }

    /* ---------------------------------------------------------------- files */

    private DocumentFile file(DocumentArtifact artifact, String name)
    {
        return new DocumentFile(artifacts.open(artifact), name, artifact.getContentType(), artifact.getSizeBytes());
    }

    private void downloaded(Document document, DocumentArtifact artifact)
    {
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("artifactId", String.valueOf(artifact.getId()));
        details.put("artifactType", artifact.getArtifactType().getValue());
        details.put("versionNumber", artifact.getVersion().getVersionNumber());
        auditTrailService.record(artifacts.event(AuditEventType.DOCUMENT_DOWNLOADED, document, AuditOutcome.SUCCESS, details));
    }

    private DocumentFile pageImage(DocumentVersion version, int pageNumber, boolean preview)
    {
        DocumentPage page = version.getPages().stream()
                .filter(candidate -> candidate.getPageNumber() == pageNumber)
                .findFirst()
                .orElse(null);
        if (page == null && (pageNumber < 1 || pageNumber > version.getPageCount()))
        {
            throw DocumentServiceException.notFound("The document has no page " + pageNumber);
        }
        String name = (preview ? "page-" : "thumbnail-") + pageNumber + ".png";
        DocumentArtifact stored = page == null ? null : preview ? page.getPreviewArtifact() : page.getThumbnailArtifact();
        if (stored != null)
        {
            return file(stored, name);
        }

        byte[] content = artifacts.read(pdf(version));
        try (PDDocument pdf = Loader.loadPDF(content))
        {
            if (pageNumber < 1 || pageNumber > pdf.getNumberOfPages())
            {
                throw DocumentServiceException.notFound("The document has no page " + pageNumber);
            }
            PDFRenderer renderer = new PDFRenderer(pdf);
            byte[] image = preview
                    ? DocumentProcessingServiceImpl.png(renderer.renderImageWithDPI(pageNumber - 1,
                            DocumentProcessingServiceImpl.PREVIEW_DPI, ImageType.RGB))
                    : DocumentProcessingServiceImpl.png(renderer.renderImage(pageNumber - 1,
                            page == null ? 0.25f : DocumentProcessingServiceImpl.thumbnailScale(page), ImageType.RGB));
            return new DocumentFile(image, name, "image/png");
        }
        catch (IOException e)
        {
            throw new DocumentServiceException("Page " + pageNumber + " of the document cannot be shown", e);
        }
    }

    private static Optional<DocumentArtifact> finalArtifact(Document document)
    {
        return document.getVersions().stream()
                .flatMap(version -> version.getArtifacts().stream())
                .filter(artifact -> artifact.getArtifactType() == ArtifactType.FINAL_SIGNED)
                .max(Comparator.comparing(DocumentArtifact::getGeneratedAt));
    }

    /** The file name without any client path, control characters or quotes (it ends up in headers). */
    private static String fileName(String submitted)
    {
        String name = StringUtils.defaultString(submitted);
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = name.replaceAll("[\\p{Cntrl}\"]", "").strip();
        if (name.length() > 255)
        {
            name = name.substring(name.length() - 255);
        }
        return name.isEmpty() ? "document.pdf" : name;
    }

    private static String baseName(String fileName)
    {
        String name = StringUtils.defaultIfBlank(fileName, "document");
        return name.toLowerCase(Locale.ROOT).endsWith(".pdf") ? name.substring(0, name.length() - 4) : name;
    }

    private static String artifactName(Document document, DocumentArtifact artifact)
    {
        String base = baseName(document.getOriginalFileName());
        return switch (artifact.getArtifactType())
        {
            case ORIGINAL -> document.getOriginalFileName();
            case FINAL_SIGNED -> base + "-signed.pdf";
            case PAGE_PREVIEW -> "page-" + artifact.getPageNumber() + ".png";
            case THUMBNAIL -> "thumbnail-" + artifact.getPageNumber() + ".png";
            default -> base + "-" + artifact.getArtifactType().getValue().toLowerCase(Locale.ROOT).replace('_', '-')
                    + extension(artifact.getContentType());
        };
    }

    private static String extension(String contentType)
    {
        String type = baseType(contentType);
        return switch (type)
        {
            case "application/pdf" -> ".pdf";
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            case "image/svg+xml" -> ".svg";
            case "application/zip" -> ".zip";
            case "application/json" -> ".json";
            case "text/csv" -> ".csv";
            default -> "";
        };
    }

    private static String baseType(String contentType)
    {
        if (contentType == null)
        {
            return "";
        }
        int parameters = contentType.indexOf(';');
        return (parameters < 0 ? contentType : contentType.substring(0, parameters)).trim().toLowerCase(Locale.ROOT);
    }

    /* ---------------------------------------------------------------- details */

    private static List<String> checkDetails(DocumentUploadDTO details)
    {
        List<String> errors = new ArrayList<String>();
        if (StringUtils.isBlank(details.getTitle()))
        {
            errors.add("The title is required");
        }
        else if (details.getTitle().strip().length() > 255)
        {
            errors.add("The title can be at most 255 characters");
        }
        String reference = StringUtils.trimToNull(details.getReferenceNumber());
        if (reference != null && !REFERENCE.matcher(reference).matches())
        {
            errors.add("The reference number can have up to 100 letters, digits, '/', '.', '-' or '_'");
        }
        if (details.getRetentionUntil() != null && details.getRetentionUntil().isBefore(LocalDate.now()))
        {
            errors.add("The retention date cannot be in the past");
        }
        String tags = tags(details.getTags());
        if (tags != null && tags.length() > 255)
        {
            errors.add("The tags are too long: use at most 255 characters");
        }
        if (tags != null && List.of(tags.split(",")).stream().anyMatch(tag -> tag.length() > MAX_TAG_LENGTH))
        {
            errors.add("A tag can be at most " + MAX_TAG_LENGTH + " characters");
        }
        return errors;
    }

    /** Comma-separated tags, trimmed, without blanks or duplicates (ignoring case). */
    private static String tags(String tags)
    {
        if (StringUtils.isBlank(tags))
        {
            return null;
        }
        Set<String> seen = new LinkedHashSet<String>();
        List<String> kept = new ArrayList<String>();
        for (String tag : tags.split(","))
        {
            String trimmed = tag.strip();
            if (!trimmed.isEmpty() && seen.add(trimmed.toLowerCase(Locale.ROOT)))
            {
                kept.add(trimmed);
            }
        }
        return kept.isEmpty() ? null : String.join(",", kept);
    }

    private String newReference()
    {
        String prefix = "DOC-" + LocalDate.now().format(REFERENCE_DATE) + "-";
        for (int attempt = 0; attempt < 20; attempt++)
        {
            StringBuilder reference = new StringBuilder(prefix);
            for (int i = 0; i < 6; i++)
            {
                reference.append(REFERENCE_CHARACTERS.charAt(RANDOM.nextInt(REFERENCE_CHARACTERS.length())));
            }
            if (!documentRepository.existsByReferenceNumberIgnoreCase(reference.toString()))
            {
                return reference.toString();
            }
        }
        throw new IllegalStateException("No free document reference number could be generated");
    }

    private static long uploadLimitMb(Organisation organisation)
    {
        OrganisationSigningPolicy policy = organisation.getSigningPolicy();
        return policy == null || policy.getMaxUploadSizeMb() <= 0 ? DEFAULT_UPLOAD_LIMIT_MB : policy.getMaxUploadSizeMb();
    }

    private static LocalDate retentionUntil(Document document)
    {
        if (document.getRetentionUntil() != null)
        {
            return document.getRetentionUntil();
        }
        OrganisationSigningPolicy policy = document.getOrganisation().getSigningPolicy();
        if (policy == null || policy.getDocumentRetentionDays() <= 0)
        {
            return null;
        }
        LocalDateTime from = document.getCompletedAt() != null ? document.getCompletedAt()
                : document.getArchivedAt() != null ? document.getArchivedAt() : document.getCreatedAt();
        return from == null ? null : from.toLocalDate().plusDays(policy.getDocumentRetentionDays());
    }

    /* ---------------------------------------------------------------- search */

    private static Specification<Document> specification(DocumentSearchCriteria criteria, String tenantId, String ownerUserId)
    {
        List<Specification<Document>> specifications = new ArrayList<Specification<Document>>();
        specifications.add((root, query, cb) -> cb.equal(root.get("organisation").get("tenantId"), tenantId));
        specifications.add((root, query, cb) -> cb.isNull(root.get("deletedAt")));

        if (ownerUserId != null)
        {
            specifications.add((root, query, cb) -> cb.or(
                    cb.equal(root.get("owner").get("keycloakUserId"), ownerUserId),
                    cb.exists(signatories(root, query, cb, signatory ->
                            cb.equal(signatory.get("internalUser").get("keycloakUserId"), ownerUserId)))));
        }

        List<DocumentStatus> statuses = criteria.getStatuses() == null ? List.of() : criteria.getStatuses();
        if (!statuses.isEmpty())
        {
            specifications.add((root, query, cb) -> root.get("status").in(statuses));
        }
        if (!Boolean.TRUE.equals(criteria.getIncludeArchived()) && !statuses.contains(DocumentStatus.ARCHIVED))
        {
            specifications.add((root, query, cb) -> cb.notEqual(root.get("status"), DocumentStatus.ARCHIVED));
        }

        String text = StringUtils.trimToNull(criteria.getText());
        if (text != null)
        {
            String like = "%" + escape(text.toLowerCase(Locale.ROOT)) + "%";
            specifications.add((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("title")), like, '\\'),
                    cb.like(cb.lower(root.get("referenceNumber")), like, '\\'),
                    cb.like(cb.lower(root.get("originalFileName")), like, '\\'),
                    cb.like(cb.lower(root.get("tags")), like, '\\')));
        }
        String reference = StringUtils.trimToNull(criteria.getReferenceNumber());
        if (reference != null)
        {
            specifications.add((root, query, cb) ->
                    cb.equal(cb.lower(root.get("referenceNumber")), reference.toLowerCase(Locale.ROOT)));
        }
        String ownerId = StringUtils.trimToNull(criteria.getOwnerId());
        if (ownerId != null)
        {
            // The owner may be given as the organisation user id or the Keycloak user id
            specifications.add((root, query, cb) -> {
                Predicate byKeycloak = cb.equal(root.get("owner").get("keycloakUserId"), ownerId);
                try
                {
                    return cb.or(byKeycloak, cb.equal(root.get("owner").get("id"), UUID.fromString(ownerId)));
                }
                catch (IllegalArgumentException e)
                {
                    return byKeycloak;
                }
            });
        }
        String email = StringUtils.trimToNull(criteria.getSignatoryEmail());
        if (email != null)
        {
            specifications.add((root, query, cb) -> cb.exists(signatories(root, query, cb, signatory ->
                    cb.equal(cb.lower(signatory.get("emailAddress")), email.toLowerCase(Locale.ROOT)))));
        }
        if (criteria.getCreatedFrom() != null)
        {
            LocalDateTime from = criteria.getCreatedFrom().atStartOfDay();
            specifications.add((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), from));
        }
        if (criteria.getCreatedTo() != null)
        {
            LocalDateTime to = criteria.getCreatedTo().plusDays(1).atStartOfDay();
            specifications.add((root, query, cb) -> cb.lessThan(root.get("createdAt"), to));
        }
        if (criteria.getCompletedFrom() != null)
        {
            LocalDateTime from = criteria.getCompletedFrom().atStartOfDay();
            specifications.add((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("completedAt"), from));
        }
        if (criteria.getCompletedTo() != null)
        {
            LocalDateTime to = criteria.getCompletedTo().plusDays(1).atStartOfDay();
            specifications.add((root, query, cb) -> cb.lessThan(root.get("completedAt"), to));
        }
        return Specification.allOf(specifications);
    }

    private interface SignatoryCondition
    {
        Predicate on(Root<Signatory> signatory);
    }

    /** Signatories of the document's workflows that meet the condition. */
    private static Subquery<UUID> signatories(Root<Document> root, jakarta.persistence.criteria.CriteriaQuery<?> query,
            jakarta.persistence.criteria.CriteriaBuilder cb, SignatoryCondition condition)
    {
        Subquery<UUID> subquery = query.subquery(UUID.class);
        Root<Signatory> signatory = subquery.from(Signatory.class);
        subquery.select(signatory.get("id"))
                .where(cb.equal(signatory.get("workflow").get("document"), root), condition.on(signatory));
        return subquery;
    }

    private static String escape(String text)
    {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /* ---------------------------------------------------------------- mapping */

    private DocumentDTO dto(Document document)
    {
        DocumentDTO dto = documentMapper.toDocumentDTO(document);
        dto.setOrganisationId(document.getOrganisation().getId().toString());
        OrganisationUser owner = document.getOwner();
        dto.setOwnerId(owner.getId().toString());
        dto.setOwnerName(name(owner));
        relevantWorkflow(document)
                .filter(workflow -> ACTIVE_WORKFLOW.contains(workflow.getStatus()))
                .ifPresent(workflow -> dto.setActiveWorkflowId(workflow.getId().toString()));
        finalArtifact(document).ifPresent(artifact -> dto.setFinalArtifactId(artifact.getId().toString()));
        return dto;
    }

    /** The active workflow of the document, otherwise its latest one. */
    private static Optional<SigningWorkflow> relevantWorkflow(Document document)
    {
        Comparator<SigningWorkflow> latest = Comparator.comparing(SigningWorkflow::getCreatedAt,
                Comparator.nullsFirst(Comparator.naturalOrder()));
        Optional<SigningWorkflow> active = document.getWorkflows().stream()
                .filter(workflow -> ACTIVE_WORKFLOW.contains(workflow.getStatus()))
                .max(latest);
        return active.isPresent() ? active : document.getWorkflows().stream().max(latest);
    }

    private static String name(OrganisationUser user)
    {
        String name = StringUtils.trimToEmpty(StringUtils.trimToEmpty(user.getFirstName()) + " "
                + StringUtils.trimToEmpty(user.getLastName()));
        return name.isEmpty() ? user.getEmailAddress() : name;
    }

    private DocumentVersionDTO versionDto(Document document, DocumentVersion version)
    {
        DocumentVersionDTO dto = documentVersionMapper.toDocumentVersionDTO(version);
        dto.setDocumentId(document.getId().toString());
        List<DocumentArtifactDTO> files = new ArrayList<DocumentArtifactDTO>();
        for (DocumentArtifact artifact : version.getArtifacts())
        {
            if (!PAGE_IMAGES.contains(artifact.getArtifactType()))
            {
                DocumentArtifactDTO file = documentArtifactMapper.toDocumentArtifactDTO(artifact);
                file.setObjectId(artifact.getStorageReference().getObjectId());
                files.add(file);
            }
        }
        dto.setArtifacts(files);
        return dto;
    }
}
