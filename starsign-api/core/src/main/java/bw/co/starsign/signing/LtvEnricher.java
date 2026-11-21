package bw.co.starsign.signing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.cms.AttributeTable;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x509.AccessDescription;
import org.bouncycastle.asn1.x509.AuthorityInformationAccess;
import org.bouncycastle.asn1.x509.CRLDistPoint;
import org.bouncycastle.asn1.x509.DistributionPoint;
import org.bouncycastle.asn1.x509.DistributionPointName;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.X509ObjectIdentifiers;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.ocsp.BasicOCSPResp;
import org.bouncycastle.cert.ocsp.CertificateID;
import org.bouncycastle.cert.ocsp.OCSPReqBuilder;
import org.bouncycastle.cert.ocsp.OCSPResp;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.tsp.TimeStampToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adds long-term validation data to a signed PDF as an incremental update: a Document Security Store (ISO 32000-2,
 * 12.8.4.3; ETSI EN 319 142-1 PAdES-B-LT) holding every certificate the document's signatures and their timestamps
 * rely on, with OCSP responses or CRLs for them, and a VRI entry per signature. A reader can then check the seal
 * after the certificates expire or the CA's services go away.
 * <p>
 * Revocation data that cannot be fetched (no AIA/CRL extension, a self-signed certificate, the responder is down)
 * is left out and reported: the certificate is still embedded and the seal itself stays valid.
 */
final class LtvEnricher
{
    private static final Logger LOGGER = LoggerFactory.getLogger(LtvEnricher.class);

    /** What was embedded. */
    record Result(byte[] pdf, int certificates, int ocspResponses, int crls, List<String> gaps)
    {
    }

    private final HttpClient http;
    private final Duration timeout;

