// license-header java merge-point
/**
 * This is only generated once! It will never be overwritten.
 * You can (and have to!) safely modify it by hand.
 * TEMPLATE:    SpringServiceImpl.vsl in andromda-spring cartridge
 * MODEL CLASS: AndroMDAModel::backend::bw.co.starsign::signing::SignatoryVerificationService
 * STEREOTYPE:  Service
 */
package bw.co.starsign.signing;

import bw.co.starsign.audit.AuditTrailService;
import bw.co.starsign.audit.AuthenticationMethod;
import bw.co.starsign.notification.EmailGateway;
import bw.co.starsign.notification.NotificationChannel;
import bw.co.starsign.notification.NotificationEventType;
import bw.co.starsign.notification.NotificationProviderConfig;
import bw.co.starsign.notification.NotificationProviderConfigRepository;
import bw.co.starsign.notification.NovuNotifier;
import bw.co.starsign.notification.SmsGateway;
import bw.co.starsign.workflow.Signatory;
import bw.co.starsign.workflow.SignatoryStatus;
import bw.co.starsign.workflow.SigningWorkflow;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * @see bw.co.starsign.signing.SignatoryVerificationService
 */
@Service("signatoryVerificationService")
@Transactional(propagation = Propagation.REQUIRED, readOnly = false)
public class SignatoryVerificationServiceImpl
    extends SignatoryVerificationServiceBase
{
    private static final int CODE_DIGITS = 6;
    private static final int CODE_VALIDITY_MINUTES = 10;
    private static final int MAX_ATTEMPTS = 5;
    private static final int MAX_CODES_PER_HOUR = 5;
    private static final int RESEND_AFTER_SECONDS = 60;
    /** How long a verified browser may keep signing without verifying again. */
    private static final int SESSION_HOURS = 4;

    private final SigningSessions signingSessions;
    private final SigningEventRecorder signingEventRecorder;

    public SignatoryVerificationServiceImpl(
        @Lazy SmsGateway smsGateway,
        @Lazy EmailGateway emailGateway,
        @Lazy IdentityVerificationProvider identityVerificationProvider,
        @Lazy AuditTrailService auditTrailService,
        VerificationChallengeRepository verificationChallengeRepository,
        VerificationChallengeMapper verificationChallengeMapper,
        SignatoryInvitationRepository signatoryInvitationRepository,
        SignatoryInvitationMapper signatoryInvitationMapper,
        AccessTokenRepository accessTokenRepository,
        AccessTokenMapper accessTokenMapper,
        SigningSessions signingSessions,
        SigningEventRecorder signingEventRecorder,
        NovuNotifier novuNotifier,
        NotificationProviderConfigRepository notificationProviderConfigRepository) {

        super(
            smsGateway,
            emailGateway,
            identityVerificationProvider,
            auditTrailService,
            verificationChallengeRepository,
            verificationChallengeMapper,
            signatoryInvitationRepository,
            signatoryInvitationMapper,
            accessTokenRepository,
            accessTokenMapper);
        this.signingSessions = signingSessions;
        this.signingEventRecorder = signingEventRecorder;
        this.novuNotifier = novuNotifier;
        this.notificationProviderConfigRepository = notificationProviderConfigRepository;
    }

    /** Sender when no active email provider names one (as NotificationServiceImpl). */
    private static final String DEFAULT_SENDER = "no-reply@starsign.co.bw";

    private final NovuNotifier novuNotifier;
    private final NotificationProviderConfigRepository notificationProviderConfigRepository;

    /** The sender address of the first active email provider, else the default. */
    private String emailSender()
    {
        return notificationProviderConfigRepository
                .findByChannelAndActiveTrueOrderByPriorityAsc(NotificationChannel.EMAIL).stream()
                .map(NotificationProviderConfig::getSenderAddress)
                .filter(StringUtils::isNotBlank)
                .findFirst()
                .orElse(DEFAULT_SENDER);
    }

    /**
     * @see bw.co.starsign.signing.SignatoryVerificationService#startVerification(String, @Valid VerificationRequestDTO)
     */
    @Override
    protected VerificationResultDTO handleStartVerification(String token, @Valid VerificationRequestDTO request)
        throws Exception
    {
        SignatoryInvitation invitation = open(token);
        Signatory signatory = invitation.getSignatory();
        VerificationMethod method = requiredMethod(invitation, request == null ? null : request.getMethod());

        switch (method)
        {
            case EMAIL_LINK:
                // Opening the emailed link was the verification
                return result(VerificationStatus.VERIFIED, null, null, null, true);
            case INTERNAL_ACCOUNT:
                throw new SignatoryVerificationServiceException(
                        "Sign in to StarSign with your account to sign this document.");
            case EXTERNAL_IDENTITY_PROVIDER:
                throw new SignatoryVerificationServiceException(
                        "Verification with an external identity provider is not available. Ask the sender to choose "
                        + "another verification method.");
            default:
                break;
        }

        LocalDateTime now = LocalDateTime.now();
        if (verificationChallengeRepository.countByInvitation_IdAndCreatedAtAfter(invitation.getId(), now.minusHours(1))
                >= MAX_CODES_PER_HOUR)
        {
            throw new SignatoryVerificationServiceException("Too many codes have been requested. Try again in an hour.");
        }
        Optional<VerificationChallenge> latest = verificationChallengeRepository
                .findFirstByInvitation_IdOrderByCreatedAtDesc(invitation.getId());
        if (latest.isPresent() && latest.get().getStatus() == VerificationStatus.PENDING
                && latest.get().getCreatedAt().isAfter(now.minusSeconds(RESEND_AFTER_SECONDS)))
        {
            throw new SignatoryVerificationServiceException(
                    "A code was sent less than a minute ago. Wait a moment before asking for another one.");
        }
        // Only the newest code can be used
        for (VerificationChallenge pending : verificationChallengeRepository.findByInvitation_IdAndStatus(
                invitation.getId(), VerificationStatus.PENDING))
        {
            pending.setStatus(VerificationStatus.EXPIRED);
            pending.setModifiedAt(now);
            pending.setModifiedBy("system");
        }

        String destination = method == VerificationMethod.SMS_OTP ? signatory.getPhoneNumber() : signatory.getEmailAddress();
        if (StringUtils.isBlank(destination))
        {
            throw new SignatoryVerificationServiceException(method == VerificationMethod.SMS_OTP
                    ? "No mobile number is recorded for you. Ask the sender to add one."
                    : "No email address is recorded for you. Ask the sender to add one.");
        }
        String code = SigningTokens.newCode(CODE_DIGITS);

        VerificationChallenge challenge = VerificationChallenge.Factory.newInstance();
        challenge.setMethod(method);
        challenge.setCodeHash(codeHash(invitation, code));
        challenge.setDestinationMasked(method == VerificationMethod.SMS_OTP
                ? SigningTokens.maskPhone(destination)
                : SigningTokens.maskEmail(destination));
        challenge.setSentAt(now);
        challenge.setExpiresAt(now.plusMinutes(CODE_VALIDITY_MINUTES));
        challenge.setAttempts(0);
        challenge.setMaxAttempts(MAX_ATTEMPTS);
        challenge.setStatus(VerificationStatus.PENDING);
        challenge.setInvitation(invitation);
        challenge.setCreatedAt(now);
        challenge.setCreatedBy(signatory.getId().toString());
        challenge.setProviderReference(send(method, destination, code, signatory.getWorkflow()));
        challenge = verificationChallengeRepository.save(challenge);

        signingEventRecorder.record(SigningEventType.VERIFICATION_STARTED, signatory.getWorkflow(), signatory,
                SigningEventRecorder.Actor.signatory(signatory, AuthenticationMethod.INVITATION_TOKEN),
                "challenge:" + challenge.getId(), "Code sent by " + method + " to " + challenge.getDestinationMasked(),
                null, null);
        return result(VerificationStatus.PENDING, challenge.getDestinationMasked(), MAX_ATTEMPTS,
                challenge.getExpiresAt(), false);
    }

    /**
     * @see bw.co.starsign.signing.SignatoryVerificationService#confirmVerification(String, @Valid VerificationCodeDTO)
     */
    @Override
    protected VerificationResultDTO handleConfirmVerification(String token, @Valid VerificationCodeDTO code)
        throws Exception
    {
        SignatoryInvitation invitation = open(token);
        Signatory signatory = invitation.getSignatory();
        LocalDateTime now = LocalDateTime.now();

        VerificationChallenge challenge = verificationChallengeRepository
                .findByInvitation_IdAndStatus(invitation.getId(), VerificationStatus.PENDING).stream()
                .max(Comparator.comparing(VerificationChallenge::getCreatedAt))
                .orElseThrow(() -> new SignatoryVerificationServiceException("Ask for a verification code first."));

        // Failures are returned rather than thrown so that the attempt count is committed
        if (challenge.getExpiresAt().isBefore(now))
        {
            challenge.setStatus(VerificationStatus.EXPIRED);
            return result(VerificationStatus.EXPIRED, challenge.getDestinationMasked(), 0, challenge.getExpiresAt(), false);
        }
        challenge.setAttempts(challenge.getAttempts() + 1);
        challenge.setModifiedAt(now);
        challenge.setModifiedBy(signatory.getId().toString());

        String entered = code == null ? null : StringUtils.deleteWhitespace(code.getCode());
        boolean matches = entered != null && entered.matches("\\d{4,10}")
                && SigningTokens.sameHash(challenge.getCodeHash(), codeHash(invitation, entered));
        if (!matches)
        {
            int remaining = Math.max(0, challenge.getMaxAttempts() - challenge.getAttempts());
            if (remaining == 0)
            {
                challenge.setStatus(VerificationStatus.LOCKED);
            }
            signingEventRecorder.record(SigningEventType.VERIFICATION_FAILED, signatory.getWorkflow(), signatory,
                    SigningEventRecorder.Actor.signatory(signatory, AuthenticationMethod.INVITATION_TOKEN),
                    null, remaining == 0 ? "Wrong code; the code is now locked" : "Wrong code", null, null);
            return result(remaining == 0 ? VerificationStatus.LOCKED : VerificationStatus.FAILED,
                    challenge.getDestinationMasked(), remaining, challenge.getExpiresAt(), false);
        }

        challenge.setStatus(VerificationStatus.VERIFIED);
        challenge.setVerifiedAt(now);
        AuthenticationMethod authentication = challenge.getMethod() == VerificationMethod.SMS_OTP
                ? AuthenticationMethod.INVITATION_TOKEN_SMS_OTP
                : AuthenticationMethod.INVITATION_TOKEN_EMAIL_OTP;
        verified(invitation, authentication, "challenge-verified:" + challenge.getId(),
                "Verified by " + challenge.getMethod() + " sent to " + challenge.getDestinationMasked());
        return result(VerificationStatus.VERIFIED, challenge.getDestinationMasked(),
                challenge.getMaxAttempts() - challenge.getAttempts(), challenge.getExpiresAt(), true);
    }

    /**
     * @see bw.co.starsign.signing.SignatoryVerificationService#verifyWithIdentityProvider(String, String)
     */
    @Override
    protected VerificationResultDTO handleVerifyWithIdentityProvider(String token, String assertion)
        throws Exception
    {
        SignatoryInvitation invitation = open(token);
        requiredMethod(invitation, VerificationMethod.EXTERNAL_IDENTITY_PROVIDER);
        Signatory signatory = invitation.getSignatory();

        VerificationResultDTO outcome;
        try
        {
            outcome = identityVerificationProvider.verify(signatory.getId().toString(), assertion);
        }
        catch (IdentityVerificationProviderException e)
        {
            throw new SignatoryVerificationServiceException(providerMessage(e));
        }
        if (outcome == null || !Boolean.TRUE.equals(outcome.getVerified()))
        {
            signingEventRecorder.record(SigningEventType.VERIFICATION_FAILED, signatory.getWorkflow(), signatory,
                    SigningEventRecorder.Actor.signatory(signatory, AuthenticationMethod.EXTERNAL_IDENTITY_PROVIDER),
                    null, "The identity provider did not confirm the signatory", null, null);
            return result(outcome == null || outcome.getStatus() == null ? VerificationStatus.FAILED : outcome.getStatus(),
                    null, null, null, false);
        }
        verified(invitation, AuthenticationMethod.EXTERNAL_IDENTITY_PROVIDER, null,
                "Verified by the external identity provider");
        return result(VerificationStatus.VERIFIED, null, null, null, true);
    }

    /** A usable signing link; verification also guards viewing the document, so it is not limited to signing. */
    private SignatoryInvitation open(String token)
    {
        SignatoryInvitation invitation = signingSessions.find(token)
                .orElseThrow(() -> SignatoryVerificationServiceException.notFound(
                        "This signing link is not valid. Check that you used the whole link from your invitation."));
        String refusal = signingSessions.refusal(invitation, SigningSessions.Purpose.VIEW);
        if (refusal != null)
        {
            throw new SignatoryVerificationServiceException(refusal);
        }
        return invitation;
    }

    /** The signatory cannot pick a weaker method than the one the sender required. */
    private static VerificationMethod requiredMethod(SignatoryInvitation invitation, VerificationMethod requested)
    {
        VerificationMethod required = SigningDocuments.verificationMethod(invitation.getSignatory());
        if (requested != null && requested != required)
        {
            throw new SignatoryVerificationServiceException(
                    "This signing request must be verified with " + describe(required) + ".");
        }
        return required;
    }

    /** Marks the signatory verified and opens a signing session for this browser. */
    private void verified(SignatoryInvitation invitation, AuthenticationMethod authentication, String eventKey, String details)
    {
        LocalDateTime now = LocalDateTime.now();
        Signatory signatory = invitation.getSignatory();
        signatory.setVerifiedAt(now);
        if (signatory.getStatus() == SignatoryStatus.INVITED || signatory.getStatus() == SignatoryStatus.VIEWED)
        {
            signatory.setStatus(SignatoryStatus.VERIFIED);
        }

        SigningClient client = SigningClient.current();
        String sessionToken = SigningTokens.newToken();
        LocalDateTime expiresAt = now.plusHours(SESSION_HOURS);
        if (invitation.getExpiresAt() != null && invitation.getExpiresAt().isBefore(expiresAt))
        {
            expiresAt = invitation.getExpiresAt();
        }
        AccessToken session = AccessToken.Factory.newInstance();
        session.setTokenHash(SigningTokens.sha256(sessionToken));
        session.setIssuedAt(now);
        session.setExpiresAt(expiresAt);
        session.setScope("signing");
        session.setAssuranceLevel(SignatureAssuranceLevel.ELECTRONIC_VERIFIED);
        session.setClientIp(client.ipAddress());
        session.setUserAgent(client.userAgent());
        session.setInvitation(invitation);
        session.setCreatedAt(now);
        session.setCreatedBy(signatory.getId().toString());
        accessTokenRepository.save(session);
        client.issueSession(sessionToken, expiresAt);

        signingEventRecorder.record(SigningEventType.VERIFICATION_PASSED, signatory.getWorkflow(), signatory,
                SigningEventRecorder.Actor.signatory(signatory, authentication), eventKey, details, null, null);
    }

    /** Sends the code synchronously: the signatory waits for it, and it is never stored in clear. */
    private String send(VerificationMethod method, String destination, String code, SigningWorkflow workflow)
    {
        String title = StringUtils.defaultIfBlank(workflow.getTitle(), "your document");
        try
        {
            if (method == VerificationMethod.SMS_OTP)
            {
                String message = "Your StarSign code is " + code + ". It expires in " + CODE_VALIDITY_MINUTES
                        + " minutes. Do not share it.";
                // Triggers the starsign-verification-code Novu workflow, not the generic SMS one
                return novuNotifier.withWorkflow(NotificationEventType.VERIFICATION_CODE,
                        java.util.UUID.randomUUID().toString(), null,
                        () -> smsGateway.send(destination, message, null));
            }
            String text = "Your StarSign verification code for \"" + title + "\" is " + code + ".\n\n"
                    + "It expires in " + CODE_VALIDITY_MINUTES + " minutes. If you did not ask for it, ignore this "
                    + "message and do not share the code with anyone.";
            String html = "<p>Your StarSign verification code for &quot;" + escapeHtml(title)
                    + "&quot; is</p><p style=\"font-size:24px;font-weight:bold;letter-spacing:4px\">" + code + "</p>"
                    + "<p>It expires in " + CODE_VALIDITY_MINUTES + " minutes. If you did not ask for it, ignore this "
                    + "message and do not share the code with anyone.</p>";
            String from = emailSender();
            return novuNotifier.withWorkflow(NotificationEventType.VERIFICATION_CODE,
                    java.util.UUID.randomUUID().toString(), null,
                    () -> emailGateway.send(from, destination, "Your StarSign verification code", html, text,
                            new HashMap<>()));
        }
        catch (RuntimeException e)
        {
            logger.warn("Could not send a {} verification code for workflow {}", method, workflow.getId(), e);
            throw new SignatoryVerificationServiceException(
                    "We could not send your verification code. Try again in a few minutes.");
        }
    }

    private static String escapeHtml(String text)
    {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String codeHash(SignatoryInvitation invitation, String code)
    {
        // Bound to the link so a code hash cannot be replayed against another invitation
        return SigningTokens.sha256(invitation.getId() + ":" + code);
    }

    /** The provider's own message, without the wrapping added by its generated base class. */
    private static String providerMessage(IdentityVerificationProviderException e)
    {
        Throwable cause = e;
        String message = e.getMessage();
        while (cause != null)
        {
            if (cause instanceof IdentityVerificationProviderException && cause.getCause() == null)
            {
                message = cause.getMessage();
            }
            cause = cause.getCause();
        }
        return message;
    }

    private static String describe(VerificationMethod method)
    {
        return switch (method)
        {
            case EMAIL_LINK -> "the link sent to your email";
            case EMAIL_OTP -> "a code sent to your email";
            case SMS_OTP -> "a code sent to your phone";
            case EXTERNAL_IDENTITY_PROVIDER -> "an identity provider";
            case INTERNAL_ACCOUNT -> "your StarSign account";
        };
    }

    private static VerificationResultDTO result(VerificationStatus status, String destinationMasked,
        Integer attemptsRemaining, LocalDateTime expiresAt, boolean verified)
    {
        VerificationResultDTO result = new VerificationResultDTO();
        result.setStatus(status);
        result.setDestinationMasked(destinationMasked);
        result.setAttemptsRemaining(attemptsRemaining);
        result.setExpiresAt(expiresAt);
        result.setVerified(verified);
        return result;
    }

}
