package bw.co.starsign.signing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Hashtable;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.DERSet;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.cms.AttributeTable;
import org.bouncycastle.asn1.cms.CMSAttributes;
import org.bouncycastle.asn1.ess.ESSCertIDv2;
import org.bouncycastle.asn1.ess.SigningCertificateV2;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.IssuerSerial;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.CMSException;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.DefaultSignedAttributeTableGenerator;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.SignerInformationStore;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.tsp.TSPAlgorithms;
import org.bouncycastle.tsp.TSPException;
import org.bouncycastle.tsp.TimeStampRequest;
import org.bouncycastle.tsp.TimeStampRequestGenerator;
import org.bouncycastle.tsp.TimeStampResponse;
import org.bouncycastle.tsp.TimeStampToken;

/**
 * Applies a PAdES baseline signature (ETSI.CAdES.detached, SHA-256, signing-certificate-v2, no CMS signing-time)
 * to a PDF as an incremental update, so earlier signatures stay valid. With a timestamp authority configured the
 * signature carries an RFC 3161 signature timestamp (PAdES-B-T).
 * <p>
 * A certifying signature ({@code certify}) also locks the document: DocMDP with P=1, so a PDF reader reports any
 * later change as invalidating it. PDF 2.0 / ETSI EN 319 142 still allow adding validation data (DSS) and document
 * timestamps afterwards, which is what {@link LtvEnricher} and {@link #documentTimestamp(byte[])} do.
 */
final class PadesSigner
{
    /** Room reserved for the CMS blob; a timestamp token and certificate chain need more. */
    private static final int SIGNATURE_SIZE = 0x4000;
    private static final int TIMESTAMPED_SIGNATURE_SIZE = 0x8000;

    /** What was applied, for the evidence. */
    record Applied(byte[] pdf, X509Certificate certificate, String timestampAuthority, Calendar timestampedAt,
            boolean certified)
    {
    }

    /** A document timestamp that was added. */
    record Timestamped(byte[] pdf, String timestampAuthority, Calendar timestampedAt)
    {
    }

    /** DocMDP access permissions: 1 = no changes at all are permitted. */
    static final int NO_CHANGES_ALLOWED = 1;

    private final KeyStore keyStore;
    private final char[] password;
    private final String tsaUrl;

    PadesSigner(KeyStore keyStore, char[] password, String tsaUrl)
    {
        this.keyStore = keyStore;
        this.password = password;
        this.tsaUrl = StringUtils.trimToNull(tsaUrl);
    }

    Applied sign(byte[] pdf, String alias, String reason, String location) throws IOException, GeneralSecurityException
    {
        return sign(pdf, alias, reason, location, false);
    }

    /**
     * @param certify lock the document with DocMDP P=1; ignored (an approval signature is applied) when the PDF
     *                already carries a signature, because only the first signature may certify a document
     */
    Applied sign(byte[] pdf, String alias, String reason, String location, boolean certify)
        throws IOException, GeneralSecurityException
    {
        if (!keyStore.isKeyEntry(alias))
        {
            throw new PdfSigningEngineException("The signing certificate '" + alias + "' does not exist in the keystore");
        }
        PrivateKey key = (PrivateKey) keyStore.getKey(alias, password);
        Certificate[] chain = keyStore.getCertificateChain(alias);
        X509Certificate certificate = (X509Certificate) chain[0];
        try
        {
            certificate.checkValidity();
        }
        catch (java.security.cert.CertificateException e)
        {
            throw new PdfSigningEngineException("The signing certificate '" + alias + "' is expired or not yet valid");
        }

        CmsSignature cms = new CmsSignature(key, chain);
        try (PDDocument document = Loader.loadPDF(pdf);
             SignatureOptions options = new SignatureOptions())
        {
            PDSignature signature = new PDSignature();
            signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
            signature.setSubFilter(PDSignature.SUBFILTER_ETSI_CADES_DETACHED);
            signature.setName(commonName(certificate));
            signature.setReason(reason);
            signature.setLocation(location);
            signature.setSignDate(Calendar.getInstance());
            boolean certified = certify && document.getSignatureDictionaries().isEmpty()
                    && document.getDocumentCatalog().getCOSObject().getCOSDictionary(COSName.PERMS) == null;
            if (certified)
            {
                lockDocument(document, signature, NO_CHANGES_ALLOWED);
            }
            options.setPreferredSignatureSize(tsaUrl == null ? SIGNATURE_SIZE : TIMESTAMPED_SIGNATURE_SIZE);
            document.addSignature(signature, cms, options);
            ByteArrayOutputStream out = new ByteArrayOutputStream(pdf.length + 64 * 1024);
            document.saveIncremental(out);
            return new Applied(out.toByteArray(), certificate, cms.timestampAuthority, cms.timestampedAt, certified);
        }
    }