    LtvEnricher(Duration timeout)
    {
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    Result enrich(byte[] pdf) throws IOException
    {
        // Certificates by their DER encoding, so the same certificate is embedded once
        Map<String, X509CertificateHolder> certificates = new LinkedHashMap<>();
        Map<String, List<String>> certificatesBySignature = new LinkedHashMap<>();
        try (PDDocument document = Loader.loadPDF(pdf))
        {
            for (PDSignature signature : document.getSignatureDictionaries())
            {
                byte[] contents = signature.getContents(pdf);
                List<String> used = new ArrayList<>();
                try
                {
                    CMSSignedData cms = new CMSSignedData(contents);
                    collect(cms, certificates, used);
                    for (SignerInformation signer : cms.getSignerInfos().getSigners())
                    {
                        AttributeTable unsigned = signer.getUnsignedAttributes();
                        Attribute stamp = unsigned == null ? null
                                : unsigned.get(PKCSObjectIdentifiers.id_aa_signatureTimeStampToken);
                        if (stamp != null)
                        {
                            for (ASN1Encodable value : stamp.getAttrValues())
                            {
                                collect(new TimeStampToken(new CMSSignedData(value.toASN1Primitive().getEncoded()))
                                        .toCMSSignedData(), certificates, used);
                            }
                        }
                    }
                }
                catch (Exception e)
                {
                    throw new IOException("A signature of the document could not be read: " + e.getMessage(), e);
                }
                certificatesBySignature.put(vriKey(contents), used);
            }
        }

        // Revocation data per certificate, fetched once
        Map<String, byte[]> ocsps = new LinkedHashMap<>();
        Map<String, byte[]> crls = new LinkedHashMap<>();
        List<String> gaps = new ArrayList<>();
        for (Map.Entry<String, X509CertificateHolder> entry : certificates.entrySet())
        {
            X509CertificateHolder certificate = entry.getValue();
            if (certificate.getSubject().equals(certificate.getIssuer()))
            {
                continue; // a root (or self-signed) certificate has no revocation status
            }
            X509CertificateHolder issuer = certificates.values().stream()
                    .filter(candidate -> candidate.getSubject().equals(certificate.getIssuer()))
                    .findFirst().orElse(null);
            byte[] ocsp = issuer == null ? null : ocsp(certificate, issuer);
            if (ocsp != null)
            {
                ocsps.put(entry.getKey(), ocsp);
                continue;
            }
            byte[] crl = crl(certificate);
            if (crl != null)
            {
                crls.put(entry.getKey(), crl);
            }
            else
            {
                gaps.add("No revocation data for " + certificate.getSubject());
            }
        }

        try (PDDocument document = Loader.loadPDF(pdf))
        {
            COSDictionary catalog = document.getDocumentCatalog().getCOSObject();
            COSDictionary dss = catalog.getCOSDictionary(COSName.getPDFName("DSS"));
            if (dss == null)
            {
                dss = new COSDictionary();
                dss.setItem(COSName.TYPE, COSName.getPDFName("DSS"));
                catalog.setItem(COSName.getPDFName("DSS"), dss);
            }
            Map<String, COSStream> certStreams = new LinkedHashMap<>();
            Map<String, COSStream> ocspStreams = new LinkedHashMap<>();
            Map<String, COSStream> crlStreams = new LinkedHashMap<>();
            COSArray certs = array(dss, "Certs");
            COSArray ocspArray = array(dss, "OCSPs");
            COSArray crlArray = array(dss, "CRLs");
            for (Map.Entry<String, X509CertificateHolder> entry : certificates.entrySet())
            {
                COSStream stream = stream(document, entry.getValue().getEncoded());
                certs.add(stream);
                certStreams.put(entry.getKey(), stream);
            }
            for (Map.Entry<String, byte[]> entry : ocsps.entrySet())
            {
                COSStream stream = stream(document, entry.getValue());
                ocspArray.add(stream);
                ocspStreams.put(entry.getKey(), stream);
            }
            for (Map.Entry<String, byte[]> entry : crls.entrySet())
            {
                COSStream stream = stream(document, entry.getValue());
                crlArray.add(stream);
                crlStreams.put(entry.getKey(), stream);
            }

            // VRI: the validation data of each signature, keyed by the SHA-1 of its /Contents
            COSDictionary vri = dss.getCOSDictionary(COSName.getPDFName("VRI"));
            if (vri == null)
            {
                vri = new COSDictionary();
                dss.setItem(COSName.getPDFName("VRI"), vri);
            }
            for (Map.Entry<String, List<String>> entry : certificatesBySignature.entrySet())
            {
                COSDictionary item = new COSDictionary();
                COSArray itemCerts = new COSArray();
                COSArray itemOcsps = new COSArray();
                COSArray itemCrls = new COSArray();
                for (String key : entry.getValue())
                {
                    itemCerts.add(certStreams.get(key));
                    if (ocspStreams.containsKey(key))
                    {
                        itemOcsps.add(ocspStreams.get(key));
                    }
                    if (crlStreams.containsKey(key))
                    {
                        itemCrls.add(crlStreams.get(key));
                    }
                }
                item.setItem(COSName.getPDFName("Cert"), itemCerts);
                if (itemOcsps.size() > 0)
                {
                    item.setItem(COSName.getPDFName("OCSP"), itemOcsps);
                }
                if (itemCrls.size() > 0)
                {
                    item.setItem(COSName.getPDFName("CRL"), itemCrls);
                }
                vri.setItem(COSName.getPDFName(entry.getKey()), item);
            }
            catalog.setNeedToBeUpdated(true);
            dss.setNeedToBeUpdated(true);
            vri.setNeedToBeUpdated(true);

            ByteArrayOutputStream out = new ByteArrayOutputStream(pdf.length + 32 * 1024);
            document.saveIncremental(out);
            return new Result(out.toByteArray(), certificates.size(), ocsps.size(), crls.size(), gaps);
        }
    }

    private static void collect(CMSSignedData cms, Map<String, X509CertificateHolder> certificates, List<String> used)
        throws IOException
    {
        @SuppressWarnings("unchecked")
        Collection<X509CertificateHolder> holders = cms.getCertificates().getMatches(null);
        for (X509CertificateHolder holder : holders)
        {
            String key = HexFormat.of().formatHex(holder.getEncoded());
            certificates.putIfAbsent(key, holder);
            if (!used.contains(key))
            {
                used.add(key);
            }
        }
    }

    private static COSArray array(COSDictionary dss, String name)
    {
        COSArray array = dss.getCOSArray(COSName.getPDFName(name));
        if (array == null)
        {
            array = new COSArray();
            dss.setItem(COSName.getPDFName(name), array);
        }
        array.setNeedToBeUpdated(true);
        return array;
    }

    private static COSStream stream(PDDocument document, byte[] bytes) throws IOException
    {
        COSStream stream = document.getDocument().createCOSStream();
        try (OutputStream out = stream.createOutputStream(COSName.FLATE_DECODE))
        {
            out.write(bytes);
        }
        stream.setNeedToBeUpdated(true);
        return stream;
    }

    /** VRI key: the upper-case hex SHA-1 of the signature's /Contents (ISO 32000-2, 12.8.4.4). */
    private static String vriKey(byte[] contents) throws IOException
    {
        try
        {
            // /Contents is zero-padded to its reserved size: hash the DER object only
            byte[] der = new CMSSignedData(contents).toASN1Structure().getEncoded("DER");
            return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-1").digest(der));
        }
        catch (NoSuchAlgorithmException | org.bouncycastle.cms.CMSException e)
        {
            throw new IOException(e);
        }
    }

    /** An OCSP response for the certificate from its AIA responder, or null. */
    private byte[] ocsp(X509CertificateHolder certificate, X509CertificateHolder issuer)
    {
        String url = accessLocation(certificate, X509ObjectIdentifiers.id_ad_ocsp);
        if (url == null)
        {
            return null;
        }
        try
        {
            CertificateID id = new CertificateID(new JcaDigestCalculatorProviderBuilder().build()
                    .get(CertificateID.HASH_SHA1), issuer, certificate.getSerialNumber());
            byte[] request = new OCSPReqBuilder().addRequest(id).build().getEncoded();
            HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                    .header("Content-Type", "application/ocsp-request")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(request)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200)
            {
                return null;
            }
            OCSPResp ocsp = new OCSPResp(response.body());
            if (ocsp.getStatus() != OCSPResp.SUCCESSFUL || !(ocsp.getResponseObject() instanceof BasicOCSPResp))
            {
                return null;
            }
            return ocsp.getEncoded();
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return null;
        }
        catch (Exception e)
        {
            LOGGER.info("OCSP for {} from {} failed: {}", certificate.getSubject(), url, e.getMessage());
            return null;
        }
    }

