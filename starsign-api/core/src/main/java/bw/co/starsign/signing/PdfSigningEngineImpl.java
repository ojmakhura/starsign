// license-header java merge-point
/**
 * This is only generated once! It will never be overwritten.
 * You can (and have to!) safely modify it by hand.
 * TEMPLATE:    SpringServiceImpl.vsl in andromda-spring cartridge
 * MODEL CLASS: AndroMDAModel::backend::bw.co.starsign::signing::PdfSigningEngine
 * STEREOTYPE:  Service
 */
package bw.co.starsign.signing;

import bw.co.starsign.audit.AuditEventType;
import bw.co.starsign.audit.AuditTrailService;
import bw.co.starsign.document.ArtifactType;
import bw.co.starsign.document.Document;
import bw.co.starsign.document.DocumentArtifact;
import bw.co.starsign.document.DocumentArtifactDTO;
import bw.co.starsign.document.DocumentArtifactMapper;
import bw.co.starsign.document.DocumentArtifactRepository;
import bw.co.starsign.document.DocumentPage;
import bw.co.starsign.document.DocumentVersion;
import bw.co.starsign.document.DocumentVersionMapper;
import bw.co.starsign.document.DocumentVersionRepository;
import bw.co.starsign.organisation.Organisation;
import bw.co.starsign.organisation.OrganisationRepository;
import bw.co.starsign.organisation.OrganisationSigningPolicy;
import bw.co.starsign.storage.DocumentStorageReference;
import bw.co.starsign.storage.DocumentStorageReferenceDTO;
import bw.co.starsign.storage.DocumentStorageReferenceRepository;
import bw.co.starsign.storage.StorageNamespace;
import bw.co.starsign.storage.StorageService;
import bw.co.starsign.storage.StorageWriteRequestDTO;
import bw.co.starsign.workflow.FieldType;
import bw.co.starsign.workflow.Signatory;
import bw.co.starsign.workflow.SignatoryRepository;
import bw.co.starsign.workflow.SigningField;
import bw.co.starsign.workflow.SigningFieldMapper;
import bw.co.starsign.workflow.SigningFieldRepository;
import bw.co.starsign.workflow.SigningWorkflow;
import bw.co.starsign.workflow.SigningWorkflowRepository;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import org.apache.commons.lang3.StringUtils;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.tsp.TimeStampToken;
import org.bouncycastle.util.Store;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * @see bw.co.starsign.signing.PdfSigningEngine
 */