    /**
     * Adds an RFC 3161 document timestamp (ETSI.RFC3161, /Type /DocTimeStamp) over the whole document as an
     * incremental update; with the DSS added before it this makes the document PAdES-B-LTA.
     */
    Timestamped documentTimestamp(byte[] pdf) throws IOException
    {
        if (tsaUrl == null)
        {
            throw new PdfSigningEngineException("No timestamp authority is configured");
        }
        Calendar[] at = new Calendar[1];
        try (PDDocument document = Loader.loadPDF(pdf);
             SignatureOptions options = new SignatureOptions())
        {
            PDSignature signature = new PDSignature();
            signature.setType(COSName.DOC_TIME_STAMP);
            signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
            signature.setSubFilter(COSName.getPDFName("ETSI.RFC3161"));
            options.setPreferredSignatureSize(TIMESTAMPED_SIGNATURE_SIZE);
            document.addSignature(signature, content -> {
                try
                {
                    byte[] imprint = MessageDigest.getInstance("SHA-256").digest(content.readAllBytes());
                    TimeStampToken token = requestTimestamp(tsaUrl, imprint);
                    at[0] = Calendar.getInstance();
                    at[0].setTime(token.getTimeStampInfo().getGenTime());
                    return token.getEncoded();
                }
                catch (GeneralSecurityException | TSPException e)
                {
                    throw new IOException("The document could not be timestamped: " + e.getMessage(), e);
                }
            }, options);
            ByteArrayOutputStream out = new ByteArrayOutputStream(pdf.length + 64 * 1024);
            document.saveIncremental(out);
            return new Timestamped(out.toByteArray(), tsaUrl, at[0]);
        }
    }

    /**
     * Makes {@code signature} the document's certification signature with the given DocMDP permissions
     * (ISO 32000-2, 12.8.2.2): a signature reference with the DocMDP transform, and /Perms /DocMDP in the catalog.
     */
    private static void lockDocument(PDDocument document, PDSignature signature, int permissions)
    {
        COSDictionary transformParameters = new COSDictionary();
        transformParameters.setItem(COSName.TYPE, COSName.getPDFName("TransformParams"));
        transformParameters.setInt(COSName.P, permissions);
        transformParameters.setName(COSName.V, "1.2");

        COSDictionary reference = new COSDictionary();
        reference.setItem(COSName.TYPE, COSName.getPDFName("SigRef"));
        reference.setItem(COSName.getPDFName("TransformMethod"), COSName.DOCMDP);
        reference.setItem(COSName.getPDFName("TransformParams"), transformParameters);

        COSArray references = new COSArray();
        references.add(reference);
        signature.getCOSObject().setItem(COSName.getPDFName("Reference"), references);

        COSDictionary catalog = document.getDocumentCatalog().getCOSObject();
        COSDictionary perms = new COSDictionary();
        perms.setItem(COSName.DOCMDP, signature.getCOSObject());
        catalog.setItem(COSName.PERMS, perms);
        catalog.setNeedToBeUpdated(true);
    }

    /** The DocMDP permission of a certified document (1-3), or 0 when it is not certified. */
    static int certificationLevel(PDDocument document)
    {
        COSDictionary perms = document.getDocumentCatalog().getCOSObject().getCOSDictionary(COSName.PERMS);
        COSDictionary docMdp = perms == null ? null : perms.getCOSDictionary(COSName.DOCMDP);
        COSArray references = docMdp == null ? null : docMdp.getCOSArray(COSName.getPDFName("Reference"));
        if (references == null)
        {
            return 0;
        }
        for (int i = 0; i < references.size(); i++)
        {
            if (references.getObject(i) instanceof COSDictionary reference
                    && COSName.DOCMDP.equals(reference.getCOSName(COSName.getPDFName("TransformMethod"))))
            {
                COSDictionary parameters = reference.getCOSDictionary(COSName.getPDFName("TransformParams"));
                return parameters == null ? 2 : parameters.getInt(COSName.P, 2);
            }
        }
        return 0;
    }

    /** Requests an RFC 3161 token for a SHA-256 imprint from the timestamp authority. */
    static TimeStampToken requestTimestamp(String tsaUrl, byte[] imprint) throws IOException, TSPException
    {
        TimeStampRequestGenerator requests = new TimeStampRequestGenerator();
        requests.setCertReq(true);
        TimeStampRequest request = requests.generate(TSPAlgorithms.SHA256, imprint,
                BigInteger.valueOf(new SecureRandom().nextLong()));

        HttpResponse<byte[]> response;
        try
        {
            response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build().send(
                    HttpRequest.newBuilder(URI.create(tsaUrl))
                            .timeout(Duration.ofSeconds(20))
                            .header("Content-Type", "application/timestamp-query")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(request.getEncoded()))
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the timestamp authority", e);
        }
        if (response.statusCode() != 200)
        {
            throw new IOException("The timestamp authority answered HTTP " + response.statusCode());
        }
        TimeStampResponse timeStampResponse = new TimeStampResponse(response.body());
        timeStampResponse.validate(request);
        TimeStampToken token = timeStampResponse.getTimeStampToken();
        if (token == null)
        {
            throw new IOException("The timestamp authority refused the request: " + timeStampResponse.getStatusString());
        }
        return token;
    }

