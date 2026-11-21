// license-header java merge-point
/**
 * This is only generated once! It will never be overwritten.
 * You can (and have to!) safely modify it by hand.
 * TEMPLATE:    SpringServiceImpl.vsl in andromda-spring cartridge
 * MODEL CLASS: AndroMDAModel::backend::bw.co.starsign::identity::UserInvitationService
 * STEREOTYPE:  Service
 */
package bw.co.starsign.identity;

import bw.co.starsign.audit.ActorType;
import bw.co.starsign.audit.AuditEventDTO;
import bw.co.starsign.audit.AuditEventType;
import bw.co.starsign.audit.AuditOutcome;
import bw.co.starsign.audit.AuditTrailService;
import bw.co.starsign.audit.AuthenticationMethod;
import bw.co.starsign.context.CallerContext;
import bw.co.starsign.notification.NotificationChannel;
import bw.co.starsign.notification.NotificationEventType;
import bw.co.starsign.notification.NotificationRequestDTO;
import bw.co.starsign.notification.NotificationService;
import bw.co.starsign.organisation.Organisation;
import bw.co.starsign.organisation.OrganisationRepository;
import jakarta.persistence.criteria.Predicate;
import jakarta.validation.Valid;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Invitations of internal users. The invitee gets a Keycloak account at once (Keycloak emails them to verify their
 * address and choose a password) and a StarSign invitation email with a link holding the invitation token. Only the
 * token's SHA-256 hash is stored. Accepting, signed in with that account, creates the organisation profile and gives
 * the invited roles.
 *
 * @see bw.co.starsign.identity.UserInvitationService
 */
