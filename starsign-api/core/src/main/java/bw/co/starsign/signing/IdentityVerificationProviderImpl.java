// license-header java merge-point
/**
 * This is only generated once! It will never be overwritten.
 * You can (and have to!) safely modify it by hand.
 * TEMPLATE:    SpringServiceImpl.vsl in andromda-spring cartridge
 * MODEL CLASS: AndroMDAModel::backend::bw.co.starsign::signing::IdentityVerificationProvider
 * STEREOTYPE:  Service
 */
package bw.co.starsign.signing;

import org.springframework.stereotype.Service;

/**
 * @see bw.co.starsign.signing.IdentityVerificationProvider
 */
@Service("identityVerificationProvider")
public class IdentityVerificationProviderImpl
    extends IdentityVerificationProviderBase
{
    public IdentityVerificationProviderImpl(
        ) {

        super(
            );
    }

    /**
     * @see bw.co.starsign.signing.IdentityVerificationProvider#verify(String, String)
     */
    @Override
    protected VerificationResultDTO handleVerify(String signatoryId, String assertion)
        throws Exception
    {
        // Integration boundary (eID, bank ID, KYC providers): until a provider is plugged in here, signatories
        // whose workflow requires one cannot be verified, and must not be treated as verified
        throw new IdentityVerificationProviderException(
                "Verification with an external identity provider is not available: no identity provider is configured. "
                + "Ask the sender to choose another verification method.");
    }

}