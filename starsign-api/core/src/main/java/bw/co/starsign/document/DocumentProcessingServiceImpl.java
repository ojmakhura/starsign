// license-header java merge-point
/**
 * This is only generated once! It will never be overwritten.
 * You can (and have to!) safely modify it by hand.
 * TEMPLATE:    SpringServiceImpl.vsl in andromda-spring cartridge
 * MODEL CLASS: AndroMDAModel::backend::bw.co.starsign::document::DocumentProcessingService
 * STEREOTYPE:  Service
 */
package bw.co.starsign.document;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import bw.co.starsign.audit.AuditEventType;
import bw.co.starsign.audit.AuditOutcome;
import bw.co.starsign.audit.AuditTrailService;
import bw.co.starsign.storage.DocumentStorageReferenceDTO;
import bw.co.starsign.storage.StorageNamespace;
import bw.co.starsign.storage.StorageService;

/**
 * Turns an uploaded PDF version into what the portal shows: metadata, page geometry, page previews and
 * thumbnails. There is no message broker, so {@link DocumentService#upload} calls
 * {@link #processUploadedVersion} itself once the upload is committed.
 * <p>
 * Transactions are opened here explicitly: the generated base declares the public methods, so a
 * class-level {@code @Transactional} on this subclass would not apply to them.
 *
 * @see bw.co.starsign.document.DocumentProcessingService
 */
