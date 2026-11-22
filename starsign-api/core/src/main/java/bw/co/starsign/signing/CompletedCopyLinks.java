package bw.co.starsign.signing;

import bw.co.starsign.notification.NotificationChannel;
import bw.co.starsign.workflow.Signatory;
import bw.co.starsign.workflow.SignatoryType;
import bw.co.starsign.workflow.SigningWorkflow;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

/**
 * The link in a signatory's "your signed copy" email ({@code COMPLETED_COPY}).
 *
 * <p>An external signatory's signing link cannot be repeated: only its hash is stored. So each completed copy gets a
 * new <em>download-only</em> link: an invitation stored as already used (single use, CONSUMED), which
 * {@link SigningSessions} refuses for viewing or signing and accepts only for downloading the completed copy
 * ({@code GET /signing/invitations/{token}/completed}). Internal signatories open the request in the portal with their
 * own account instead.
 */
@Component
public class CompletedCopyLinks
{
    private final SignatoryInvitationRepository signatoryInvitationRepository;
    private final SigningLinks signingLinks;

    public CompletedCopyLinks(SignatoryInvitationRepository signatoryInvitationRepository, SigningLinks signingLinks)
    {
        this.signatoryInvitationRepository = signatoryInvitationRepository;
        this.signingLinks = signingLinks;
    }

    /** Absolute URL the signatory uses to get the completed document. */
    public String linkFor(Signatory signatory)
    {
        SigningWorkflow workflow = signatory.getWorkflow();
        if (signatory.getSignatoryType() == SignatoryType.INTERNAL_USER && signatory.getInternalUser() != null)
        {
            return signingLinks.internalSigningLink(workflow.getId().toString(), signatory.getId().toString());
        }

        LocalDateTime now = LocalDateTime.now();
        String token = SigningTokens.newToken();
        SignatoryInvitation invitation = SignatoryInvitation.Factory.newInstance();
        invitation.setTokenHash(SigningTokens.sha256(token));
        // Already used: good for downloading the completed copy only (SigningSessions.refusal)
        invitation.setStatus(InvitationStatus.CONSUMED);
        invitation.setSingleUse(true);
        invitation.setIssuedAt(now);
        invitation.setSentAt(now);
        invitation.setConsumedAt(now);
        invitation.setAccessCount(0);
        invitation.setDeliveryChannel(NotificationChannel.EMAIL);
        invitation.setSignatory(signatory);
        invitation.setOrganisation(workflow.getOrganisation());
        invitation.setCreatedAt(now);
        invitation.setCreatedBy("system");
        signatoryInvitationRepository.save(invitation);
        return signingLinks.completedCopyLink(token);
    }
}