    static String commonName(X509Certificate certificate)
    {
        String subject = certificate.getSubjectX500Principal().getName();
        for (String part : subject.split(","))
        {
            if (part.trim().startsWith("CN="))
            {
                return part.trim().substring(3);
            }
        }
        return subject;
    }

    /** Builds the detached CMS over the byte ranges PDFBox hands over. */
    private final class CmsSignature implements SignatureInterface
    {
        private final PrivateKey key;
        private final Certificate[] chain;
        private String timestampAuthority;
        private Calendar timestampedAt;

        CmsSignature(PrivateKey key, Certificate[] chain)
        {
            this.key = key;
            this.chain = chain;
        }

        @Override
        public byte[] sign(InputStream content) throws IOException
        {
            try
            {
                X509Certificate certificate = (X509Certificate) chain[0];
                String algorithm = "EC".equalsIgnoreCase(key.getAlgorithm()) ? "SHA256withECDSA" : "SHA256withRSA";
                ContentSigner signer = new JcaContentSignerBuilder(algorithm).build(key);

                JcaSignerInfoGeneratorBuilder signerInfo = new JcaSignerInfoGeneratorBuilder(
                        new JcaDigestCalculatorProviderBuilder().build());
                signerInfo.setSignedAttributeGenerator(new PadesAttributes(signingCertificateV2(certificate)));

                CMSSignedDataGenerator generator = new CMSSignedDataGenerator();
                generator.addSignerInfoGenerator(signerInfo.build(signer, certificate));
                generator.addCertificates(new JcaCertStore(Arrays.asList(chain)));
                CMSSignedData signed = generator.generate(new CMSProcessableByteArray(content.readAllBytes()), false);
                if (tsaUrl != null)
                {
                    signed = timestamp(signed);
                }
                return signed.getEncoded();
            }
            catch (GeneralSecurityException | OperatorCreationException | CMSException | TSPException e)
            {
                throw new IOException("The PDF could not be signed: " + e.getMessage(), e);
            }
        }

        /** Adds an RFC 3161 token over the signature value as an unsigned attribute. */
        private CMSSignedData timestamp(CMSSignedData signed) throws IOException, TSPException, GeneralSecurityException
        {
            SignerInformation signerInformation = signed.getSignerInfos().getSigners().iterator().next();
            byte[] imprint = MessageDigest.getInstance("SHA-256").digest(signerInformation.getSignature());
            TimeStampToken token = requestTimestamp(tsaUrl, imprint);

            ASN1EncodableVector unsigned = signerInformation.getUnsignedAttributes() == null
                    ? new ASN1EncodableVector()
                    : signerInformation.getUnsignedAttributes().toASN1EncodableVector();
            unsigned.add(new Attribute(PKCSObjectIdentifiers.id_aa_signatureTimeStampToken,
                    new DERSet(ASN1Primitive.fromByteArray(token.getEncoded()))));
            SignerInformation stamped = SignerInformation.replaceUnsignedAttributes(signerInformation,
                    new AttributeTable(unsigned));
            timestampAuthority = tsaUrl;
            timestampedAt = Calendar.getInstance();
            timestampedAt.setTime(token.getTimeStampInfo().getGenTime());
            return CMSSignedData.replaceSigners(signed, new SignerInformationStore(stamped));
        }
    }

    private static AttributeTable signingCertificateV2(X509Certificate certificate) throws GeneralSecurityException
    {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        IssuerSerial issuerSerial = new IssuerSerial(
                new GeneralNames(new GeneralName(X500Name.getInstance(certificate.getIssuerX500Principal().getEncoded()))),
                new ASN1Integer(certificate.getSerialNumber()));
        ESSCertIDv2 id = new ESSCertIDv2(new AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256), hash, issuerSerial);
        ASN1EncodableVector attributes = new ASN1EncodableVector();
        attributes.add(new Attribute(PKCSObjectIdentifiers.id_aa_signingCertificateV2,
                new DERSet(new SigningCertificateV2(new ESSCertIDv2[] {id}))));
        return new AttributeTable(attributes);
    }

    /** PAdES: the claimed signing time lives in the signature dictionary (/M), not in the CMS. */
    private static final class PadesAttributes extends DefaultSignedAttributeTableGenerator
    {
        PadesAttributes(AttributeTable attributes)
        {
            super(attributes);
        }

        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        protected Hashtable createStandardAttributeTable(Map parameters)
        {
            Hashtable table = super.createStandardAttributeTable(parameters);
            table.remove(CMSAttributes.signingTime);
            return table;
        }
    }
}
