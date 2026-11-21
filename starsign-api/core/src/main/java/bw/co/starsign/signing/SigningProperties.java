package bw.co.starsign.signing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * {@code app.signing.*}: the keystore used for PAdES signatures and the optional RFC 3161 timestamp authority,
 * plus the links put in signing invitations ({@code app.signing-portal-url}, {@code app.portal-url} are bound
 * by {@link SigningLinks}).
 */
@Component
@ConfigurationProperties(prefix = "app.signing")
public class SigningProperties
{
    /** PKCS12 keystore holding the platform signing key and certificate. */
    private String keystorePath = "./data/signing/starsign-dev.p12";

    private String keystorePassword = "changeit";

    /** Alias of the default signing key in the keystore. */
    private String keyAlias = "starsign";

    /** Generates a self-signed keystore at keystorePath when it is missing (development only). */
    private boolean generateDevKeystore = false;

    /** RFC 3161 timestamp authority; blank for none. */
    private String tsaUrl;

    /**
     * Seals the completed document with a certification signature that allows no changes (DocMDP P=1): any later
     * edit, form fill or comment shows the signature as invalid. Off falls back to an approval signature.
     */
    private boolean certifyFinal = true;

    /**
     * Adds long-term validation data after sealing: the certificate chains and their revocation information
     * (OCSP/CRL) in a Document Security Store, plus a document timestamp when a timestamp authority is set
     * (PAdES-B-LT / B-LTA), so the seal can still be checked after the certificates expire.
     */
    private boolean ltv = true;

    /** Timeout for fetching OCSP responses and CRLs. */
    private int revocationTimeoutSeconds = 10;

    public boolean isCertifyFinal()
    {
        return certifyFinal;
    }

    public void setCertifyFinal(boolean certifyFinal)
    {
        this.certifyFinal = certifyFinal;
    }

    public boolean isLtv()
    {
        return ltv;
    }

    public void setLtv(boolean ltv)
    {
        this.ltv = ltv;
    }

    public int getRevocationTimeoutSeconds()
    {
        return revocationTimeoutSeconds;
    }

    public void setRevocationTimeoutSeconds(int revocationTimeoutSeconds)
    {
        this.revocationTimeoutSeconds = revocationTimeoutSeconds;
    }

    public String getKeystorePath()
    {
        return keystorePath;
    }

    public void setKeystorePath(String keystorePath)
    {
        this.keystorePath = keystorePath;
    }

    public String getKeystorePassword()
    {
        return keystorePassword;
    }

    public void setKeystorePassword(String keystorePassword)
    {
        this.keystorePassword = keystorePassword;
    }

    public String getKeyAlias()
    {
        return keyAlias;
    }

    public void setKeyAlias(String keyAlias)
    {
        this.keyAlias = keyAlias;
    }

    public boolean isGenerateDevKeystore()
    {
        return generateDevKeystore;
    }

    public void setGenerateDevKeystore(boolean generateDevKeystore)
    {
        this.generateDevKeystore = generateDevKeystore;
    }

    public String getTsaUrl()
    {
        return tsaUrl;
    }

    public void setTsaUrl(String tsaUrl)
    {
        this.tsaUrl = tsaUrl;
    }
}
