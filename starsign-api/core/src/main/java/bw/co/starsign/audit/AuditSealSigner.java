package bw.co.starsign.audit;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.util.Base64;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Signs audit chain seals with the platform signing key (the same PKCS#12 keystore the PDF signing
 * engine uses, {@code app.signing.*}), so a seal can be checked by anyone holding the certificate.
 */
@Component
public class AuditSealSigner
{
    private final String keystorePath;
    private final String keystorePassword;
    private final String keyAlias;

    private volatile Key key;

    public AuditSealSigner(
        @Value("${app.signing.keystore-path:}") String keystorePath,
        @Value("${app.signing.keystore-password:}") String keystorePassword,
        @Value("${app.signing.key-alias:}") String keyAlias)
    {
        this.keystorePath = keystorePath;
        this.keystorePassword = keystorePassword;
        this.keyAlias = keyAlias;
    }

    /** The id stored with a seal: signature algorithm, alias and the start of the certificate fingerprint. */
    public String keyId()
    {
        return key().id();
    }

    public String sign(String data)
    {
        try
        {
            Signature signature = Signature.getInstance(key().algorithm());
            signature.initSign(key().privateKey());
            signature.update(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        }
        catch (java.security.GeneralSecurityException e)
        {
            throw new AuditTrailServiceException("The audit seal could not be signed", e);
        }
    }

    /**
     * @return true when the signature was made by the current key; false when it does not match or was
     *         made by another key (which cannot be checked here).
     */
    public boolean verify(String data, String signatureValue, String signingKeyId)
    {
        Key current = key();
        if (!current.id().equals(signingKeyId) || StringUtils.isBlank(signatureValue))
        {
            return false;
        }
        try
        {
            Signature signature = Signature.getInstance(current.algorithm());
            signature.initVerify(current.certificate().getPublicKey());
            signature.update(data.getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.getDecoder().decode(signatureValue));
        }
        catch (java.security.GeneralSecurityException | IllegalArgumentException e)
        {
            throw new AuditTrailServiceException("The audit seal signature could not be checked", e);
        }
    }

    private Key key()
    {
        Key loaded = this.key;
        if (loaded == null)
        {
            synchronized (this)
            {
                if (this.key == null)
                {
                    this.key = load();
                }
                loaded = this.key;
            }
        }
        return loaded;
    }

    private Key load()
    {
        if (StringUtils.isBlank(keystorePath) || !new File(keystorePath).isFile())
        {
            throw new AuditTrailServiceException(
                    "Audit seals cannot be signed: the signing keystore (app.signing.keystore-path) is not available");
        }
        try
        {
            char[] password = keystorePassword == null ? new char[0] : keystorePassword.toCharArray();
            KeyStore store = KeyStore.getInstance(new File(keystorePath), password);
            String alias = StringUtils.isNotBlank(keyAlias) ? keyAlias : store.aliases().nextElement();
            java.security.Key privateKey = store.getKey(alias, password);
            Certificate certificate = store.getCertificate(alias);
            if (!(privateKey instanceof PrivateKey) || certificate == null)
            {
                throw new AuditTrailServiceException(
                        "Audit seals cannot be signed: the signing keystore has no key named " + alias);
            }
            // The PDF signing key may be RSA or EC
            String algorithm = "EC".equals(privateKey.getAlgorithm()) ? "SHA256withECDSA" : "SHA256withRSA";
            String id = algorithm + ":" + alias + ":" + DigestUtils.sha256Hex(certificate.getEncoded()).substring(0, 16);
            return new Key(id, algorithm, (PrivateKey) privateKey, certificate);
        }
        catch (java.io.IOException | java.security.GeneralSecurityException e)
        {
            throw new AuditTrailServiceException("The signing keystore could not be opened", e);
        }
    }

    private record Key(String id, String algorithm, PrivateKey privateKey, Certificate certificate)
    {
    }
}
