package bw.co.starsign.signing;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import org.apache.commons.lang3.StringUtils;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The PKCS12 keystore holding the platform's signing keys ({@code app.signing.keystore-path}). When it is missing
 * and {@code app.signing.generate-dev-keystore} is set, a self-signed RSA-3072 key is generated and saved there;
 * such a certificate is never trusted by PDF readers and is meant for development only.
 */
@Component
public class SigningKeystore
{
    private static final Logger LOGGER = LoggerFactory.getLogger(SigningKeystore.class);
    private static final int DEV_KEY_SIZE = 3072;
    private static final Duration DEV_VALIDITY = Duration.ofDays(5 * 365);

    private final SigningProperties properties;
    private volatile KeyStore keyStore;

    public SigningKeystore(SigningProperties properties)
    {
        this.properties = properties;
    }

    /** The keystore, or null when none is configured. */
    public synchronized KeyStore keyStore()
    {
        if (keyStore != null)
        {
            return keyStore;
        }
        if (StringUtils.isBlank(properties.getKeystorePath()))
        {
            return null;
        }
        Path path = Paths.get(properties.getKeystorePath());
        try
        {
            if (Files.exists(path))
            {
                KeyStore loaded = KeyStore.getInstance("PKCS12");
                try (InputStream in = Files.newInputStream(path))
                {
                    loaded.load(in, password());
                }
                keyStore = loaded;
            }
            else if (properties.isGenerateDevKeystore())
            {
                keyStore = generate(path);
            }
            return keyStore;
        }
        catch (IOException | java.security.GeneralSecurityException | org.bouncycastle.operator.OperatorCreationException e)
        {
            throw new PdfSigningEngineException("The signing keystore " + path + " could not be opened", e);
        }
    }

    /** The keystore; a rule violation when none is configured. */
    public KeyStore requireKeyStore()
    {
        KeyStore store = keyStore();
        if (store == null)
        {
            throw new PdfSigningEngineException("Digital signatures are not available: no signing keystore is configured");
        }
        return store;
    }

    /** The alias to sign with: the requested one, else the configured default. */
    public String alias(String requested)
    {
        return StringUtils.defaultIfBlank(StringUtils.trimToNull(requested), properties.getKeyAlias());
    }

    public char[] password()
    {
        return StringUtils.defaultString(properties.getKeystorePassword()).toCharArray();
    }

    public String defaultAlias()
    {
        return properties.getKeyAlias();
    }

    private KeyStore generate(Path path)
        throws IOException, java.security.GeneralSecurityException, org.bouncycastle.operator.OperatorCreationException
    {
        LOGGER.warn("Generating a self-signed development signing keystore at {}; do not use it in production", path);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(DEV_KEY_SIZE, new SecureRandom());
        KeyPair pair = generator.generateKeyPair();

        X500Name subject = new X500Name("CN=StarSign Development Signing, O=StarSign, OU=Development, C=BW");
        Instant now = Instant.now();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(subject,
                new BigInteger(64, new SecureRandom()).abs().add(BigInteger.ONE),
                Date.from(now.minus(Duration.ofMinutes(5))), Date.from(now.plus(DEV_VALIDITY)), subject, pair.getPublic());
        JcaX509ExtensionUtils extensions = new JcaX509ExtensionUtils();
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.nonRepudiation));
        builder.addExtension(Extension.subjectKeyIdentifier, false, extensions.createSubjectKeyIdentifier(pair.getPublic()));
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(pair.getPrivate());
        X509CertificateHolder holder = builder.build(signer);
        X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(holder);

        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setKeyEntry(properties.getKeyAlias(), pair.getPrivate(), password(), new Certificate[] {certificate});
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null)
        {
            Files.createDirectories(parent);
        }
        try (OutputStream out = Files.newOutputStream(path))
        {
            store.store(out, password());
        }
        return store;
    }
}
