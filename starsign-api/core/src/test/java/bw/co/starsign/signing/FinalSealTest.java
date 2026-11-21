package bw.co.starsign.signing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.tsp.TSPAlgorithms;
import org.bouncycastle.tsp.TimeStampRequest;
import org.bouncycastle.tsp.TimeStampResponseGenerator;
import org.bouncycastle.tsp.TimeStampToken;
import org.bouncycastle.tsp.TimeStampTokenGenerator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The seal of a completed document: flatten the form, certify with "no changes allowed", add long-term validation
 * data and a document timestamp, against a throwaway key and an in-process timestamp authority.
 */
class FinalSealTest
{
    private static final char[] PASSWORD = "test".toCharArray();
    private static KeyStore keyStore;
    private static HttpServer tsa;
    private static String tsaUrl;

    @BeforeAll
    static void setUp() throws Exception
    {
        KeyPair signing = keyPair();
        X509Certificate certificate = certificate("CN=StarSign Test Seal", signing, false);
        keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry("seal", signing.getPrivate(), PASSWORD, new Certificate[] {certificate});

        // An RFC 3161 timestamp authority on a local port
        KeyPair tsaKeys = keyPair();
        X509Certificate tsaCertificate = certificate("CN=StarSign Test TSA", tsaKeys, true);
        TimeStampTokenGenerator tokens = new TimeStampTokenGenerator(new JcaSignerInfoGeneratorBuilder(
                new JcaDigestCalculatorProviderBuilder().build())
                .build(new JcaContentSignerBuilder("SHA256withRSA").build(tsaKeys.getPrivate()), tsaCertificate),
                new JcaDigestCalculatorProviderBuilder().build().get(
                        new org.bouncycastle.asn1.x509.AlgorithmIdentifier(
                                org.bouncycastle.asn1.oiw.OIWObjectIdentifiers.idSHA1)),
                new org.bouncycastle.asn1.ASN1ObjectIdentifier("1.2.3.4.1"));
        tokens.addCertificates(new JcaCertStore(List.of(tsaCertificate)));
        TimeStampResponseGenerator responses = new TimeStampResponseGenerator(tokens, TSPAlgorithms.ALLOWED);
        tsa = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        long[] serial = {1};
        tsa.createContext("/tsa", exchange -> {
            try
            {
                TimeStampRequest request = new TimeStampRequest(exchange.getRequestBody().readAllBytes());
                byte[] body = responses.generate(request, BigInteger.valueOf(serial[0]++), new Date()).getEncoded();
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            catch (Exception e)
            {
                exchange.sendResponseHeaders(500, -1);
            }
            exchange.close();
        });
        tsa.start();
        tsaUrl = "http://127.0.0.1:" + tsa.getAddress().getPort() + "/tsa";
    }

    @AfterAll
    static void tearDown()
    {
        if (tsa != null)
        {
            tsa.stop(0);
        }
    }

    @Test
    void flattensTheFormSoNothingIsLeftToFill() throws Exception
    {
        try (PDDocument document = Loader.loadPDF(pdfWithField()))
        {
            assertNotNull(document.getDocumentCatalog().getAcroForm());

            assertTrue(PdfSigningEngineImpl.flatten(document));

            assertNull(document.getDocumentCatalog().getAcroForm());
            assertTrue(document.getPage(0).getAnnotations().stream()
                    .noneMatch(annotation -> annotation instanceof PDAnnotationWidget));
        }
    }

    @Test
    void certifiesWithNoChangesAllowedAndAddsLongTermValidation() throws Exception
    {
        byte[] pdf = flattened(pdfWithField());
        PadesSigner signer = new PadesSigner(keyStore, PASSWORD, tsaUrl);

        PadesSigner.Applied applied = signer.sign(pdf, "seal", "Completed signing request", "Test", true);
        assertTrue(applied.certified());
        assertNotNull(applied.timestampedAt(), "the signature carries a signature timestamp");

        LtvEnricher enricher = new LtvEnricher(Duration.ofSeconds(2));
        LtvEnricher.Result ltv = enricher.enrich(applied.pdf());
        assertEquals(2, ltv.certificates(), "the seal certificate and the TSA certificate");
        PadesSigner.Timestamped stamped = signer.documentTimestamp(ltv.pdf());
        byte[] sealed = enricher.enrich(stamped.pdf()).pdf();
        // -Dstarsign.sealSample=/path/sealed.pdf keeps the result, to open in a PDF reader
        String sample = System.getProperty("starsign.sealSample");
        if (sample != null)
        {
            java.nio.file.Files.write(java.nio.file.Path.of(sample), sealed);
        }

        try (PDDocument document = Loader.loadPDF(sealed))
        {
            assertEquals(PadesSigner.NO_CHANGES_ALLOWED, PadesSigner.certificationLevel(document));
            assertNotNull(document.getDocumentCatalog().getCOSObject().getCOSDictionary(COSName.getPDFName("DSS")));
            List<PDSignature> signatures = document.getSignatureDictionaries();
            assertEquals(2, signatures.size(), "the certification signature and the document timestamp");
            assertTrue(verifies(signatures.get(0), sealed), "the certification signature still verifies");
            assertTrue(timestampMatches(signatures.get(1), sealed), "the document timestamp covers the document");
        }
    }

    @Test
    void anEditAfterSealingIsNoLongerCoveredBySignatures() throws Exception
    {
        PadesSigner signer = new PadesSigner(keyStore, PASSWORD, null);
        byte[] sealed = signer.sign(flattened(pdfWithField()), "seal", "Completed", "Test", true).pdf();

        // Someone adds a comment afterwards
        byte[] edited;
        try (PDDocument document = Loader.loadPDF(sealed))
        {
            PDPage page = document.getPage(0);
            PDAnnotationText note = new PDAnnotationText();
            note.setRectangle(new PDRectangle(50, 50, 20, 20));
            note.setContents("Changed after signing");
            page.getAnnotations().add(note);
            page.getCOSObject().setNeedToBeUpdated(true);
            page.getCOSObject().getCOSArray(COSName.ANNOTS).setNeedToBeUpdated(true);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.saveIncremental(out);
            edited = out.toByteArray();
        }

        try (PDDocument document = Loader.loadPDF(edited))
        {
            PDSignature signature = document.getSignatureDictionaries().get(0);
            int[] range = signature.getByteRange();
            assertTrue((long) range[2] + range[3] < edited.length, "the edit lies outside what was signed");
            assertEquals(PadesSigner.NO_CHANGES_ALLOWED, PadesSigner.certificationLevel(document),
                    "readers judge the edit against P=1 and report the certification as broken");
        }
    }

    @Test
    void doesNotCertifyADocumentThatIsAlreadySigned() throws Exception
    {
        PadesSigner signer = new PadesSigner(keyStore, PASSWORD, null);
        byte[] once = signer.sign(flattened(pdfWithField()), "seal", "First", "Test", false).pdf();

        PadesSigner.Applied twice = signer.sign(once, "seal", "Final", "Test", true);

        assertFalse(twice.certified());
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static boolean verifies(PDSignature signature, byte[] pdf) throws Exception
    {
        CMSSignedData cms = new CMSSignedData(new CMSProcessableByteArray(signature.getSignedContent(pdf)),
                signature.getContents(pdf));
        SignerInformation signer = cms.getSignerInfos().getSigners().iterator().next();
        @SuppressWarnings("unchecked")
        X509CertificateHolder holder = (X509CertificateHolder) cms.getCertificates().getMatches(signer.getSID())
                .iterator().next();
        return signer.verify(new JcaSimpleSignerInfoVerifierBuilder().build(holder));
    }

    private static boolean timestampMatches(PDSignature signature, byte[] pdf) throws Exception
    {
        assertEquals("ETSI.RFC3161", signature.getSubFilter());
        TimeStampToken token = new TimeStampToken(new CMSSignedData(signature.getContents(pdf)));
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(signature.getSignedContent(pdf));
        assertArrayEquals(digest, token.getTimeStampInfo().getMessageImprintDigest());
        return true;
    }

    private static byte[] flattened(byte[] pdf) throws Exception
    {
        try (PDDocument document = Loader.loadPDF(pdf))
        {
            PdfSigningEngineImpl.flatten(document);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    /** A one-page PDF with a filled text field. */
    private static byte[] pdfWithField() throws Exception
    {
        try (PDDocument document = new PDDocument())
        {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            PDAcroForm form = new PDAcroForm(document);
            document.getDocumentCatalog().setAcroForm(form);
            PDResources resources = new PDResources();
            resources.put(COSName.getPDFName("Helv"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
            form.setDefaultResources(resources);
            form.setDefaultAppearance("/Helv 12 Tf 0 g");
            PDTextField field = new PDTextField(form);
            field.setPartialName("name");
            PDAnnotationWidget widget = field.getWidgets().get(0);
            widget.setRectangle(new PDRectangle(100, 700, 200, 20));
            widget.setPage(page);
            page.getAnnotations().add(widget);
            form.getFields().add(field);
            field.setValue("Onalenna Makhura");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private static KeyPair keyPair() throws Exception
    {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static X509Certificate certificate(String name, KeyPair keys, boolean timestamping) throws Exception
    {
        Instant now = Instant.now();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(new X500Name(name), BigInteger.ONE,
                Date.from(now.minusSeconds(300)), Date.from(now.plus(Duration.ofDays(1))), new X500Name(name),
                keys.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        if (timestamping)
        {
            builder.addExtension(Extension.extendedKeyUsage, true,
                    new ExtendedKeyUsage(KeyPurposeId.id_kp_timeStamping));
        }
        return new JcaX509CertificateConverter().getCertificate(
                builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate())));
    }
}
