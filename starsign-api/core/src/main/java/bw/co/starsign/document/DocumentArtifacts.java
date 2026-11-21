package bw.co.starsign.document;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import bw.co.starsign.audit.ActorType;
import bw.co.starsign.audit.AuditEventDTO;
import bw.co.starsign.audit.AuditEventType;
import bw.co.starsign.audit.AuditOutcome;
import bw.co.starsign.context.CallerContext;
import bw.co.starsign.storage.DocumentStorageReference;
import bw.co.starsign.storage.DocumentStorageReferenceDTO;
import bw.co.starsign.storage.DocumentStorageReferenceRepository;
import bw.co.starsign.storage.StorageNamespace;
import bw.co.starsign.storage.StorageService;
import bw.co.starsign.storage.StorageWriteRequestDTO;

/**
 * What the document services share: storing and reading artifacts, finding a version's PDF, and the
 * actor and audit event of the current operation.
 */
@Component
public class DocumentArtifacts
{
    /** Artifact types that hold the PDF of a version, most processed first. */
    static final List<ArtifactType> PDF_TYPES = List.of(
            ArtifactType.FINAL_SIGNED, ArtifactType.INTERMEDIATE_SIGNED, ArtifactType.PREPARATION, ArtifactType.ORIGINAL);

    static final String SYSTEM = "system";

    private final StorageService storageService;
    private final DocumentStorageReferenceRepository storageReferenceRepository;
    private final CallerContext caller;

    public DocumentArtifacts(
        @Lazy StorageService storageService,
        DocumentStorageReferenceRepository storageReferenceRepository,
        @Lazy CallerContext caller)
    {
        this.storageService = storageService;
        this.storageReferenceRepository = storageReferenceRepository;
        this.caller = caller;
    }

    /**
     * Stores the bytes and adds an artifact for them to the version (persisted with the version).
     */
    DocumentArtifact store(DocumentVersion version, ArtifactType type, StorageNamespace namespace, byte[] content,
            String contentType, String fileName, Integer pageNumber, boolean immutable)
    {
        Document document = version.getDocument();
        String sha256 = PdfInspector.sha256(content);

        StorageWriteRequestDTO request = new StorageWriteRequestDTO();
        request.setOrganisationId(document.getOrganisation().getId().toString());
        request.setNamespace(namespace);
        request.setContentType(contentType);
        request.setContentLength((long) content.length);
        request.setOriginalFileName(fileName);
        request.setExpectedSha256(sha256);
        Map<String, String> metadata = new HashMap<String, String>();
        metadata.put("documentReference", document.getReferenceNumber());
        metadata.put("versionNumber", String.valueOf(version.getVersionNumber()));
        metadata.put("artifactType", type.getValue());
        request.setMetadata(metadata);

        DocumentStorageReferenceDTO stored = storageService.store(request, new ByteArrayInputStream(content));

        LocalDateTime now = LocalDateTime.now();
        DocumentArtifact artifact = DocumentArtifact.Factory.newInstance();
        artifact.setArtifactType(type);
        artifact.setSha256(sha256);
        artifact.setContentType(contentType);
        artifact.setSizeBytes(content.length);
        artifact.setPageNumber(pageNumber);
        artifact.setGeneratedAt(now);
        artifact.setImmutable(immutable);
        artifact.setStorageReference(reference(stored.getObjectId()));
        artifact.setCreatedAt(now);
        artifact.setCreatedBy(actor());
        artifact.setVersion(version);
        version.addArtifacts(artifact);
        return artifact;
    }

    DocumentStorageReference reference(String objectId)
    {
        return storageReferenceRepository.findByObjectId(objectId)
                .orElseThrow(() -> new IllegalStateException("The stored object " + objectId + " has no storage reference"));
    }

    InputStream open(DocumentArtifact artifact)
    {
        return storageService.openStream(artifact.getStorageReference().getObjectId());
    }

    byte[] read(DocumentArtifact artifact)
    {
        try (InputStream in = open(artifact))
        {
            return in.readAllBytes();
        }
        catch (IOException e)
        {
            throw new IllegalStateException("The " + artifact.getArtifactType() + " file of document "
                    + artifact.getVersion().getDocument().getReferenceNumber() + " cannot be read", e);
        }
    }

    /** The PDF of the version: its signed, prepared or original file. */
    static Optional<DocumentArtifact> pdf(DocumentVersion version)
    {
        return version.getArtifacts().stream()
                .filter(artifact -> PDF_TYPES.contains(artifact.getArtifactType()))
                .min(Comparator.comparingInt(artifact -> PDF_TYPES.indexOf(artifact.getArtifactType())));
    }

    /** The numbered version, or the current one when no number is given. */
    static Optional<DocumentVersion> version(Document document, Integer versionNumber)
    {
        int number = versionNumber == null ? document.getCurrentVersionNumber() : versionNumber;
        return document.getVersions().stream().filter(version -> version.getVersionNumber() == number).findFirst();
    }

    /** The signed-in user, or null in background jobs. */
    String userIdOrNull()
    {
        try
        {
            return caller.userId();
        }
        catch (RuntimeException e)
        {
            // Scheduled jobs and message consumers run without a signed-in user
            return null;
        }
    }

    String actor()
    {
        String userId = userIdOrNull();
        return userId == null ? SYSTEM : userId;
    }

    AuditEventDTO event(AuditEventType type, Document document, AuditOutcome outcome, Map<String, ?> details)
    {
        String userId = userIdOrNull();
        AuditEventDTO event = new AuditEventDTO();
        event.setEventType(type);
        event.setOccurredAt(LocalDateTime.now());
        event.setOrganisationId(document.getOrganisation().getId().toString());
        event.setActorType(userId == null ? ActorType.SYSTEM : ActorType.ORGANISATION_USER);
        event.setActorId(userId == null ? SYSTEM : userId);
        event.setOutcome(outcome);
        event.setDocumentId(document.getId() == null ? null : document.getId().toString());
        event.setEntityType("Document");
        event.setEntityId(event.getDocumentId());
        event.setDocumentSha256(document.getOriginalSha256());
        event.setDetails(details == null ? null : json(details));
        return event;
    }

    /** A JSON object of the (flat) values: the audit details and raw PDF metadata are JSON columns. */
    static String json(Map<String, ?> values)
    {
        StringBuilder json = new StringBuilder("{");
        for (Map.Entry<String, ?> entry : values.entrySet())
        {
            if (json.length() > 1)
            {
                json.append(',');
            }
            json.append(quote(entry.getKey())).append(':').append(value(entry.getValue()));
        }
        return json.append('}').toString();
    }

    private static String value(Object value)
    {
        if (value == null)
        {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean)
        {
            return value.toString();
        }
        if (value instanceof Iterable<?> items)
        {
            StringBuilder array = new StringBuilder("[");
            for (Object item : items)
            {
                if (array.length() > 1)
                {
                    array.append(',');
                }
                array.append(value(item));
            }
            return array.append(']').toString();
        }
        return quote(value.toString());
    }

    private static String quote(String text)
    {
        StringBuilder quoted = new StringBuilder("\"");
        for (char c : text.toCharArray())
        {
            switch (c)
            {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                default ->
                {
                    if (c < 0x20)
                    {
                        quoted.append(String.format("\\u%04x", (int) c));
                    }
                    else
                    {
                        quoted.append(c);
                    }
                }
            }
        }
        return quoted.append('"').toString();
    }
}