@Service("documentProcessingService")
public class DocumentProcessingServiceImpl
    extends DocumentProcessingServiceBase
{
    static final int PREVIEW_DPI = 96;
    static final int THUMBNAIL_WIDTH = 160;
    private static final int MIN_DPI = 36;
    private static final int MAX_DPI = 300;
    private static final String PNG = "image/png";

    /** Content can only be replaced before the document is sent for signing. */
    private static final Set<DocumentStatus> SANITISABLE = Set.of(
            DocumentStatus.UPLOADED, DocumentStatus.DRAFT, DocumentStatus.IN_PREPARATION);

    private final DocumentArtifacts artifacts;
    private final PdfInspector pdfInspector;
    private final TransactionTemplate write;

    public DocumentProcessingServiceImpl(
        @Lazy StorageService storageService,
        @Lazy MalwareScanner malwareScanner,
        @Lazy AuditTrailService auditTrailService,
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
        DocumentArtifacts artifacts,
        PdfInspector pdfInspector,
        PlatformTransactionManager transactionManager) {

        super(
            storageService,
            malwareScanner,
            auditTrailService,
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
        this.artifacts = artifacts;
        this.pdfInspector = pdfInspector;
        this.write = new TransactionTemplate(transactionManager);
    }

    /**
     * @see bw.co.starsign.document.DocumentProcessingService#validatePdf(String)
     */
    @Override
    protected PdfValidationResultDTO handleValidatePdf(String objectId)
        throws Exception
    {
        DocumentStorageReferenceDTO reference = storageService.findByObjectId(objectId);
        if (reference == null)
        {
            throw DocumentProcessingServiceException.notFound("The stored file does not exist");
        }
        byte[] content;
        try (InputStream in = storageService.openStream(objectId))
        {
            content = in.readAllBytes();
        }
        return pdfInspector.inspect(content, reference.getOriginalFileName());
    }

    /**
     * Idempotent: running it again refreshes the metadata, pages and images of the version.
     *
     * @see bw.co.starsign.document.DocumentProcessingService#processUploadedVersion(String, int)
     */
    @Override
    protected void handleProcessUploadedVersion(String documentId, int versionNumber)
        throws Exception
    {
        write.executeWithoutResult(status -> {
            Document document = load(documentId);
            DocumentVersion version = version(document, versionNumber);
            byte[] content = artifacts.read(pdf(version));

            DocumentMetadata metadata = withPdf(content, pdf -> {
                DocumentMetadata extracted = metadata(version, pdf, content);
                pages(version, pdf);
                previews(version, pdf, PREVIEW_DPI);
                return extracted;
            });

            int pageCount = version.getPages().size();
            version.setPageCount(pageCount);
            if (document.getCurrentVersionNumber() == versionNumber)
            {
                document.setPageCount(pageCount);
            }
            boolean clean = "CLEAN".equals(metadata.getMalwareScanStatus());
            // A file that fails the scan stays UPLOADED: it cannot be prepared or sent for signing
            if (clean && document.getStatus() == DocumentStatus.UPLOADED)
            {
                document.setStatus(DocumentStatus.DRAFT);
            }
            touch(document);
            documentRepository.save(document);

            Map<String, Object> details = new LinkedHashMap<String, Object>();
            details.put("versionNumber", versionNumber);
            details.put("pageCount", pageCount);
            details.put("malwareScanStatus", metadata.getMalwareScanStatus());
            auditTrailService.record(artifacts.event(AuditEventType.DOCUMENT_PROCESSED, document,
                    clean ? AuditOutcome.SUCCESS : AuditOutcome.FAILURE, details));
        });
    }

    /**
     * @see bw.co.starsign.document.DocumentProcessingService#extractMetadata(String, int)
     */
    @Override
    protected DocumentMetadataDTO handleExtractMetadata(String documentId, int versionNumber)
        throws Exception
    {
        return write.execute(status -> {
            DocumentVersion version = version(load(documentId), versionNumber);
            byte[] content = artifacts.read(pdf(version));
            DocumentMetadata metadata = withPdf(content, pdf -> metadata(version, pdf, content));
            documentVersionRepository.save(version);
            return documentMetadataMapper.toDocumentMetadataDTO(metadata);
        });
    }

    /**
     * @see bw.co.starsign.document.DocumentProcessingService#extractPages(String, int)
     */
    @Override
    protected List<DocumentPageDTO> handleExtractPages(String documentId, int versionNumber)
        throws Exception
    {
        return write.execute(status -> {
            DocumentVersion version = version(load(documentId), versionNumber);
            byte[] content = artifacts.read(pdf(version));
            withPdf(content, pdf -> {
                pages(version, pdf);
                return null;
            });
            version.setPageCount(version.getPages().size());
            documentVersionRepository.save(version);
            return pageDtos(version);
        });
    }

    /**
     * @return the number of pages rendered
     * @see bw.co.starsign.document.DocumentProcessingService#generatePreviews(String, int, int)
     */
    @Override
    protected int handleGeneratePreviews(String documentId, int versionNumber, int dpi)
        throws Exception
    {
        if (dpi < MIN_DPI || dpi > MAX_DPI)
        {
            throw new DocumentProcessingServiceException(
                    "Previews can be rendered at " + MIN_DPI + " to " + MAX_DPI + " DPI");
        }
        return write.execute(status -> {
            DocumentVersion version = version(load(documentId), versionNumber);
            byte[] content = artifacts.read(pdf(version));
            int rendered = withPdf(content, pdf -> {
                if (version.getPages().isEmpty())
                {
                    pages(version, pdf);
                }
                return previews(version, pdf, dpi);
            });
            documentVersionRepository.save(version);
            return rendered;
        });
    }

    /**
     * @see bw.co.starsign.document.DocumentProcessingService#sanitise(String)
     */
    @Override
    protected DocumentVersionDTO handleSanitise(String documentId)
        throws Exception
    {
        return write.execute(status -> {
            Document document = load(documentId);
            if (!SANITISABLE.contains(document.getStatus()))
            {
                throw new DocumentProcessingServiceException(
                        "A document can only be cleaned before it is sent for signing");
            }
            DocumentVersion source = version(document, document.getCurrentVersionNumber());
            byte[] original = artifacts.read(pdf(source));

            byte[] cleaned = withPdf(original, pdf -> {
                int removed = PdfActiveContent.sanitise(pdf);
                if (removed == 0)
                {
                    return null;
                }
                if (pdf.isEncrypted())
                {
                    pdf.setAllSecurityToBeRemoved(true);
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                pdf.save(out);
                return out.toByteArray();
            });
            if (cleaned == null)
            {
                throw new DocumentProcessingServiceException("The document has no active content to remove");
            }

            // The original version is never touched: the cleaned file becomes a new version
            LocalDateTime now = LocalDateTime.now();
            int number = document.getVersions().stream().mapToInt(DocumentVersion::getVersionNumber).max().orElse(0) + 1;
            DocumentVersion version = DocumentVersion.Factory.newInstance();
            version.setVersionNumber(number);
            version.setSha256(PdfInspector.sha256(cleaned));
            version.setReason("Active content removed");
            version.setSourceVersionNumber(source.getVersionNumber());
            version.setGeneratedBy(artifacts.actor());
            version.setImmutable(true);
            version.setCreatedAt(now);
            version.setCreatedBy(artifacts.actor());
            version.setDocument(document);
            document.addVersions(version);
            artifacts.store(version, ArtifactType.PREPARATION, StorageNamespace.ARTIFACTS, cleaned, PdfInspector.PDF,
                    document.getOriginalFileName(), null, true);

            withPdf(cleaned, pdf -> {
                metadata(version, pdf, cleaned).setSanitised(true);
                pages(version, pdf);
                previews(version, pdf, PREVIEW_DPI);
                return null;
            });
            version.setPageCount(version.getPages().size());
            document.setCurrentVersionNumber(number);
            document.setPageCount(version.getPageCount());
            touch(document);
            Document saved = documentRepository.save(document);

            DocumentVersion stored = version(saved, number);
            DocumentVersionDTO dto = documentVersionMapper.toDocumentVersionDTO(stored);
            dto.setDocumentId(saved.getId().toString());
            return dto;
        });
    }

    private Document load(String id)
    {
        UUID key;
        try
        {
            key = UUID.fromString(id.trim());
        }
        catch (IllegalArgumentException e)
        {
            throw DocumentProcessingServiceException.notFound("'" + id + "' is not the id of a document");
        }
        return documentRepository.findById(key)
                .filter(document -> document.getDeletedAt() == null)
                .orElseThrow(() -> DocumentProcessingServiceException.notFound("The document does not exist"));
    }

    private static DocumentVersion version(Document document, int versionNumber)
    {
        return DocumentArtifacts.version(document, versionNumber)
                .orElseThrow(() -> DocumentProcessingServiceException.notFound(
                        "Version " + versionNumber + " of the document does not exist"));
    }

    private static DocumentArtifact pdf(DocumentVersion version)
    {
        return DocumentArtifacts.pdf(version)
                .orElseThrow(() -> DocumentProcessingServiceException.notFound(
                        "Version " + version.getVersionNumber() + " of the document has no PDF"));
    }

    private interface PdfWork<T>
    {
        T apply(PDDocument pdf) throws IOException;
    }

    private static <T> T withPdf(byte[] content, PdfWork<T> work)
    {
        try (PDDocument pdf = Loader.loadPDF(content))
        {
            return work.apply(pdf);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("The PDF cannot be processed", e);
        }
    }

    private void touch(Document document)
    {
        document.setModifiedAt(LocalDateTime.now());
        document.setModifiedBy(artifacts.actor());
    }

    /** Creates or refreshes the metadata of the version. */
    private DocumentMetadata metadata(DocumentVersion version, PDDocument pdf, byte[] content) throws IOException
    {
        LocalDateTime now = LocalDateTime.now();
        DocumentMetadata metadata = version.getMetadata();
        if (metadata == null)
        {
            metadata = DocumentMetadata.Factory.newInstance();
            metadata.setCreatedAt(now);
            metadata.setCreatedBy(artifacts.actor());
            metadata.setVersion(version);
            version.setMetadata(metadata);
        }
        else
        {
            metadata.setModifiedAt(now);
            metadata.setModifiedBy(artifacts.actor());
        }

        PDDocumentInformation info = pdf.getDocumentInformation();
        metadata.setPdfVersion(String.valueOf(pdf.getVersion()));
        metadata.setPdfTitle(trim(info.getTitle()));
        metadata.setPdfAuthor(trim(info.getAuthor()));
        metadata.setPdfSubject(trim(info.getSubject()));
        metadata.setPdfKeywords(trim(info.getKeywords()));
        metadata.setPdfCreator(trim(info.getCreator()));
        metadata.setPdfProducer(trim(info.getProducer()));
        metadata.setPdfCreatedAt(localDateTime(info.getCreationDate()));
        metadata.setPdfModifiedAt(localDateTime(info.getModificationDate()));

        PdfActiveContent.Findings findings = PdfActiveContent.scan(pdf);
        metadata.setEncrypted(pdf.isEncrypted());
        metadata.setContainsJavascript(findings.javascript());
        metadata.setContainsEmbeddedFiles(findings.embeddedFiles());
        metadata.setContainsDigitalSignatures(!pdf.getSignatureDictionaries().isEmpty());
        metadata.setContainsAcroForm(pdf.getDocumentCatalog().getAcroForm() != null);
        metadata.setLinearized(PdfInspector.isLinearized(content));

        boolean clean = malwareScanner.scan(new ByteArrayInputStream(content), version.getDocument().getOriginalFileName());
        metadata.setMalwareScanStatus(clean ? "CLEAN" : "REJECTED");
        metadata.setMalwareScannedAt(now);

        Map<String, Object> raw = new LinkedHashMap<String, Object>();
        for (String key : info.getMetadataKeys())
        {
            raw.put(key, info.getCustomMetadataValue(key));
        }
        metadata.setRawMetadata(DocumentArtifacts.json(raw));
        return metadata;
    }

    /** Creates or refreshes the page geometry; pages that no longer exist are dropped. */
    private void pages(DocumentVersion version, PDDocument pdf)
    {
        LocalDateTime now = LocalDateTime.now();
        int count = pdf.getNumberOfPages();
        for (int index = 0; index < count; index++)
        {
            int number = index + 1;
            PDPage source = pdf.getPage(index);
            DocumentPage page = page(version, number);
            if (page == null)
            {
                page = DocumentPage.Factory.newInstance();
                page.setPageNumber(number);
                page.setCreatedAt(now);
                page.setCreatedBy(artifacts.actor());
                page.setVersion(version);
                version.addPages(page);
            }
            PDRectangle media = source.getMediaBox();
            PDRectangle crop = source.getCropBox();
            page.setWidthPoints(media.getWidth());
            page.setHeightPoints(media.getHeight());
            page.setRotation(source.getRotation());
            page.setCropBoxX(crop.getLowerLeftX());
            page.setCropBoxY(crop.getLowerLeftY());
            page.setCropBoxWidth(crop.getWidth());
            page.setCropBoxHeight(crop.getHeight());
            page.setUserUnit(source.getUserUnit());
        }
        version.getPages().removeIf(page -> page.getPageNumber() > count);
    }

    /**
     * Renders every page as a PNG preview at the DPI and a {@value #THUMBNAIL_WIDTH}px wide thumbnail,
     * replacing earlier images.
     */
    private int previews(DocumentVersion version, PDDocument pdf, int dpi) throws IOException
    {
        PDFRenderer renderer = new PDFRenderer(pdf);
        List<DocumentPage> pages = new ArrayList<DocumentPage>(version.getPages());
        pages.sort(Comparator.comparingInt(DocumentPage::getPageNumber));
        int rendered = 0;
        for (DocumentPage page : pages)
        {
            int index = page.getPageNumber() - 1;
            if (index >= pdf.getNumberOfPages())
            {
                continue;
            }
            byte[] preview = png(renderer.renderImageWithDPI(index, dpi, ImageType.RGB));
            replace(page, DocumentPage::getPreviewArtifact, artifacts.store(version, ArtifactType.PAGE_PREVIEW,
                    StorageNamespace.PREVIEWS, preview, PNG, "page-" + page.getPageNumber() + ".png",
                    page.getPageNumber(), false), true);

            byte[] thumbnail = png(renderer.renderImage(index, thumbnailScale(page), ImageType.RGB));
            replace(page, DocumentPage::getThumbnailArtifact, artifacts.store(version, ArtifactType.THUMBNAIL,
                    StorageNamespace.THUMBNAILS, thumbnail, PNG, "thumbnail-" + page.getPageNumber() + ".png",
                    page.getPageNumber(), false), false);
            rendered++;
        }
        return rendered;
    }

    private void replace(DocumentPage page, Function<DocumentPage, DocumentArtifact> current, DocumentArtifact next,
            boolean preview)
    {
        DocumentArtifact old = current.apply(page);
        if (preview)
        {
            page.setPreviewArtifact(next);
        }
        else
        {
            page.setThumbnailArtifact(next);
        }
        if (old != null)
        {
            page.getVersion().removeArtifacts(old);
            storageService.scheduleDeletion(old.getStorageReference().getObjectId(), LocalDateTime.now());
        }
    }

    /** Scale from points to a thumbnail of fixed width, taking the page rotation into account. */
    static float thumbnailScale(DocumentPage page)
    {
        boolean sideways = page.getRotation() % 180 != 0;
        double width = sideways ? page.getCropBoxHeight() : page.getCropBoxWidth();
        if (width <= 0)
        {
            width = sideways ? page.getHeightPoints() : page.getWidthPoints();
        }
        return width <= 0 ? 0.25f : (float) Math.min(1.0, THUMBNAIL_WIDTH / width);
    }

    static byte[] png(BufferedImage image) throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static DocumentPage page(DocumentVersion version, int number)
    {
        return version.getPages().stream().filter(page -> page.getPageNumber() == number).findFirst().orElse(null);
    }

    private List<DocumentPageDTO> pageDtos(DocumentVersion version)
    {
        List<DocumentPage> pages = new ArrayList<DocumentPage>(version.getPages());
        pages.sort(Comparator.comparingInt(DocumentPage::getPageNumber));
        List<DocumentPageDTO> dtos = new ArrayList<DocumentPageDTO>();
        for (DocumentPage page : pages)
        {
            DocumentPageDTO dto = documentPageMapper.toDocumentPageDTO(page);
            dto.setPreviewAvailable(page.getPreviewArtifact() != null);
            dto.setThumbnailAvailable(page.getThumbnailArtifact() != null);
            dtos.add(dto);
        }
        return dtos;
    }

    private static String trim(String value)
    {
        if (value == null)
        {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.isEmpty())
        {
            return null;
        }
        // The columns are plain varchar(255)
        return trimmed.length() > 255 ? trimmed.substring(0, 255) : trimmed;
    }

    private static LocalDateTime localDateTime(Calendar calendar)
    {
        return calendar == null ? null : LocalDateTime.ofInstant(calendar.toInstant(), ZoneId.systemDefault());
    }
}