@Service("userInvitationService")
@Transactional(propagation = Propagation.REQUIRED, readOnly = false)
public class UserInvitationServiceImpl
    extends UserInvitationServiceBase
{
    /** Keycloak actions a new account must complete before it can sign in. */
    static final List<String> NEW_ACCOUNT_ACTIONS = List.of("VERIFY_EMAIL", "UPDATE_PASSWORD");

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CallerContext caller;
    private final OrganisationRepository organisationRepository;
    private final UserRoles userRoles;
    private final String portalUrl;
    private final int validityDays;

    public UserInvitationServiceImpl(
        @Lazy KeycloakAdminService keycloakAdminService,
        @Lazy NotificationService notificationService,
        @Lazy AuditTrailService auditTrailService,
        UserInvitationRepository userInvitationRepository,
        UserInvitationMapper userInvitationMapper,
        OrganisationUserRepository organisationUserRepository,
        OrganisationUserMapper organisationUserMapper,
        RoleRepository roleRepository,
        RoleMapper roleMapper,
        CallerContext caller,
        OrganisationRepository organisationRepository,
        UserRoles userRoles,
        @Value("${app.portal-url:http://localhost:4700}") String portalUrl,
        @Value("${app.invitations.validity-days:7}") int validityDays) {

        super(
            keycloakAdminService,
            notificationService,
            auditTrailService,
            userInvitationRepository,
            userInvitationMapper,
            organisationUserRepository,
            organisationUserMapper,
            roleRepository,
            roleMapper);

        this.caller = caller;
        this.organisationRepository = organisationRepository;
        this.userRoles = userRoles;
        this.portalUrl = StringUtils.removeEnd(portalUrl, "/");
        this.validityDays = validityDays;
    }

    /**
     * @see bw.co.starsign.identity.UserInvitationService#invite(String, UserInvitationRequestDTO)
     */
    @Override
    protected UserInvitationDTO handleInvite(String organisationId, @Valid UserInvitationRequestDTO request)
        throws Exception
    {
        Organisation organisation = loadOrganisation(organisationId);
        checkTenant(organisation);

        List<String> problems = new ArrayList<>();
        String email = StringUtils.trimToEmpty(request.getEmailAddress()).toLowerCase(Locale.ROOT);
        if (!EMAIL.matcher(email).matches())
        {
            problems.add("Give a valid email address");
        }
        if (StringUtils.isBlank(request.getFirstName()))
        {
            problems.add("Give the first name");
        }
        if (StringUtils.isBlank(request.getLastName()))
        {
            problems.add("Give the last name");
        }
        if (request.getRoleCodes() == null || request.getRoleCodes().stream().allMatch(StringUtils::isBlank))
        {
            problems.add("Choose at least one role");
        }
        if (!problems.isEmpty())
        {
            throw new UserInvitationServiceException(String.join("; ", problems));
        }

        if (organisationUserRepository.count(member(organisation, email)) > 0)
        {
            throw new UserInvitationServiceException(email + " is already a user of the organisation");
        }
        if (userInvitationRepository.count(pending(organisation, email)) > 0)
        {
            throw new UserInvitationServiceException(email + " already has an open invitation: resend it instead");
        }

        List<Role> roles = userRoles.resolve(request.getRoleCodes(), organisation, UserInvitationServiceException::new);

        prepareAccount(organisation, email, request);

        LocalDateTime now = LocalDateTime.now();
        String token = newToken();
        UserInvitation invitation = UserInvitation.Factory.newInstance();
        invitation.setEmailAddress(email);
        invitation.setFirstName(request.getFirstName().trim());
        invitation.setLastName(request.getLastName().trim());
        invitation.setJobTitle(StringUtils.trimToNull(request.getJobTitle()));
        invitation.setTokenHash(DigestUtils.sha256Hex(token));
        invitation.setStatus(UserInvitationStatus.PENDING);
        invitation.setExpiresAt(now.plusDays(validityDays));
        invitation.setSentAt(now);
        invitation.setResendCount(0);
        invitation.setOrganisation(organisation);
        invitation.setInvitedBy(organisationUserRepository.findByKeycloakUserId(caller.userId()).orElse(null));
        invitation.setRoles(new ArrayList<>(roles));
        invitation.setCreatedAt(now);
        invitation.setCreatedBy(caller.userId());
        invitation = userInvitationRepository.save(invitation);

        notifyInvitee(invitation, token, request.getMessage());
        audit(AuditEventType.USER_INVITED, invitation, "Roles: " + String.join(", ", roles.stream().map(Role::getCode).toList()));
        return toDTO(invitation);
    }

    /**
     * @see bw.co.starsign.identity.UserInvitationService#findByOrganisation(String, UserInvitationStatus)
     */
    @Override
    @Transactional(readOnly = true)
    protected List<UserInvitationDTO> handleFindByOrganisation(String organisationId, UserInvitationStatus status)
        throws Exception
    {
        Organisation organisation = loadOrganisation(organisationId);
        checkTenant(organisation);

        Specification<UserInvitation> specification = (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(builder.equal(root.get("organisation").get("id"), organisation.getId()));
            if (status != null)
            {
                predicates.add(builder.equal(root.get("status"), status));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
        return userInvitationRepository.findAll(specification, Sort.by(Sort.Direction.DESC, "createdAt")).stream()
            .map(this::toDTO)
            .toList();
    }

    /**
     * Sends the invitation again with a new token and a new expiry; links in earlier emails stop working.
     *
     * @see bw.co.starsign.identity.UserInvitationService#resend(String)
     */
    @Override
    protected UserInvitationDTO handleResend(String id)
        throws Exception
    {
        UserInvitation invitation = loadInTenant(id);
        if (invitation.getStatus() == UserInvitationStatus.ACCEPTED)
        {
            throw new UserInvitationServiceException("The invitation has already been accepted");
        }
        if (invitation.getStatus() == UserInvitationStatus.REVOKED)
        {
            throw new UserInvitationServiceException("The invitation has been revoked: invite the user again");
        }

        LocalDateTime now = LocalDateTime.now();
        String token = newToken();
        invitation.setTokenHash(DigestUtils.sha256Hex(token));
        invitation.setStatus(UserInvitationStatus.PENDING);
        invitation.setExpiresAt(now.plusDays(validityDays));
        invitation.setSentAt(now);
        invitation.setResendCount(invitation.getResendCount() + 1);
        invitation.setModifiedAt(now);
        invitation.setModifiedBy(caller.userId());
        invitation = userInvitationRepository.save(invitation);

        notifyInvitee(invitation, token, null);

        // The Keycloak link (verify email, choose password) may have expired as well
        KeycloakUserDTO account = keycloakAdminService.findUserByEmail(invitation.getEmailAddress());
        if (account != null && account.getRequiredActions() != null && !account.getRequiredActions().isEmpty())
        {
            keycloakAdminService.sendRequiredActions(account.getUserId(), account.getRequiredActions(), validitySeconds());
        }
        return toDTO(invitation);
    }

    /**
     * An account that was created for the invitation and never used is disabled, so it cannot be used to sign in.
     *
     * @see bw.co.starsign.identity.UserInvitationService#revoke(String)
     */
    @Override
    protected UserInvitationDTO handleRevoke(String id)
        throws Exception
    {
        UserInvitation invitation = loadInTenant(id);
        if (invitation.getStatus() == UserInvitationStatus.REVOKED)
        {
            return toDTO(invitation);
        }
        if (invitation.getStatus() == UserInvitationStatus.ACCEPTED)
        {
            throw new UserInvitationServiceException("The invitation has already been accepted: deactivate the user instead");
        }

        LocalDateTime now = LocalDateTime.now();
        invitation.setStatus(UserInvitationStatus.REVOKED);
        invitation.setRevokedAt(now);
        invitation.setModifiedAt(now);
        invitation.setModifiedBy(caller.userId());
        invitation = userInvitationRepository.save(invitation);

        KeycloakUserDTO account = keycloakAdminService.findUserByEmail(invitation.getEmailAddress());
        String organisationId = invitation.getOrganisation().getId().toString();
        if (account != null && organisationId.equals(account.getOrganisationId())
            && organisationUserRepository.findByKeycloakUserId(account.getUserId()).isEmpty())
        {
            keycloakAdminService.setEnabled(account.getUserId(), false);
        }
        return toDTO(invitation);
    }

    /**
     * @see bw.co.starsign.identity.UserInvitationService#accept(InvitationAcceptanceDTO)
     */
    @Override
    protected OrganisationUserDTO handleAccept(@Valid InvitationAcceptanceDTO acceptance)
        throws Exception
    {
        if (StringUtils.isBlank(acceptance.getToken()))
        {
            throw new UserInvitationServiceException("The invitation link is incomplete");
        }

        UserInvitation invitation = userInvitationRepository.findByTokenHash(DigestUtils.sha256Hex(acceptance.getToken().trim()))
            .orElseThrow(() -> UserInvitationServiceException.notFound(
                "The invitation does not exist or a newer invitation has been sent"));

        switch (invitation.getStatus())
        {
            case ACCEPTED -> throw new UserInvitationServiceException("The invitation has already been accepted");
            case REVOKED -> throw new UserInvitationServiceException("The invitation has been revoked");
            case EXPIRED -> throw new UserInvitationServiceException("The invitation has expired: ask for a new one");
            default -> { }
        }
        if (invitation.getExpiresAt().isBefore(LocalDateTime.now()))
        {
            throw new UserInvitationServiceException("The invitation has expired: ask for a new one");
        }

        String userId = caller.userId();
        Organisation organisation = invitation.getOrganisation();
        String organisationId = organisation.getId().toString();
        KeycloakUserDTO account = keycloakAdminService.findUserById(userId);
        if (!invitation.getEmailAddress().equalsIgnoreCase(StringUtils.trimToEmpty(account.getEmail())))
        {
            throw new UserInvitationServiceException("The invitation was sent to another email address: sign in as "
                + invitation.getEmailAddress());
        }
        if (StringUtils.isNotBlank(account.getOrganisationId()) && !organisationId.equals(account.getOrganisationId()))
        {
            throw new UserInvitationServiceException("Your account belongs to another organisation");
        }

        LocalDateTime now = LocalDateTime.now();
        OrganisationUser user = memberFor(invitation, userId);
        user.setKeycloakUserId(userId);
        user.setUsername(StringUtils.defaultIfBlank(account.getUsername(), invitation.getEmailAddress()));
        user.setEmailAddress(invitation.getEmailAddress());
        user.setFirstName(StringUtils.defaultIfBlank(account.getFirstName(), invitation.getFirstName()));
        user.setLastName(StringUtils.defaultIfBlank(account.getLastName(), invitation.getLastName()));
        if (invitation.getJobTitle() != null)
        {
            user.setJobTitle(invitation.getJobTitle());
        }
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerified(Boolean.TRUE.equals(account.getEmailVerified()));
        user.setOrganisation(organisation);
        user.setDeletedAt(null);
        user.setDeactivatedAt(null);
        user.setLockedUntil(null);
        user.setFailedLoginCount(0);
        if (user.getLocale() == null)
        {
            user.setLocale(organisation.getLocale());
        }
        if (user.getCreatedAt() == null)
        {
            user.setCreatedAt(now);
            user.setCreatedBy(userId);
        }
        else
        {
            user.setModifiedAt(now);
            user.setModifiedBy(userId);
        }

        String assignedBy = invitation.getInvitedBy() == null ? userId : invitation.getInvitedBy().getKeycloakUserId();
        user.getRoles().clear();
        for (Role role : invitation.getRoles())
        {
            user.addRoles(UserRole.Factory.newInstance(now, assignedBy, now, assignedBy, user, role));
        }
        user = organisationUserRepository.save(user);

        invitation.setStatus(UserInvitationStatus.ACCEPTED);
        invitation.setAcceptedAt(now);
        invitation.setAcceptedUser(user);
        invitation.setModifiedAt(now);
        invitation.setModifiedBy(userId);
        userInvitationRepository.save(invitation);

        AuditEventDTO event = event(AuditEventType.USER_ACTIVATED, invitation, "Accepted the invitation");
        event.setActorType(ActorType.ORGANISATION_USER);
        event.setAuthenticationMethod(AuthenticationMethod.KEYCLOAK_PASSWORD);
        event.setEntityType("OrganisationUser");
        event.setEntityId(user.getId().toString());
        auditTrailService.record(event);

        // Tenant claims and roles take effect with the user's next token
        keycloakAdminService.updateUser(tenantAttributes(userId, organisation));
        keycloakAdminService.assignRealmRoles(userId, UserRoles.keycloakRoles(invitation.getRoles()));
        if (StringUtils.isNotBlank(organisation.getKeycloakOrganisationId()))
        {
            keycloakAdminService.addOrganisationMember(organisation.getKeycloakOrganisationId(), userId);
        }

        OrganisationUserDTO dto = organisationUserMapper.toOrganisationUserDTO(user);
        dto.setOrganisationId(organisationId);
        dto.setOrganisationName(organisation.getName());
        dto.setRoles(user.getRoles().stream().map(userRole -> userRole.getRole().getCode()).sorted().toList());
        return dto;
    }

    /**
     * Scheduled: open invitations past their expiry become EXPIRED.
     *
     * @see bw.co.starsign.identity.UserInvitationService#expireOverdue()
     */
    @Override
    protected int handleExpireOverdue()
        throws Exception
    {
        LocalDateTime now = LocalDateTime.now();
        Specification<UserInvitation> overdue = (root, query, builder) -> builder.and(
            builder.equal(root.get("status"), UserInvitationStatus.PENDING),
            builder.lessThan(root.get("expiresAt"), now));

        List<UserInvitation> invitations = userInvitationRepository.findAll(overdue);
        for (UserInvitation invitation : invitations)
        {
            invitation.setStatus(UserInvitationStatus.EXPIRED);
            invitation.setModifiedAt(now);
            invitation.setModifiedBy("system");
        }
        userInvitationRepository.saveAll(invitations);
        return invitations.size();
    }

    /**
     * Makes sure the invitee has a Keycloak account of this organisation: an existing one is reused (and enabled
     * again after a revoked invitation), otherwise one is created and Keycloak emails the invitee to verify their
     * address and choose a password.
     */
    private void prepareAccount(Organisation organisation, String email, UserInvitationRequestDTO request)
    {
        String organisationId = organisation.getId().toString();
        KeycloakUserDTO account = keycloakAdminService.findUserByEmail(email);

        if (account == null)
        {
            KeycloakUserDTO user = new KeycloakUserDTO();
            user.setUsername(email);
            user.setEmail(email);
            user.setFirstName(request.getFirstName().trim());
            user.setLastName(request.getLastName().trim());
            user.setEnabled(true);
            user.setEmailVerified(false);
            user.setOrganisationId(organisationId);
            user.setAttributes(tenantAttributeMap(organisation));
            keycloakAdminService.createUser(user, NEW_ACCOUNT_ACTIONS);
            return;
        }

        if (StringUtils.isNotBlank(account.getOrganisationId()) && !organisationId.equals(account.getOrganisationId()))
        {
            throw new UserInvitationServiceException(email + " already has an account in another organisation");
        }
        if (StringUtils.isBlank(account.getOrganisationId()) && account.getRealmRoles() != null
            && !account.getRealmRoles().isEmpty())
        {
            // E.g. a platform administrator: such accounts are never moved into an organisation
            throw new UserInvitationServiceException(email + " already has an account that cannot join an organisation");
        }

        KeycloakUserDTO update = tenantAttributes(account.getUserId(), organisation);
        update.setEnabled(true);
        keycloakAdminService.updateUser(update);
        if (account.getRequiredActions() != null && !account.getRequiredActions().isEmpty())
        {
            keycloakAdminService.sendRequiredActions(account.getUserId(), account.getRequiredActions(), validitySeconds());
        }
    }

    /**
     * The profile the accepting account takes over: its own, a removed profile with the same email address in the
     * organisation, or a new one.
     */
    private OrganisationUser memberFor(UserInvitation invitation, String userId)
    {
        Organisation organisation = invitation.getOrganisation();
        Optional<OrganisationUser> own = organisationUserRepository.findByKeycloakUserId(userId);
        if (own.isPresent())
        {
            if (!own.get().getOrganisation().getId().equals(organisation.getId()))
            {
                throw new UserInvitationServiceException("Your account is already used in another organisation");
            }
            if (own.get().getDeletedAt() == null && own.get().getStatus() == UserStatus.ACTIVE)
            {
                throw new UserInvitationServiceException("You already are a user of this organisation");
            }
            return own.get();
        }

        Specification<OrganisationUser> sameEmail = (root, query, builder) -> builder.and(
            builder.equal(root.get("organisation").get("id"), organisation.getId()),
            builder.equal(builder.lower(root.get("emailAddress")), invitation.getEmailAddress()));
        Optional<OrganisationUser> previous = organisationUserRepository.findOne(sameEmail);
        if (previous.isPresent())
        {
            if (previous.get().getDeletedAt() == null)
            {
                throw new UserInvitationServiceException("Another account with this email address already is a user of the organisation");
            }
            return previous.get();
        }
        return OrganisationUser.Factory.newInstance();
    }

    private void notifyInvitee(UserInvitation invitation, String token, String message)
    {
        Organisation organisation = invitation.getOrganisation();
        Map<String, Object> model = new HashMap<>();
        model.put("firstName", invitation.getFirstName());
        model.put("lastName", invitation.getLastName());
        model.put("organisationName", organisation.getName());
        model.put("invitedByName", invitedByName(invitation));
        model.put("acceptUrl", portalUrl + "/invitations/accept?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8));
        model.put("expiresAt", invitation.getExpiresAt().toString());
        model.put("roles", invitation.getRoles().stream().map(Role::getName).toList());
        if (StringUtils.isNotBlank(message))
        {
            model.put("message", message.trim());
        }

        NotificationRequestDTO request = new NotificationRequestDTO();
        request.setOrganisationId(organisation.getId().toString());
        request.setEventType(NotificationEventType.USER_INVITATION);
        request.setChannel(NotificationChannel.EMAIL);
        request.setRecipientName((invitation.getFirstName() + " " + invitation.getLastName()).trim());
        request.setRecipientAddress(invitation.getEmailAddress());
        request.setTemplateModel(model);
        // A resend is a new email, a retry of the same send is not
        request.setDeduplicationKey("user-invitation:" + invitation.getId() + ":" + invitation.getResendCount());
        request.setLocale(organisation.getLocale());
        notificationService.enqueue(request);
    }

    private KeycloakUserDTO tenantAttributes(String userId, Organisation organisation)
    {
        KeycloakUserDTO update = new KeycloakUserDTO();
        update.setUserId(userId);
        update.setOrganisationId(organisation.getId().toString());
        update.setAttributes(tenantAttributeMap(organisation));
        return update;
    }

    private static Map<String, Object> tenantAttributeMap(Organisation organisation)
    {
        Map<String, Object> attributes = new HashMap<>();
        if (StringUtils.isNotBlank(organisation.getTenantId()))
        {
            attributes.put(KeycloakAdminServiceImpl.TENANT_ATTRIBUTE, List.of(organisation.getTenantId()));
        }
        attributes.put(KeycloakAdminServiceImpl.ORGANISATION_ATTRIBUTE, List.of(organisation.getId().toString()));
        return attributes;
    }

    private Specification<OrganisationUser> member(Organisation organisation, String email)
    {
        return (root, query, builder) -> builder.and(
            builder.equal(root.get("organisation").get("id"), organisation.getId()),
            builder.equal(builder.lower(root.get("emailAddress")), email),
            builder.isNull(root.get("deletedAt")));
    }

    private Specification<UserInvitation> pending(Organisation organisation, String email)
    {
        return (root, query, builder) -> builder.and(
            builder.equal(root.get("organisation").get("id"), organisation.getId()),
            builder.equal(builder.lower(root.get("emailAddress")), email),
            builder.equal(root.get("status"), UserInvitationStatus.PENDING),
            builder.greaterThan(root.get("expiresAt"), LocalDateTime.now()));
    }

    private Organisation loadOrganisation(String organisationId)
    {
        return organisationRepository.findById(uuid(organisationId, "organisation"))
            .orElseThrow(() -> UserInvitationServiceException.notFound("The organisation does not exist"));
    }

    private UserInvitation loadInTenant(String id)
    {
        UserInvitation invitation = userInvitationRepository.findById(uuid(id, "invitation"))
            .orElseThrow(() -> UserInvitationServiceException.notFound("The invitation does not exist"));
        checkTenant(invitation.getOrganisation());
        return invitation;
    }

    /** Callers with a tenant only see their own organisation; others' records are reported as missing. */
    private void checkTenant(Organisation organisation)
    {
        if (caller.tenantId().isPresent()
            && !organisation.getId().toString().equals(caller.organisationId().orElse(null)))
        {
            throw UserInvitationServiceException.notFound("The invitation does not exist");
        }
    }

    private UserInvitationDTO toDTO(UserInvitation invitation)
    {
        UserInvitationDTO dto = userInvitationMapper.toUserInvitationDTO(invitation);
        dto.setOrganisationId(invitation.getOrganisation().getId().toString());
        dto.setInvitedByName(invitedByName(invitation));
        dto.setRoleCodes(invitation.getRoles().stream().map(Role::getCode).sorted().toList());
        return dto;
    }

    private static String invitedByName(UserInvitation invitation)
    {
        OrganisationUser invitedBy = invitation.getInvitedBy();
        if (invitedBy == null)
        {
            return null;
        }
        return StringUtils.trimToNull(StringUtils.defaultString(invitedBy.getFirstName()) + " "
            + StringUtils.defaultString(invitedBy.getLastName()));
    }

    private void audit(AuditEventType type, UserInvitation invitation, String details)
    {
        auditTrailService.record(event(type, invitation, details));
    }

    private AuditEventDTO event(AuditEventType type, UserInvitation invitation, String details)
    {
        AuditEventDTO event = new AuditEventDTO();
        event.setEventType(type);
        event.setOccurredAt(LocalDateTime.now());
        event.setOrganisationId(invitation.getOrganisation().getId().toString());
        event.setChainKey(invitation.getOrganisation().getTenantId());
        event.setActorId(caller.userId());
        event.setActorType(caller.tenantId().isPresent() ? ActorType.ORGANISATION_USER : ActorType.PLATFORM_ADMIN);
        event.setOutcome(AuditOutcome.SUCCESS);
        event.setEntityType("UserInvitation");
        event.setEntityId(invitation.getId().toString());
        event.setDetails(details);
        return event;
    }

    private int validitySeconds()
    {
        return validityDays * 24 * 3600;
    }

    private static String newToken()
    {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static UUID uuid(String id, String what)
    {
        try
        {
            return UUID.fromString(id.trim());
        }
        catch (IllegalArgumentException | NullPointerException e)
        {
            throw UserInvitationServiceException.notFound("'" + id + "' is not the id of an " + what);
        }
    }
}