@Service("pdfSigningEngine")
@Transactional(propagation = Propagation.REQUIRED, readOnly = false)
public class PdfSigningEngineImpl
    extends PdfSigningEngineBase
{
    private static final String PDF = "application/pdf";
    private static final String GENERATED_BY = "pdf-signing-engine";

    private final SigningWorkflowRepository signingWorkflowRepository;
    private final SignatoryRepository signatoryRepository;
    private final DocumentStorageReferenceRepository documentStorageReferenceRepository;
    private final OrganisationRepository organisationRepository;
    private final SigningEventRepository signingEventRepository;
    private final SigningKeystore signingKeystore;
    private final SigningProperties signingProperties;
    private final SigningEventRecorder signingEventRecorder;

    public PdfSigningEngineImpl(
        @Lazy StorageService storageService,
        @Lazy AuditTrailService auditTrailService,
        DocumentArtifactRepository documentArtifactRepository,
        DocumentArtifactMapper documentArtifactMapper,
        DocumentVersionRepository documentVersionRepository,
        DocumentVersionMapper documentVersionMapper,
        SigningFieldRepository signingFieldRepository,
        SigningFieldMapper signingFieldMapper,
        SigningFieldValueRepository signingFieldValueRepository,
        SigningFieldValueMapper signingFieldValueMapper,
        SignatureEvidenceRepository signatureEvidenceRepository,
        SignatureEvidenceMapper signatureEvidenceMapper,
        SigningWorkflowRepository signingWorkflowRepository,
        SignatoryRepository signatoryRepository,
        DocumentStorageReferenceRepository documentStorageReferenceRepository,
        OrganisationRepository organisationRepository,
        SigningEventRepository signingEventRepository,
        SigningKeystore signingKeystore,
        SigningProperties signingProperties,
        SigningEventRecorder signingEventRecorder) {

        super(
            storageService,
            auditTrailService,
            documentArtifactRepository,
            documentArtifactMapper,
            documentVersionRepository,
            documentVersionMapper,
            signingFieldRepository,
            signingFieldMapper,
            signingFieldValueRepository,
            signingFieldValueMapper,
            signatureEvidenceRepository,
            signatureEvidenceMapper);
        this.signingWorkflowRepository = signingWorkflowRepository;
        this.signatoryRepository = signatoryRepository;
        this.documentStorageReferenceRepository = documentStorageReferenceRepository;
        this.organisationRepository = organisationRepository;
        this.signingEventRepository = signingEventRepository;
        this.signingKeystore = signingKeystore;
        this.signingProperties = signingProperties;
        this.signingEventRecorder = signingEventRecorder;
    }

    /**
     * @see bw.co.starsign.signing.PdfSigningEngine#renderFields(String, String)
     */
    @Override
    protected DocumentArtifactDTO handleRenderFields(String workflowId, String signatoryId)
        throws Exception
    {
        SigningWorkflow workflow = workflow(workflowId);
        Signatory signatory = signatoryRepository.findById(uuid(signatoryId, "signatory"))
                .filter(candidate -> candidate.getWorkflow().getId().equals(workflow.getId()))
                .orElseThrow(() -> new PdfSigningEngineException("The signatory does not belong to the signing request"));
        DocumentVersion current = SigningDocuments.currentVersion(workflow);
        DocumentArtifact source = requirePdf(current);

        Map<UUID, SigningFieldValue> values = signingFieldValueRepository.findBySignatory_Id(signatory.getId()).stream()
                .collect(Collectors.toMap(value -> value.getField().getId(), value -> value, (a, b) -> b));
        SignatureEvidence evidence = signatory.getEvidence();
        byte[] signatureImage = evidence != null && evidence.getSignatureImage() != null
                ? read(evidence.getSignatureImage().getObjectId())
                : null;
        String typedName = evidence == null ? null : evidence.getTypedName();

        byte[] stamped;
        try (PDDocument document = Loader.loadPDF(read(source.getStorageReference().getObjectId())))
        {
            PDImageXObject image = null;
            Map<Integer, List<PdfFieldStamper.Stamp>> pages = new LinkedHashMap<>();
            for (SigningField field : SigningDocuments.fieldsOf(signatory))
            {
                SigningFieldValue value = values.get(field.getId());
                if (value == null || field.getPageNumber() < 1 || field.getPageNumber() > document.getNumberOfPages())
                {
                    continue;
                }
                PdfFieldStamper.Stamp stamp;
                if (SigningDocuments.isSignature(field))
                {
                    if (signatureImage == null && value.getSignatureImage() != null)
                    {
                        signatureImage = read(value.getSignatureImage().getObjectId());
                    }
                    if (signatureImage != null && image == null)
                    {
                        image = PDImageXObject.createFromByteArray(document, signatureImage, "signature");
                    }
                    String text = StringUtils.defaultIfBlank(typedName,
                            StringUtils.startsWith(value.getValueText(), "signature:") ? null : value.getValueText());
                    if (field.getFieldType() == FieldType.INITIALS && image == null)
                    {
                        text = initials(text);
                    }
                    stamp = new PdfFieldStamper.Stamp(field, text, false, image);
                }
                else if (field.getFieldType() == FieldType.DATE)
                {
                    stamp = new PdfFieldStamper.Stamp(field, PdfFieldStamper.dateText(field, value.getValueDate()), false, null);
                }
                else
                {
                    stamp = new PdfFieldStamper.Stamp(field, value.getValueText(), value.isValueBoolean(), null);
                }
                pages.computeIfAbsent(field.getPageNumber(), page -> new ArrayList<>()).add(stamp);
            }
            for (Map.Entry<Integer, List<PdfFieldStamper.Stamp>> entry : pages.entrySet())
            {
                PDPage page = document.getPage(entry.getKey() - 1);
                try (PDPageContentStream content = new PDPageContentStream(document, page,
                        PDPageContentStream.AppendMode.APPEND, true, true))
                {
                    for (PdfFieldStamper.Stamp stamp : entry.getValue())
                    {
                        PdfFieldStamper.draw(content, page, stamp);
                    }
                }
                markUpdated(page);
            }
            stamped = saveIncremental(document);
        }

        DocumentArtifact artifact = newVersion(workflow.getDocument(), current, source, stamped,
                ArtifactType.INTERMEDIATE_SIGNED, "Signed by " + signatory.getFullName(), visualAssurance(workflow));
        return toDTO(artifact);
    }

    /**
     * @see bw.co.starsign.signing.PdfSigningEngine#generateFinalDocument(String)
     */
    @Override
    protected DocumentArtifactDTO handleGenerateFinalDocument(String workflowId)
        throws Exception
    {
        SigningWorkflow workflow = workflow(workflowId);
        // Finalisation is retried after failures: a document already produced is the answer
        if (workflow.getFinalArtifact() != null)
        {
            return toDTO(workflow.getFinalArtifact());
        }
        DocumentVersion current = SigningDocuments.currentVersion(workflow);
        DocumentArtifact source = requirePdf(current);

        byte[] completed;
        try (PDDocument document = Loader.loadPDF(read(source.getStorageReference().getObjectId())))
        {
            // Nothing fillable is left once everyone has signed: the field values are already stamped into the
            // pages, so the form layer is flattened before the seal locks the document
            boolean flattened = flatten(document);
            EvidencePages.append(document, evidenceLines(workflow, "Signing certificate", false));
            completed = flattened ? saveFull(document) : saveIncremental(document);
        }
        DocumentArtifact artifact = newVersion(workflow.getDocument(), current, source, completed,
                ArtifactType.FINAL_SIGNED, "Completed: all signatures applied", visualAssurance(workflow));

        OrganisationSigningPolicy policy = SigningDocuments.policy(workflow);
        if (policy != null && policy.isDigitalSignatureEnabled())
        {
            artifact = seal(artifact, policy.getCertificateAlias(),
                    "Completed signing request " + StringUtils.defaultString(workflow.getReferenceNumber()), workflow,
                    true);
        }
        workflow.setFinalArtifact(artifact);
        workflow.setFinalDocumentSha256(artifact.getSha256());
        signingEventRecorder.auditDocument(AuditEventType.DOCUMENT_GENERATED, workflow.getDocument(), workflow,
                "Final signed document generated (SHA-256 " + artifact.getSha256() + ", "
                        + artifact.getAssuranceLevel() + ")", "DocumentArtifact", artifact.getId().toString());
        return toDTO(artifact);
    }

    /**
     * @see bw.co.starsign.signing.PdfSigningEngine#applyDigitalSignature(String, String, String)
     */
    @Override
    protected DocumentArtifactDTO handleApplyDigitalSignature(String artifactId, String certificateAlias, String reason)
        throws Exception
    {
        DocumentArtifact source = artifact(artifactId);
        if (!PDF.equalsIgnoreCase(source.getContentType()))
        {
            throw new PdfSigningEngineException("Only PDF documents can be signed digitally");
        }
        DocumentArtifact sealed = seal(source, certificateAlias, StringUtils.defaultIfBlank(reason, "Sealed by StarSign"), null,
                false);
        return toDTO(sealed);
    }

    /**
     * @see bw.co.starsign.signing.PdfSigningEngine#validateDigitalSignatures(String)
     */
    @Override
    protected DigitalSignatureValidationDTO handleValidateDigitalSignatures(String artifactId)
        throws Exception
    {
        DocumentArtifact artifact = artifact(artifactId);
        byte[] pdf = read(artifact.getStorageReference().getObjectId());
        DigitalSignatureValidationDTO result = new DigitalSignatureValidationDTO();
        List<String> messages = new ArrayList<>();
        List<String> subjects = new ArrayList<>();
        boolean valid = true;
        boolean timestamped = true;
        boolean coversWholeDocument = false;

        List<PDSignature> signatures;
        int certificationLevel;
        boolean validationData;
        try (PDDocument document = Loader.loadPDF(pdf))
        {
            certificationLevel = PadesSigner.certificationLevel(document);
            validationData = document.getDocumentCatalog().getCOSObject()
                    .getCOSDictionary(org.apache.pdfbox.cos.COSName.getPDFName("DSS")) != null;
            signatures = document.getSignatureDictionaries();
            for (int index = 0; index < signatures.size(); index++)
            {
                PDSignature signature = signatures.get(index);
                String name = StringUtils.defaultIfBlank(signature.getName(), "Signature " + (index + 1));
                try
                {
                    byte[] contents = signature.getContents(pdf);
                    byte[] signedContent = signature.getSignedContent(pdf);
                    if ("ETSI.RFC3161".equals(signature.getSubFilter()))
                    {
                        // A document timestamp: the token's imprint must be the hash of the covered bytes
                        name = "Document timestamp";
                        org.bouncycastle.tsp.TimeStampToken token =
                                new org.bouncycastle.tsp.TimeStampToken(new CMSSignedData(contents));
                        byte[] expected = java.security.MessageDigest.getInstance(
                                token.getTimeStampInfo().getMessageImprintAlgOID().getId()).digest(signedContent);
                        @SuppressWarnings("unchecked")
                        Collection<X509CertificateHolder> tsaCertificates = token.getCertificates()
                                .getMatches(token.getSID());
                        if (!java.util.Arrays.equals(expected, token.getTimeStampInfo().getMessageImprintDigest())
                                || tsaCertificates.isEmpty()
                                || !token.isSignatureValid(new JcaSimpleSignerInfoVerifierBuilder()
                                        .build(tsaCertificates.iterator().next())))
                        {
                            valid = false;
                            messages.add(name + ": it does not match the document");
                        }
                        else
                        {
                            messages.add(name + ": valid, " + token.getTimeStampInfo().getGenTime().toInstant());
                        }
                        int[] byteRange = signature.getByteRange();
                        if (index == signatures.size() - 1)
                        {
                            coversWholeDocument = byteRange.length == 4
                                    && (long) byteRange[2] + byteRange[3] == pdf.length;
                        }
                        continue;
                    }
                    CMSSignedData cms = new CMSSignedData(new CMSProcessableByteArray(signedContent), contents);
                    SignerInformation signer = cms.getSignerInfos().getSigners().iterator().next();
                    @SuppressWarnings("unchecked")
                    Collection<X509CertificateHolder> matches = cms.getCertificates().getMatches(signer.getSID());
                    if (matches.isEmpty())
                    {
                        valid = false;
                        messages.add(name + ": the signing certificate is not embedded");
                        continue;
                    }
                    X509CertificateHolder holder = matches.iterator().next();
                    X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(holder);
                    subjects.add(certificate.getSubjectX500Principal().getName());
                    if (!signer.verify(new JcaSimpleSignerInfoVerifierBuilder().build(holder)))
                    {
                        valid = false;
                        messages.add(name + ": the signature does not match the signed content");
                    }
                    if (signature.getSignDate() != null && !holder.isValidOn(signature.getSignDate().getTime()))
                    {
                        valid = false;
                        messages.add(name + ": the certificate was not valid when the document was signed");
                    }
                    if (!trusted(certificate, cms.getCertificates()))
                    {
                        messages.add(name + ": the certificate (" + PadesSigner.commonName(certificate)
                                + ") is not issued by a trusted certification authority");
                    }
                    if (!timestampValid(signer, messages, name))
                    {
                        timestamped = false;
                    }
                    int[] byteRange = signature.getByteRange();
                    if (index == signatures.size() - 1)
                    {
                        coversWholeDocument = byteRange.length == 4 && (long) byteRange[2] + byteRange[3] == pdf.length;
                    }
                }
                catch (Exception e)
                {
                    // A damaged signature makes the document invalid; the reason is reported, not thrown
                    valid = false;
                    messages.add(name + ": the signature could not be read (" + e.getMessage() + ")");
                }
            }
        }
        if (signatures.isEmpty())
        {
            valid = false;
            timestamped = false;
            messages.add("The document has no digital signatures. Visual electronic signatures are not digital signatures.");
        }
        else if (!coversWholeDocument)
        {
            messages.add("The document was changed after its last digital signature");
        }
        if (certificationLevel == PadesSigner.NO_CHANGES_ALLOWED)
        {
            messages.add("Certified: no changes are allowed; PDF readers flag any later edit as invalidating it");
        }
        else if (certificationLevel > 0)
        {
            messages.add("Certified with DocMDP permissions P=" + certificationLevel);
        }
        else if (!signatures.isEmpty())
        {
            messages.add("Not certified: approval signature only");
        }
        messages.add(validationData
                ? "Long-term validation data (DSS) is embedded"
                : "No long-term validation data (DSS) is embedded");
        messages.add("Revocation of the signing certificates was not checked");

        result.setValid(valid);
        result.setSignatureCount(signatures.size());
        result.setSignerSubjects(subjects);
        result.setTimestamped(timestamped);
        result.setRevocationChecked(false);
        result.setCoversWholeDocument(coversWholeDocument);
        result.setMessages(messages);
        return result;
    }

    /**
     * @see bw.co.starsign.signing.PdfSigningEngine#getCertificates(String)
     */
    @Override
    protected List<CertificateInfoDTO> handleGetCertificates(String organisationId)
        throws Exception
    {
        Organisation organisation = organisationRepository.findById(uuid(organisationId, "organisation"))
                .orElseThrow(() -> new PdfSigningEngineException("The organisation does not exist"));
        KeyStore keyStore = signingKeystore.keyStore();
        if (keyStore == null)
        {
            return new ArrayList<>();
        }
        // The organisation signs with its own alias when its policy names one, else with the platform default
        String chosen = organisation.getSigningPolicy() == null ? null : organisation.getSigningPolicy().getCertificateAlias();
        Set<String> aliases = StringUtils.isBlank(chosen)
                ? Set.of(signingKeystore.defaultAlias())
                : Set.of(signingKeystore.defaultAlias(), chosen.trim());
        List<CertificateInfoDTO> certificates = new ArrayList<>();
        for (String alias : Collections.list(keyStore.aliases()))
        {
            if (!aliases.contains(alias) || !(keyStore.getCertificate(alias) instanceof X509Certificate certificate))
            {
                continue;
            }
            CertificateInfoDTO info = new CertificateInfoDTO();
            info.setAlias(alias);
            info.setSubject(certificate.getSubjectX500Principal().getName());
            info.setIssuer(certificate.getIssuerX500Principal().getName());
            info.setSerialNumber(certificate.getSerialNumber().toString(16));
            info.setThumbprint(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded())));
            info.setValidFrom(LocalDateTime.ofInstant(certificate.getNotBefore().toInstant(), ZoneId.systemDefault()));
            info.setValidTo(LocalDateTime.ofInstant(certificate.getNotAfter().toInstant(), ZoneId.systemDefault()));
            info.setTrusted(trusted(certificate, keyStore.getCertificateChain(alias)));
            certificates.add(info);
        }
        certificates.sort(Comparator.comparing(CertificateInfoDTO::getAlias));
        return certificates;
    }

    /**
     * @see bw.co.starsign.signing.PdfSigningEngine#generateEvidenceReport(String)
     */
    @Override
    protected DocumentArtifactDTO handleGenerateEvidenceReport(String workflowId)
        throws Exception
    {
        SigningWorkflow workflow = workflow(workflowId);
        DocumentVersion current = SigningDocuments.currentVersion(workflow);
        if (current == null)
        {
            throw new PdfSigningEngineException("The signing request has no document");
        }
        byte[] report;
        try (PDDocument document = new PDDocument())
        {
            EvidencePages.append(document, evidenceLines(workflow, "Signing evidence report", true));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            report = out.toByteArray();
        }
        String sha256 = SigningTokens.sha256(report);
        DocumentStorageReference reference = store(workflow.getOrganisation(), StorageNamespace.EVIDENCE,
                "evidence-" + StringUtils.defaultIfBlank(workflow.getReferenceNumber(), workflow.getId().toString()) + ".pdf",
                report, sha256);
        LocalDateTime now = LocalDateTime.now();
        DocumentArtifact artifact = DocumentArtifact.Factory.newInstance();
        artifact.setArtifactType(ArtifactType.EVIDENCE_REPORT);
        artifact.setSha256(sha256);
        artifact.setContentType(PDF);
        artifact.setSizeBytes(report.length);
        artifact.setGeneratedAt(now);
        artifact.setImmutable(true);
        // Evidence about the signatures, not a signature itself
        artifact.setAssuranceLevel(null);
        artifact.setVersion(current);
        artifact.setStorageReference(reference);
        artifact.setCreatedAt(now);
        artifact.setCreatedBy(GENERATED_BY);
        artifact = documentArtifactRepository.save(artifact);
        current.getArtifacts().add(artifact);
        signingEventRecorder.auditDocument(AuditEventType.DOCUMENT_GENERATED, workflow.getDocument(), workflow,
                "Signing evidence report generated (SHA-256 " + sha256 + ")", "DocumentArtifact", artifact.getId().toString());
        return toDTO(artifact);
    }

    // ---------------------------------------------------------------- digital signatures

    /** Applies a PAdES signature to the artifact's PDF, producing a new version (append-only). */
    /**
     * Applies the platform's PAdES signature. The seal of a completed document ({@code finalSeal}) also certifies it
     * with "no changes allowed" (app.signing.certify-final) and adds long-term validation data (app.signing.ltv):
     * the DSS with certificates and revocation data, then, with a timestamp authority, a document timestamp and
     * the validation data of that timestamp (PAdES-B-LTA).
     */
    private DocumentArtifact seal(DocumentArtifact source, String certificateAlias, String reason, SigningWorkflow workflow,
            boolean finalSeal)
        throws IOException, java.security.GeneralSecurityException
    {
        KeyStore keyStore = signingKeystore.requireKeyStore();
        String alias = signingKeystore.alias(certificateAlias);
        PadesSigner signer = new PadesSigner(keyStore, signingKeystore.password(), signingProperties.getTsaUrl());
        Document document = source.getVersion().getDocument();
        String location = document.getOrganisation() == null ? null : document.getOrganisation().getName();
        PadesSigner.Applied applied = signer.sign(read(source.getStorageReference().getObjectId()), alias, reason, location,
                finalSeal && signingProperties.isCertifyFinal());

        byte[] pdf = applied.pdf();
        List<String> notes = new ArrayList<>();
        notes.add(applied.certified() ? "certified: no changes allowed (DocMDP P=1)" : "approval signature");
        if (applied.timestampAuthority() != null)
        {
            notes.add("signature timestamped by " + applied.timestampAuthority());
        }
        if (finalSeal && signingProperties.isCertifyFinal() && !applied.certified())
        {
            notes.add("not certified: the document already carried a digital signature");
        }
        if (finalSeal && signingProperties.isLtv())
        {
            // Validation data is best effort: the certified document stands even if a CA service is unreachable
            LtvEnricher enricher = new LtvEnricher(java.time.Duration.ofSeconds(
                    Math.max(1, signingProperties.getRevocationTimeoutSeconds())));
            try
            {
                LtvEnricher.Result ltv = enricher.enrich(pdf);
                pdf = ltv.pdf();
                notes.add("validation data: " + ltv.certificates() + " certificate(s), " + ltv.ocspResponses()
                        + " OCSP, " + ltv.crls() + " CRL");
                ltv.gaps().forEach(notes::add);
                if (StringUtils.isNotBlank(signingProperties.getTsaUrl()))
                {
                    PadesSigner.Timestamped stamped = signer.documentTimestamp(pdf);
                    pdf = enricher.enrich(stamped.pdf()).pdf();
                    notes.add("document timestamp by " + stamped.timestampAuthority() + " at "
                            + (stamped.timestampedAt() == null ? "?" : stamped.timestampedAt().toInstant()));
                }
                else
                {
                    notes.add("no document timestamp: no timestamp authority is configured");
                }
            }
            catch (IOException | RuntimeException e)
            {
                logger.warn("Long-term validation data could not be added to document {}: {}", document.getId(),
                        e.getMessage());
                notes.add("long-term validation data not added: " + e.getMessage());
            }
        }

        DocumentArtifact sealed = newVersion(document, source.getVersion(), source, pdf, source.getArtifactType(),
                "Digitally signed with certificate " + PadesSigner.commonName(applied.certificate())
                        + (applied.certified() ? " (certified, no changes allowed)" : ""),
                SignatureAssuranceLevel.ADVANCED_DIGITAL);
        signingEventRecorder.auditDocument(AuditEventType.DOCUMENT_GENERATED, document, workflow,
                "PAdES signature applied with certificate " + applied.certificate().getSubjectX500Principal().getName()
                        + "; " + String.join("; ", notes),
                "DocumentArtifact", sealed.getId().toString());
        return sealed;
    }

    /**
     * Flattens the document's form fields into the page content and removes the form layer, unless the PDF carries
     * a digital signature (flattening would break it). Returns whether anything was flattened.
     */
    static boolean flatten(PDDocument document) throws IOException
    {
        org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm form = document.getDocumentCatalog().getAcroForm();
        if (form == null || !document.getSignatureDictionaries().isEmpty())
        {
            return false;
        }
        if (form.getFields().isEmpty())
        {
            document.getDocumentCatalog().setAcroForm(null);
            return true;
        }
        form.setNeedAppearances(false);
        form.refreshAppearances();
        form.flatten();
        document.getDocumentCatalog().setAcroForm(null);
        return true;
    }

    /** A full (non-incremental) save: used after flattening, when the document has no signature to preserve. */
    private static byte[] saveFull(PDDocument document) throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        document.save(out);
        return out.toByteArray();
    }

    /** Whether the certificate chains up to a certification authority the JVM trusts. */
    private static boolean trusted(X509Certificate certificate, Object chainSource)
    {
        try
        {
            if (certificate.getSubjectX500Principal().equals(certificate.getIssuerX500Principal()))
            {
                return false;
            }
            certificate.checkValidity();
            List<X509Certificate> chain = new ArrayList<>();
            chain.add(certificate);
            if (chainSource instanceof java.security.cert.Certificate[] array)
            {
                Arrays.stream(array).skip(1).filter(X509Certificate.class::isInstance)
                        .map(X509Certificate.class::cast).forEach(chain::add);
            }
            else if (chainSource instanceof Store<?> store)
            {
                for (Object holder : store.getMatches(null))
                {
                    X509Certificate other = new JcaX509CertificateConverter().getCertificate((X509CertificateHolder) holder);
                    if (!other.equals(certificate))
                    {
                        chain.add(other);
                    }
                }
            }
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init((KeyStore) null);
            Set<TrustAnchor> anchors = Arrays.stream(factory.getTrustManagers())
                    .filter(X509TrustManager.class::isInstance)
                    .flatMap(manager -> Arrays.stream(((X509TrustManager) manager).getAcceptedIssuers()))
                    .map(issuer -> new TrustAnchor(issuer, null))
                    .collect(Collectors.toSet());
            PKIXParameters parameters = new PKIXParameters(anchors);
            // Revocation is not checked here (no OCSP/CRL access); validation results say so
            parameters.setRevocationEnabled(false);
            CertPathValidator.getInstance("PKIX").validate(
                    CertificateFactory.getInstance("X.509").generateCertPath(chain), parameters);
            return true;
        }
        catch (Exception e)
        {
            // Any failure to build a trusted path means "not trusted", which is the answer asked for
            return false;
        }
    }

    /** Checks an RFC 3161 signature timestamp, if present: its imprint must cover the signature value. */
    private static boolean timestampValid(SignerInformation signer, List<String> messages, String name)
    {
        if (signer.getUnsignedAttributes() == null)
        {
            return false;
        }
        Attribute attribute = signer.getUnsignedAttributes().get(PKCSObjectIdentifiers.id_aa_signatureTimeStampToken);
        if (attribute == null)
        {
            return false;
        }
        try
        {
            TimeStampToken token = new TimeStampToken(new CMSSignedData(
                    attribute.getAttrValues().getObjectAt(0).toASN1Primitive().getEncoded()));
            byte[] imprint = MessageDigest.getInstance(
                    token.getTimeStampInfo().getMessageImprintAlgOID().getId()).digest(signer.getSignature());
            if (!Arrays.equals(imprint, token.getTimeStampInfo().getMessageImprintDigest()))
            {
                messages.add(name + ": the timestamp does not cover the signature");
                return false;
            }
            @SuppressWarnings("unchecked")
            Collection<X509CertificateHolder> tsa = token.getCertificates().getMatches(token.getSID());
            if (!tsa.isEmpty() && !token.isSignatureValid(new JcaSimpleSignerInfoVerifierBuilder().build(tsa.iterator().next())))
            {
                messages.add(name + ": the timestamp signature is not valid");
                return false;
            }
            return true;
        }
        catch (Exception e)
        {
            messages.add(name + ": the timestamp could not be read (" + e.getMessage() + ")");
            return false;
        }
    }

    // ---------------------------------------------------------------- versions and artifacts

    /** Stores the PDF as the next version of the document with one artifact. */
    private DocumentArtifact newVersion(Document document, DocumentVersion previous, DocumentArtifact previousArtifact,
        byte[] pdf, ArtifactType type, String reason, SignatureAssuranceLevel assuranceLevel) throws IOException
    {
        LocalDateTime now = LocalDateTime.now();
        String sha256 = SigningTokens.sha256(pdf);
        int versionNumber = document.getVersions().stream().mapToInt(DocumentVersion::getVersionNumber).max().orElse(0) + 1;

        DocumentVersion version = DocumentVersion.Factory.newInstance();
        version.setVersionNumber(versionNumber);
        version.setSha256(sha256);
        version.setReason(StringUtils.abbreviate(reason, 255));
        version.setSourceVersionNumber(previous == null ? null : previous.getVersionNumber());
        version.setGeneratedBy(GENERATED_BY);
        version.setImmutable(true);
        version.setDocument(document);
        version.setCreatedAt(now);
        version.setCreatedBy(GENERATED_BY);
        try (PDDocument parsed = Loader.loadPDF(pdf))
        {
            version.setPageCount(parsed.getNumberOfPages());
            for (int index = 0; index < parsed.getNumberOfPages(); index++)
            {
                version.getPages().add(page(parsed.getPage(index), index + 1, version, now));
            }
        }
        document.getVersions().add(version);
        document.setCurrentVersionNumber(versionNumber);
        version = documentVersionRepository.save(version);

        DocumentStorageReference reference = store(document.getOrganisation(), StorageNamespace.ARTIFACTS,
                StringUtils.defaultIfBlank(document.getReferenceNumber(), document.getId().toString())
                        + "-v" + versionNumber + ".pdf", pdf, sha256);
        DocumentArtifact artifact = DocumentArtifact.Factory.newInstance();
        artifact.setArtifactType(type);
        artifact.setSha256(sha256);
        artifact.setContentType(PDF);
        artifact.setSizeBytes(pdf.length);
        artifact.setGeneratedAt(now);
        artifact.setImmutable(true);
        artifact.setPreviousArtifactSha256(previousArtifact == null ? null : previousArtifact.getSha256());
        artifact.setAssuranceLevel(assuranceLevel);
        artifact.setVersion(version);
        artifact.setStorageReference(reference);
        artifact.setCreatedAt(now);
        artifact.setCreatedBy(GENERATED_BY);
        artifact = documentArtifactRepository.save(artifact);
        version.getArtifacts().add(artifact);
        return artifact;
    }

    private static DocumentPage page(PDPage pdfPage, int number, DocumentVersion version, LocalDateTime now)
    {
        PDRectangle media = pdfPage.getMediaBox();
        PDRectangle crop = pdfPage.getCropBox();
        DocumentPage page = DocumentPage.Factory.newInstance();
        page.setPageNumber(number);
        page.setWidthPoints(media.getWidth());
        page.setHeightPoints(media.getHeight());
        page.setRotation(pdfPage.getRotation());
        page.setCropBoxX(crop.getLowerLeftX());
        page.setCropBoxY(crop.getLowerLeftY());
        page.setCropBoxWidth(crop.getWidth());
        page.setCropBoxHeight(crop.getHeight());
        page.setUserUnit(pdfPage.getUserUnit());
        page.setVersion(version);
        page.setCreatedAt(now);
        page.setCreatedBy(GENERATED_BY);
        return page;
    }

    private DocumentStorageReference store(Organisation organisation, StorageNamespace namespace, String fileName,
        byte[] bytes, String sha256)
    {
        StorageWriteRequestDTO write = new StorageWriteRequestDTO();
        write.setOrganisationId(organisation == null ? null : organisation.getId().toString());
        write.setNamespace(namespace);
        write.setContentType(PDF);
        write.setContentLength((long) bytes.length);
        write.setOriginalFileName(fileName);
        write.setExpectedSha256(sha256);
        DocumentStorageReferenceDTO stored = storageService.store(write, new ByteArrayInputStream(bytes));
        return documentStorageReferenceRepository.findByObjectId(stored.getObjectId())
                .orElseThrow(() -> new PdfSigningEngineException("The stored document could not be found again"));
    }

    private byte[] read(String objectId) throws IOException
    {
        try (InputStream in = storageService.openStream(objectId))
        {
            return in.readAllBytes();
        }
    }

    /** Writes only the changes after the original bytes, so that earlier signatures stay valid. */
    private static byte[] saveIncremental(PDDocument document) throws IOException
    {
        document.getDocumentCatalog().getCOSObject().setNeedToBeUpdated(true);
        document.getDocumentCatalog().getPages().getCOSObject().setNeedToBeUpdated(true);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        document.saveIncremental(out);
        return out.toByteArray();
    }

    private static void markUpdated(PDPage page)
    {
        page.getCOSObject().setNeedToBeUpdated(true);
        if (page.getResources() != null)
        {
            page.getResources().getCOSObject().setNeedToBeUpdated(true);
        }
    }

    private static DocumentArtifact requirePdf(DocumentVersion version)
    {
        DocumentArtifact pdf = SigningDocuments.pdfArtifact(version);
        if (pdf == null)
        {
            throw new PdfSigningEngineException("The document has no PDF to sign");
        }
        return pdf;
    }

    /** The visual signatures' level: verified only when every signer was verified; never a digital level. */
    private static SignatureAssuranceLevel visualAssurance(SigningWorkflow workflow)
    {
        List<SignatureEvidence> evidence = workflow.getSignatories().stream()
                .map(Signatory::getEvidence)
                .filter(item -> item != null)
                .toList();
        return !evidence.isEmpty() && evidence.stream()
                .allMatch(item -> item.getAssuranceLevel() == SignatureAssuranceLevel.ELECTRONIC_VERIFIED)
                ? SignatureAssuranceLevel.ELECTRONIC_VERIFIED
                : SignatureAssuranceLevel.ELECTRONIC_BASIC;
    }

    private static String initials(String name)
    {
        if (StringUtils.isBlank(name))
        {
            return name;
        }
        StringBuilder initials = new StringBuilder();
        for (String part : StringUtils.split(name.trim()))
        {
            initials.append(Character.toUpperCase(part.charAt(0))).append('.');
        }
        return initials.toString();
    }

    // ---------------------------------------------------------------- evidence text

    /**
     * The certificate appended to the completed PDF ({@code detailed} false) is a short summary: who signed, when,
     * how they were verified and from where, plus the original document's hash. The evidence report
     * ({@code detailed} true) adds every recorded detail: per-signer hashes, consent and intent times, device,
     * time zone, assurance and the event log.
     */
    private List<EvidencePages.Line> evidenceLines(SigningWorkflow workflow, String title, boolean detailed)
    {
        List<EvidencePages.Line> lines = new ArrayList<>();
        Document document = workflow.getDocument();
        lines.add(EvidencePages.Line.title(title));
        lines.add(EvidencePages.Line.field("Signing request", StringUtils.defaultString(workflow.getReferenceNumber())
                + " - " + StringUtils.defaultString(workflow.getTitle())));
        lines.add(EvidencePages.Line.field("Document", document == null ? null : document.getTitle()));
        lines.add(EvidencePages.Line.field("Organisation",
                workflow.getOrganisation() == null ? null : workflow.getOrganisation().getName()));
        lines.add(EvidencePages.Line.field("Original document SHA-256", StringUtils.defaultIfBlank(
                workflow.getSourceDocumentSha256(), document == null ? null : document.getOriginalSha256())));
        if (detailed)
        {
            lines.add(EvidencePages.Line.field("Status", workflow.getStatus()));
        }
        lines.add(EvidencePages.Line.field("Sent for signing", when(workflow.getInitiatedAt())));
        lines.add(EvidencePages.Line.field("Completed", when(workflow.getCompletedAt())));

        lines.add(EvidencePages.Line.heading("Signatories"));
        List<Signatory> signatories = new ArrayList<>(workflow.getSignatories());
        signatories.sort(Comparator.comparingInt(Signatory::getSigningOrder));
        for (Signatory signatory : signatories)
        {
            lines.add(EvidencePages.Line.heading(signatory.getFullName() + " <" + signatory.getEmailAddress() + ">"));
            SignatureEvidence evidence = signatory.getEvidence();
            if (signatory.getSignedAt() != null)
            {
                lines.add(EvidencePages.Line.field("Signed", when(signatory.getSignedAt())
                        + (evidence == null || evidence.getCaptureMethod() == null ? ""
                                : " (" + humanise(evidence.getCaptureMethod()).toLowerCase(java.util.Locale.ROOT) + " signature)")));
            }
            else if (signatory.getDeclinedAt() != null)
            {
                lines.add(EvidencePages.Line.field("Declined", when(signatory.getDeclinedAt())));
                lines.add(EvidencePages.Line.field("Reason", signatory.getDeclineReason()));
            }
            else
            {
                lines.add(EvidencePages.Line.field("Status", humanise(signatory.getStatus())));
            }
            if (evidence == null)
            {
                continue;
            }
            lines.add(EvidencePages.Line.field("Identity verified by", humanise(evidence.getVerificationMethod())));
            lines.add(EvidencePages.Line.field("IP address", evidence.getIpAddress()));
            if (!detailed)
            {
                continue;
            }
            lines.add(EvidencePages.Line.field("Assurance level", evidence.getAssuranceLevel()));
            lines.add(EvidencePages.Line.field("Consent accepted", when(evidence.getConsentAcceptedAt())));
            lines.add(EvidencePages.Line.field("Intent confirmed", when(evidence.getIntentConfirmedAt())));
            lines.add(EvidencePages.Line.field("Device", evidence.getUserAgent()));
            lines.add(EvidencePages.Line.field("Time zone", evidence.getTimeZone()));
            lines.add(EvidencePages.Line.field("Document SHA-256 when signed", evidence.getDocumentSha256AtSigning()));
            lines.add(EvidencePages.Line.field("Signed version SHA-256", evidence.getSignedArtifactSha256()));
            lines.add(EvidencePages.Line.field("Field values SHA-256", evidence.getFieldValuesSha256()));
            lines.add(EvidencePages.Line.field("Evidence SHA-256", evidence.getEvidenceSha256()));
            lines.add(EvidencePages.Line.field("Digital signature", evidence.getDigitalSignatureStatus()));
        }
        lines.add(EvidencePages.Line.blank());
        lines.add(EvidencePages.Line.text(detailed
                ? "The signatures above are electronic signatures: images or typed names backed by the evidence "
                        + "recorded here. They are not certificate-based digital signatures; any digital signature of "
                        + "this document is shown by the PDF reader's signature panel."
                : "Electronic signatures. The full evidence and event log are kept by StarSign and available on "
                        + "request; any digital seal is shown in the PDF reader's signature panel."));

        if (detailed)
        {
            lines.add(EvidencePages.Line.heading("Event log"));
            Map<UUID, String> names = new HashMap<>();
            signatories.forEach(signatory -> names.put(signatory.getId(), signatory.getFullName()));
            for (SigningEvent event : signingEventRepository.findByWorkflow_IdOrderBySequenceNumberAsc(workflow.getId()))
            {
                lines.add(EvidencePages.Line.text("#" + event.getSequenceNumber() + "  " + event.getOccurredAt() + "  "
                        + event.getEventType()
                        + (event.getSignatory() == null ? "" : "  " + names.getOrDefault(event.getSignatory().getId(), ""))
                        + (event.getIpAddress() == null ? "" : "  from " + event.getIpAddress())
                        + (event.getDetails() == null ? "" : "  - " + SigningEventRecorder.detailsText(event.getDetails()))));
            }
        }
        lines.add(EvidencePages.Line.blank());
        lines.add(EvidencePages.Line.text("Generated by StarSign on " + when(LocalDateTime.now())));
        return lines;
    }

    private static final java.time.format.DateTimeFormatter WHEN =
            java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm:ss", java.util.Locale.ENGLISH);

    /** A readable timestamp with the server's time zone, e.g. "19 Nov 2026, 22:09:37 (Africa/Gaborone)". */
    private static String when(LocalDateTime time)
    {
        return time == null ? null : time.format(WHEN) + " (" + ZoneId.systemDefault() + ")";
    }

    /** EMAIL_OTP -> "Email OTP", DRAWN -> "drawn" style labels for enum values. */
    private static String humanise(Object value)
    {
        if (value == null)
        {
            return null;
        }
        String text = value.toString().replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
        text = text.replace("otp", "OTP").replace("sms", "SMS");
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    // ---------------------------------------------------------------- loading and mapping

    private SigningWorkflow workflow(String id)
    {
        return signingWorkflowRepository.findById(uuid(id, "signing request"))
                .orElseThrow(() -> new PdfSigningEngineException("The signing request does not exist"));
    }

    private DocumentArtifact artifact(String id)
    {
        DocumentArtifact artifact = documentArtifactRepository.findById(uuid(id, "document artifact"))
                .orElseThrow(() -> new PdfSigningEngineException("The document artifact does not exist"));
        if (artifact.getStorageReference() == null)
        {
            throw new PdfSigningEngineException("The document artifact has no stored content");
        }
        return artifact;
    }

    private static DocumentArtifactDTO toDTO(DocumentArtifact artifact)
    {
        DocumentArtifactDTO dto = new DocumentArtifactDTO();
        dto.setId(artifact.getId() == null ? null : artifact.getId().toString());
        dto.setArtifactType(artifact.getArtifactType());
        dto.setSha256(artifact.getSha256());
        dto.setContentType(artifact.getContentType());
        dto.setSizeBytes(artifact.getSizeBytes());
        dto.setPageNumber(artifact.getPageNumber());
        dto.setGeneratedAt(artifact.getGeneratedAt());
        dto.setObjectId(artifact.getStorageReference() == null ? null : artifact.getStorageReference().getObjectId());
        dto.setPreviousArtifactSha256(artifact.getPreviousArtifactSha256());
        dto.setAssuranceLevel(artifact.getAssuranceLevel());
        return dto;
    }

    private static UUID uuid(String id, String what)
    {
        try
        {
            return UUID.fromString(id.trim());
        }
        catch (IllegalArgumentException e)
        {
            throw new PdfSigningEngineException("'" + id + "' is not the id of a " + what);
        }
    }

}