    /** The CRL from the certificate's first HTTP distribution point, or null. */
    private byte[] crl(X509CertificateHolder certificate)
    {
        Extension extension = certificate.getExtension(Extension.cRLDistributionPoints);
        if (extension == null)
        {
            return null;
        }
        for (DistributionPoint point : CRLDistPoint.getInstance(extension.getParsedValue()).getDistributionPoints())
        {
            DistributionPointName name = point.getDistributionPoint();
            if (name == null || name.getType() != DistributionPointName.FULL_NAME)
            {
                continue;
            }
            for (GeneralName generalName : GeneralNames.getInstance(name.getName()).getNames())
            {
                String url = generalName.getTagNo() == GeneralName.uniformResourceIdentifier
                        ? generalName.getName().toString() : null;
                if (url == null || !url.startsWith("http"))
                {
                    continue;
                }
                try
                {
                    HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                            .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                    if (response.statusCode() == 200 && response.body().length > 0)
                    {
                        return response.body();
                    }
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    return null;
                }
                catch (Exception e)
                {
                    LOGGER.info("CRL for {} from {} failed: {}", certificate.getSubject(), url, e.getMessage());
                }
            }
        }
        return null;
    }

    private static String accessLocation(X509CertificateHolder certificate, ASN1ObjectIdentifier method)
    {
        Extension extension = certificate.getExtension(Extension.authorityInfoAccess);
        if (extension == null)
        {
            return null;
        }
        for (AccessDescription description : AuthorityInformationAccess.getInstance(extension.getParsedValue())
                .getAccessDescriptions())
        {
            GeneralName location = description.getAccessLocation();
            if (method.equals(description.getAccessMethod())
                    && location.getTagNo() == GeneralName.uniformResourceIdentifier)
            {
                return location.getName().toString();
            }
        }
        return null;
    }
}
