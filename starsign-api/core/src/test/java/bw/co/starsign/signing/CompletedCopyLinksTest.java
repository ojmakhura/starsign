package bw.co.starsign.signing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import bw.co.starsign.identity.OrganisationUser;
import bw.co.starsign.workflow.Signatory;
import bw.co.starsign.workflow.SignatoryType;
import bw.co.starsign.workflow.SigningWorkflow;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The completed-copy email used to say "Download your copy at ." because no link was ever set. */
class CompletedCopyLinksTest
{
    private SignatoryInvitationRepository repository;
    private SigningLinks signingLinks;
    private CompletedCopyLinks links;
    private Signatory signatory;

    @BeforeEach
    void setUp()
    {
        repository = mock(SignatoryInvitationRepository.class);
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        signingLinks = new SigningLinks();
        signingLinks.setPortalUrl("https://portal.example.co.bw/");
        links = new CompletedCopyLinks(repository, signingLinks);

        SigningWorkflow workflow = SigningWorkflow.Factory.newInstance();
        workflow.setId(UUID.randomUUID());
        signatory = Signatory.Factory.newInstance();
        signatory.setId(UUID.randomUUID());
        signatory.setWorkflow(workflow);
        signatory.setSignatoryType(SignatoryType.EXTERNAL_PERSON);
    }

    @Test
    void externalSignatoryGetsADownloadOnlyLink()
    {
        String link = links.linkFor(signatory);

        String prefix = "https://portal.example.co.bw/api/signing/invitations/";
        assertTrue(link.startsWith(prefix) && link.endsWith("/completed"), link);
        String token = link.substring(prefix.length(), link.length() - "/completed".length());

        ArgumentCaptor<SignatoryInvitation> saved = ArgumentCaptor.forClass(SignatoryInvitation.class);
        verify(repository).save(saved.capture());
        SignatoryInvitation invitation = saved.getValue();
        assertEquals(SigningTokens.sha256(token), invitation.getTokenHash(), "the link's token is the stored one");
        // Used and single-use: SigningSessions refuses it for viewing or signing, accepts it for the download
        assertEquals(InvitationStatus.CONSUMED, invitation.getStatus());
        assertTrue(invitation.isSingleUse());
        assertEquals(signatory, invitation.getSignatory());
    }

    @Test
    void publicApiUrlIsUsedWhenSet()
    {
        signingLinks.setPublicApiUrl("https://api.example.co.bw/");

        assertTrue(links.linkFor(signatory).startsWith("https://api.example.co.bw/signing/invitations/"));
    }

    @Test
    void internalSignatoryOpensTheRequestInThePortal()
    {
        signatory.setSignatoryType(SignatoryType.INTERNAL_USER);
        signatory.setInternalUser(OrganisationUser.Factory.newInstance());

        String link = links.linkFor(signatory);

        assertEquals("https://portal.example.co.bw/signing/workflows/" + signatory.getWorkflow().getId()
                + "/signatories/" + signatory.getId(), link);
        verify(repository, never()).save(any());
    }
}
