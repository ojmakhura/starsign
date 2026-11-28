// license-header java merge-point
/**
 * This is only generated once! It will never be overwritten.
 * You can (and have to!) safely modify it by hand.
 * TEMPLATE:    SpringServiceImpl.vsl in andromda-spring cartridge
 * MODEL CLASS: AndroMDAModel::backend::bw.co.starsign::identity::OrganisationUserService
 * STEREOTYPE:  Service
 */
package bw.co.starsign.identity;

import bw.co.starsign.audit.ActorType;
import bw.co.starsign.audit.AuditEventDTO;
import bw.co.starsign.audit.AuditEventType;
import bw.co.starsign.audit.AuditOutcome;
import bw.co.starsign.audit.AuditTrailService;
import bw.co.starsign.audit.AuthenticationMethod;
import bw.co.starsign.common.SortDirection;
import bw.co.starsign.context.CallerContext;
import bw.co.starsign.organisation.Organisation;
import bw.co.starsign.organisation.OrganisationMapper;
import bw.co.starsign.organisation.OrganisationRepository;
import bw.co.starsign.workflow.Signatory;
import bw.co.starsign.workflow.SignatoryRepository;
import bw.co.starsign.workflow.SignatoryStatus;
import bw.co.starsign.workflow.WorkflowStatus;
import jakarta.persistence.criteria.Predicate;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Lazy;
import bw.co.starsign.common.Pages;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Organisation users: the local anchor of a Keycloak account that belongs to one organisation. Keycloak owns the
 * account: credentials, MFA, sessions, lockout, realm roles and the profile attributes (phone number, job title,
 * locale). The database keeps what StarSign needs to reference and report on the user (name, email address, status,
 * last sign-in). The DTOs returned here carry only those; the web layer adds the Keycloak details
 * (KeycloakUserDetails). Keycloak is called after the local change, so a Keycloak failure rolls the local change
 * back.
 *
 * @see bw.co.starsign.identity.OrganisationUserService
 */
@Service("organisationUserService")
@Transactional(propagation = Propagation.REQUIRED, readOnly = false)
public class OrganisationUserServiceImpl
    extends OrganisationUserServiceBase
{
    /** The page size of a paged search that gives none, and the largest one allowed. */
    private static final int DEFAULT_PAGE_SIZE = 25;
    private static final int MAX_PAGE_SIZE = 200;

    /**
     * Every permission scope the API knows (keycloak/ROLE_MATRIX.md, webservice AuthzResources): the caller's
     * permissions are the ones of these that their token grants.
     */
    static final List<String> RESOURCES = List.of(
        "admin-configuration", "admin-notification-providers", "admin-storage", "audit", "branding", "documents",
        "invitations", "notification-templates", "notifications", "organisations", "reports", "roles", "signing",
        "subscriptions", "templates", "users", "workflows");

    static final List<String> ACTIONS = List.of(
        "self", "view", "list", "edit", "delete", "initiate", "cancel", "sign", "verify", "export", "manage",
        "configure", "administer");

    /** Resources on which owner-scoped roles get {@code <action>-own} grants. */
    static final List<String> OWNED_RESOURCES = List.of("documents", "workflows");

    /** Signatory states in which an internal signatory still has to act. */
    private static final List<SignatoryStatus> AWAITING_SIGNATURE = List.of(
        SignatoryStatus.INVITED, SignatoryStatus.VIEWED, SignatoryStatus.VERIFIED);

    private static final String UPDATE_PASSWORD = "UPDATE_PASSWORD";
    private static final String CONFIGURE_TOTP = "CONFIGURE_TOTP";

    private final CallerContext caller;
    private final OrganisationRepository organisationRepository;
    private final OrganisationMapper organisationMapper;
    private final SignatoryRepository signatoryRepository;
    private final UserRoles userRoles;

    public OrganisationUserServiceImpl(
        @Lazy KeycloakAdminService keycloakAdminService,
        @Lazy AuditTrailService auditTrailService,
        OrganisationUserRepository organisationUserRepository,
        OrganisationUserMapper organisationUserMapper,
        RoleRepository roleRepository,
        RoleMapper roleMapper,
        CallerContext caller,
        OrganisationRepository organisationRepository,
        OrganisationMapper organisationMapper,
        SignatoryRepository signatoryRepository,
        UserRoles userRoles) {

        super(
            keycloakAdminService,
            auditTrailService,
            organisationUserRepository,
            organisationUserMapper,
            roleRepository,
            roleMapper);

        this.caller = caller;
        this.organisationRepository = organisationRepository;
        this.organisationMapper = organisationMapper;
        this.signatoryRepository = signatoryRepository;
        this.userRoles = userRoles;
    }

    /**
     * Works for every signed-in caller: organisation users get their profile and organisation, platform
     * administrators (no organisation) the account details from Keycloak. A member of an organisation without a
     * local profile yet gets one on first call.
     *
     * @see bw.co.starsign.identity.OrganisationUserService#getCurrentUser()
     */
    @Override
    protected CurrentUserDTO handleGetCurrentUser()
        throws Exception
    {
        String userId = caller.userId();

        OrganisationUser user = organisationUserRepository.findByKeycloakUserId(userId)
            .orElseGet(() -> provision(userId));
        if (user != null && user.getDeletedAt() != null)
        {
            user = null;
        }

        CurrentUserDTO current = new CurrentUserDTO();
        current.setPermissions(currentPermissions());
        current.setPlatformAdministrator(caller.has("organisations:administer"));

        if (user != null)
        {
            current.setUser(toDTO(user));
            current.setOrganisation(organisationMapper.toOrganisationListDTO(user.getOrganisation()));
            current.setPendingSignatureCount(pendingSignatures(user));
        }
        else
        {
            current.setUser(accountOnly(userId));
            current.setPendingSignatureCount(0L);
        }
        return current;
    }

    /**
     * @see bw.co.starsign.identity.OrganisationUserService#findById(String)
     */
    @Override
    @Transactional(readOnly = true)
    protected OrganisationUserDTO handleFindById(String id)
        throws Exception
    {
        return toDTO(loadInTenant(id));
    }

    /**
     * Lookup for other services (e.g. internal signing); not limited to the caller's organisation.
     *
     * @see bw.co.starsign.identity.OrganisationUserService#findByKeycloakUserId(String)
     */
    @Override
    @Transactional(readOnly = true)
    protected OrganisationUserDTO handleFindByKeycloakUserId(String keycloakUserId)
        throws Exception
    {
        if (StringUtils.isBlank(keycloakUserId))
        {
            throw OrganisationUserServiceException.notFound("The user does not exist");
        }
        OrganisationUser user = organisationUserRepository.findByKeycloakUserId(keycloakUserId.trim())
            .filter(found -> found.getDeletedAt() == null)
            .orElseThrow(() -> OrganisationUserServiceException.notFound("The user does not exist"));
        return toDTO(user);
    }

    /**
     * Organisation users only ever search their own organisation; platform administrators may search one
     * organisation or all.
     *
     * @see bw.co.starsign.identity.OrganisationUserService#search(OrganisationUserSearchCriteria)
     */
    @Override
    @Transactional(readOnly = true)
    protected List<OrganisationUserListDTO> handleSearch(@Valid OrganisationUserSearchCriteria criteria)
        throws Exception
    {
        Specification<OrganisationUser> specification = searchSpecification(criteria);
        Sort sort = sort(criteria.getSortField(), criteria.getSortDirection());
        Pageable page = Pageable.unpaged(sort);
        if (criteria.getPageSize() != null && criteria.getPageSize() > 0)
        {
            int number = criteria.getPageNumber() == null ? 0 : Math.max(0, criteria.getPageNumber());
            page = PageRequest.of(number, criteria.getPageSize(), sort);
        }
        return organisationUserRepository.findListItems(specification, page);
    }

    /**
     * @see bw.co.starsign.identity.OrganisationUserService#pagedSearch(OrganisationUserSearchCriteria)
     */
    @Override
    protected Page<OrganisationUserListDTO> handlePagedSearch(@Valid OrganisationUserSearchCriteria criteria)
        throws Exception
    {
        Specification<OrganisationUser> specification = searchSpecification(criteria);
        Sort sort = sort(criteria.getSortField(), criteria.getSortDirection());
        return organisationUserRepository.findListItemPage(specification,
            Pages.request(criteria, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE, sort));
    }

    /** The search's specification: users not removed, of the caller's organisation unless platform-wide. */
    private Specification<OrganisationUser> searchSpecification(OrganisationUserSearchCriteria criteria)
    {
        String organisationId = scopedOrganisation(criteria.getOrganisationId());
        Set<String> roleHolders = StringUtils.isBlank(criteria.getRoleCode()) ? null
            : roleHolders(criteria.getRoleCode().trim(), organisationId);

        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(builder.isNull(root.get("deletedAt")));
            if (organisationId != null)
            {
                predicates.add(builder.equal(root.get("organisation").get("id"), uuid(organisationId, "organisation")));
            }
            if (criteria.getStatus() != null)
            {
                predicates.add(builder.equal(root.get("status"), criteria.getStatus()));
            }
            if (StringUtils.isNotBlank(criteria.getText()))
            {
                String pattern = "%" + criteria.getText().trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(builder.or(
                    builder.like(builder.lower(root.get("firstName")), pattern),
                    builder.like(builder.lower(root.get("lastName")), pattern),
                    builder.like(builder.lower(root.get("emailAddress")), pattern),
                    builder.like(builder.lower(root.get("username")), pattern)));
            }
            if (roleHolders != null)
            {
                predicates.add(roleHolders.isEmpty() ? builder.disjunction() : root.get("keycloakUserId").in(roleHolders));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }

    /**
     * The Keycloak users who hold the role with this code (directly, as StarSign assigns roles). Keycloak owns the
     * assignments; the search then keeps those of the organisation.
     */
    private Set<String> roleHolders(String roleCode, String organisationId)
    {
        List<String> realmRoles = roleRepository.findAll().stream()
            .filter(role -> roleCode.equals(role.getCode()))
            .filter(role -> role.getOrganisation() == null || organisationId == null
                || role.getOrganisation().getId().toString().equals(organisationId))
            .map(Role::getKeycloakRoleName)
            .filter(StringUtils::isNotBlank)
            .distinct()
            .toList();
        Set<String> holders = new LinkedHashSet<>();
        realmRoles.forEach(name -> holders.addAll(keycloakAdminService.findRealmRoleMemberIds(name)));
        return holders;
    }

    /**
     * @see bw.co.starsign.identity.OrganisationUserService#findByOrganisation(String)
     */
    @Override
    @Transactional(readOnly = true)
    protected List<OrganisationUserListDTO> handleFindByOrganisation(String organisationId)
        throws Exception
    {
        String scoped = scopedOrganisation(organisationId);
        if (scoped == null)
        {
            throw new OrganisationUserServiceException("Say which organisation's users to list");
        }
        UUID organisation = uuid(scoped, "organisation");
        Specification<OrganisationUser> specification = (root, query, builder) -> builder.and(
            builder.equal(root.get("organisation").get("id"), organisation),
            builder.isNull(root.get("deletedAt")));
        return organisationUserRepository.findListItems(specification, Pageable.unpaged(sort(null, null)));
    }

    /**
     * @see bw.co.starsign.identity.OrganisationUserService#updateProfile(String, UserProfileUpdateDTO)
     */
    @Override
    protected OrganisationUserDTO handleUpdateProfile(String id, @Valid UserProfileUpdateDTO profile)
        throws Exception
    {
        OrganisationUser user = loadInTenant(id);

        List<String> problems = new ArrayList<>();
        if (profile.getFirstName() != null && StringUtils.isBlank(profile.getFirstName()))
        {
            problems.add("The first name cannot be empty");
        }
        if (profile.getLastName() != null && StringUtils.isBlank(profile.getLastName()))
        {
            problems.add("The last name cannot be empty");
        }
        if (!problems.isEmpty())
        {
            throw new OrganisationUserServiceException(String.join("; ", problems));
        }

        if (profile.getFirstName() != null)
        {
            user.setFirstName(profile.getFirstName().trim());
        }
        if (profile.getLastName() != null)
        {
            user.setLastName(profile.getLastName().trim());
        }
        touch(user);
        user = organisationUserRepository.save(user);

        // Keycloak owns the profile: the name (also shown on its pages and in the tokens; kept here as a copy) and
        // the phone number, job title and locale attributes
        KeycloakUserDTO account = new KeycloakUserDTO();
        account.setUserId(user.getKeycloakUserId());
        account.setFirstName(user.getFirstName());
        account.setLastName(user.getLastName());
        account.setAttributes(KeycloakAttributes.changes(profile.getPhoneNumber(), profile.getJobTitle(),
            profile.getLocale()));
        keycloakAdminService.updateUser(account);

        return toDTO(user);
    }

    /**
     * @see bw.co.starsign.identity.OrganisationUserService#activate(String)
     */
    @Override
    protected OrganisationUserDTO handleActivate(String id)
        throws Exception
    {
        OrganisationUser user = loadInTenant(id);
        if (user.getStatus() == UserStatus.ACTIVE)
        {
            return toDTO(user);
        }

        user.setStatus(UserStatus.ACTIVE);
        user.setDeactivatedAt(null);
        touch(user);
        user = organisationUserRepository.save(user);

        audit(AuditEventType.USER_ACTIVATED, user, null);
        keycloakAdminService.setEnabled(user.getKeycloakUserId(), true);
        return toDTO(user);
    }

    /**
     * @see bw.co.starsign.identity.OrganisationUserService#deactivate(String, String)
     */
    @Override
    protected OrganisationUserDTO handleDeactivate(String id, String reason)
        throws Exception
    {
        if (StringUtils.isBlank(reason))
        {
            throw new OrganisationUserServiceException("Say why the user is deactivated");
        }

        OrganisationUser user = loadInTenant(id);
        checkNotSelf(user, "You cannot deactivate your own account");
        if (user.getStatus() == UserStatus.DEACTIVATED)
        {
            return toDTO(user);
        }

        // Signing services refuse internal signatories whose user is DEACTIVATED
        user.setStatus(UserStatus.DEACTIVATED);
        user.setDeactivatedAt(LocalDateTime.now());
        touch(user);
        user = organisationUserRepository.save(user);

        audit(AuditEventType.USER_DEACTIVATED, user, reason.trim());
        keycloakAdminService.setEnabled(user.getKeycloakUserId(), false);
        keycloakAdminService.logoutUser(user.getKeycloakUserId());
        return toDTO(user);
    }

    /**
     * Ends a lockout by Keycloak's brute-force detection (failed sign-ins); Keycloak keeps the failure count.
     *
     * @see bw.co.starsign.identity.OrganisationUserService#unlock(String)
     */
    @Override
    protected OrganisationUserDTO handleUnlock(String id)
        throws Exception
    {
        OrganisationUser user = loadInTenant(id);
        if (user.getStatus() == UserStatus.DEACTIVATED)
        {
            throw new OrganisationUserServiceException("The user is deactivated: activate the user instead");
        }

        if (user.getStatus() == UserStatus.LOCKED)
        {
            // Profiles locked before Keycloak took lockouts over
            user.setStatus(UserStatus.ACTIVE);
        }
        touch(user);
        user = organisationUserRepository.save(user);

        audit(AuditEventType.USER_ACTIVATED, user, "Unlocked");
        keycloakAdminService.clearLoginFailures(user.getKeycloakUserId());
        return toDTO(user);
    }

    /**
     * Replaces the user's StarSign roles, which Keycloak holds as realm roles: realm roles that are no StarSign role
     * of the organisation (given outside StarSign) are left alone.
     *
     * @see bw.co.starsign.identity.OrganisationUserService#assignRoles(String, RoleAssignmentDTO)
     */
    @Override
    protected OrganisationUserDTO handleAssignRoles(String id, @Valid RoleAssignmentDTO assignment)
        throws Exception
    {
        OrganisationUser user = loadInTenant(id);
        List<Role> wanted = userRoles.resolve(assignment.getRoleCodes(), user.getOrganisation(),
            OrganisationUserServiceException::new);

        KeycloakUserDTO account = keycloakAdminService.findUserById(user.getKeycloakUserId());
        List<Role> previous = userRoles.held(account.getRealmRoles(), user.getOrganisation());
        Set<UUID> wantedIds = new LinkedHashSet<>(wanted.stream().map(Role::getId).toList());
        Set<UUID> previousIds = new LinkedHashSet<>(previous.stream().map(Role::getId).toList());

        if (wantedIds.equals(previousIds))
        {
            return toDTO(user);
        }

        touch(user);
        user = organisationUserRepository.save(user);

        audit(AuditEventType.USER_ROLES_CHANGED, user,
            "Roles: " + String.join(", ", wanted.stream().map(Role::getCode).toList()));

        List<String> keep = UserRoles.keycloakRoles(wanted);
        List<String> removed = previous.stream()
            .map(Role::getKeycloakRoleName)
            .filter(name -> StringUtils.isNotBlank(name) && !keep.contains(name))
            .distinct()
            .toList();
        keycloakAdminService.removeRealmRoles(user.getKeycloakUserId(), removed);
        keycloakAdminService.assignRealmRoles(user.getKeycloakUserId(), keep);
        return toDTO(user);
    }

    /**
     * The permissions granted by the StarSign roles the user holds in Keycloak (role permission catalogue).
     *
     * @see bw.co.starsign.identity.OrganisationUserService#getPermissions(String)
     */
    @Override
    @Transactional(readOnly = true)
    protected List<String> handleGetPermissions(String id)
        throws Exception
    {
        OrganisationUser user = loadInTenant(id);
        KeycloakUserDTO account = keycloakAdminService.findUserById(user.getKeycloakUserId());
        Set<String> permissions = new TreeSet<>();
        for (Role role : userRoles.held(account.getRealmRoles(), user.getOrganisation()))
        {
            role.getPermissions().forEach(permission -> permissions.add(permission.getCode()));
        }
        return new ArrayList<>(permissions);
    }

    /**
     * @see bw.co.starsign.identity.OrganisationUserService#getSessions(String)
     */
    @Override
    @Transactional(readOnly = true)
    protected List<KeycloakSessionDTO> handleGetSessions(String id)
        throws Exception
    {
        return keycloakAdminService.getUserSessions(loadInTenant(id).getKeycloakUserId());
    }

    /**
     * @see bw.co.starsign.identity.OrganisationUserService#terminateSessions(String)
     */
    @Override
    protected void handleTerminateSessions(String id)
        throws Exception
    {
        keycloakAdminService.logoutUser(loadInTenant(id).getKeycloakUserId());
    }

    /**
     * Keycloak emails the user a link to choose a new password; StarSign never handles passwords.
     *
     * @see bw.co.starsign.identity.OrganisationUserService#requestPasswordReset(String)
     */
    @Override
    protected void handleRequestPasswordReset(String id)
        throws Exception
    {
        OrganisationUser user = loadInTenant(id);
        if (user.getStatus() == UserStatus.DEACTIVATED)
        {
            throw new OrganisationUserServiceException("The user is deactivated: activate the user first");
        }
        keycloakAdminService.sendRequiredActions(user.getKeycloakUserId(), List.of(UPDATE_PASSWORD), 0);
    }

    /**
     * The user must set up an authenticator app at their next sign-in.
     *
     * @see bw.co.starsign.identity.OrganisationUserService#requireMfa(String)
     */
    @Override
    protected void handleRequireMfa(String id)
        throws Exception
    {
        OrganisationUser user = loadInTenant(id);
        KeycloakUserDTO account = keycloakAdminService.findUserById(user.getKeycloakUserId());
        List<String> actions = new ArrayList<>(account.getRequiredActions() == null ? List.of() : account.getRequiredActions());
        if (!actions.contains(CONFIGURE_TOTP))
        {
            actions.add(CONFIGURE_TOTP);
            KeycloakUserDTO update = new KeycloakUserDTO();
            update.setUserId(user.getKeycloakUserId());
            update.setRequiredActions(actions);
            keycloakAdminService.updateUser(update);
        }
    }

    /**
     * Called after a successful Keycloak sign-in; accounts without a local profile (platform administrators) are
     * not recorded.
     *
     * @see bw.co.starsign.identity.OrganisationUserService#recordLogin(String, String, String)
     */
    @Override
    protected void handleRecordLogin(String keycloakUserId, String ipAddress, String userAgent)
        throws Exception
    {
        if (StringUtils.isBlank(keycloakUserId))
        {
            return;
        }
        OrganisationUser user = organisationUserRepository.findByKeycloakUserId(keycloakUserId.trim())
            .filter(found -> found.getDeletedAt() == null)
            .orElse(null);
        if (user == null)
        {
            return;
        }

        user.setLastLoginAt(LocalDateTime.now());
        user.setLastLoginIp(StringUtils.truncate(ipAddress, 64));
        if (user.getStatus() == UserStatus.INVITED || user.getStatus() == UserStatus.PENDING_VERIFICATION)
        {
            user.setStatus(UserStatus.ACTIVE);
        }
        user = organisationUserRepository.save(user);

        AuditEventDTO event = event(AuditEventType.USER_LOGIN, user, null);
        event.setActorType(ActorType.ORGANISATION_USER);
        event.setActorId(user.getKeycloakUserId());
        event.setActorDisplayName(displayName(user));
        event.setAuthenticationMethod(authenticationMethod(user));
        event.setIpAddress(ipAddress);
        event.setUserAgent(userAgent);
        auditTrailService.record(event);
    }

    /**
     * Soft delete: the profile stays for the audit history, the Keycloak account is disabled and signed out.
     *
     * @see bw.co.starsign.identity.OrganisationUserService#remove(String)
     */
    @Override
    protected boolean handleRemove(String id)
        throws Exception
    {
        OrganisationUser user = loadInTenant(id);
        checkNotSelf(user, "You cannot remove your own account");

        LocalDateTime now = LocalDateTime.now();
        user.setDeletedAt(now);
        user.setStatus(UserStatus.DEACTIVATED);
        if (user.getDeactivatedAt() == null)
        {
            user.setDeactivatedAt(now);
        }
        touch(user);
        user = organisationUserRepository.save(user);

        audit(AuditEventType.USER_DEACTIVATED, user, "Removed");
        keycloakAdminService.setEnabled(user.getKeycloakUserId(), false);
        keycloakAdminService.logoutUser(user.getKeycloakUserId());
        Organisation organisation = user.getOrganisation();
        if (StringUtils.isNotBlank(organisation.getKeycloakOrganisationId()))
        {
            keycloakAdminService.removeOrganisationMember(organisation.getKeycloakOrganisationId(), user.getKeycloakUserId());
        }
        return true;
    }

    /**
     * Creates the local anchor of a member of an organisation on their first call. Only callers whose token
     * carries an organisation and who hold an organisation role qualify: accounts that were invited but never
     * accepted have no role yet. Their roles stay in Keycloak.
     */
    private OrganisationUser provision(String userId)
    {
        String organisationId = caller.organisationId().orElse(null);
        if (organisationId == null || !caller.has("organisations:view"))
        {
            return null;
        }

        UUID organisationUuid;
        try
        {
            organisationUuid = UUID.fromString(organisationId);
        }
        catch (IllegalArgumentException e)
        {
            logger.warn("Token of user {} names organisation '{}', which is not an organisation id", userId, organisationId);
            return null;
        }
        Organisation organisation = organisationRepository.findById(organisationUuid).orElse(null);
        if (organisation == null)
        {
            logger.warn("Token of user {} names organisation {}, which does not exist", userId, organisationId);
            return null;
        }

        KeycloakUserDTO account = keycloakAdminService.findUserById(userId);
        String email = StringUtils.trimToEmpty(account.getEmail()).toLowerCase(Locale.ROOT);

        Specification<OrganisationUser> sameEmail = (root, query, builder) -> builder.and(
            builder.equal(root.get("organisation").get("id"), organisation.getId()),
            builder.equal(builder.lower(root.get("emailAddress")), email));
        if (organisationUserRepository.count(sameEmail) > 0)
        {
            logger.warn("Not provisioning user {}: organisation {} already has a user with email {}", userId,
                organisationId, email);
            return null;
        }

        LocalDateTime now = LocalDateTime.now();
        OrganisationUser user = OrganisationUser.Factory.newInstance();
        user.setKeycloakUserId(userId);
        user.setUsername(StringUtils.defaultIfBlank(account.getUsername(), email));
        user.setEmailAddress(email);
        user.setFirstName(StringUtils.defaultString(account.getFirstName()));
        user.setLastName(StringUtils.defaultString(account.getLastName()));
        user.setStatus(UserStatus.ACTIVE);
        user.setOrganisation(organisation);
        user.setLastLoginAt(now);
        user.setCreatedAt(now);
        user.setCreatedBy(userId);

        logger.info("Provisioned the profile of user {} in organisation {}", userId, organisationId);
        return organisationUserRepository.save(user);
    }

    /**
     * A caller without a profile (platform administrators): only the Keycloak user id, the web layer fills in the
     * account details from Keycloak and the token.
     */
    private static OrganisationUserDTO accountOnly(String userId)
    {
        OrganisationUserDTO dto = new OrganisationUserDTO();
        dto.setKeycloakUserId(userId);
        dto.setStatus(UserStatus.ACTIVE);
        dto.setRoles(new ArrayList<>());
        return dto;
    }

    /** How the user signs in, for the audit trail: with a second factor when they have one set up in Keycloak. */
    private AuthenticationMethod authenticationMethod(OrganisationUser user)
    {
        try
        {
            return keycloakAdminService.isMfaConfigured(user.getKeycloakUserId())
                ? AuthenticationMethod.KEYCLOAK_MFA : AuthenticationMethod.KEYCLOAK_PASSWORD;
        }
        catch (RuntimeException e)
        {
            logger.warn("Could not read the credentials of user {}: {}", user.getKeycloakUserId(), e.getMessage());
            return AuthenticationMethod.KEYCLOAK_PASSWORD;
        }
    }

    /** The permission scopes the caller's token grants, in the API's own format (e.g. "documents:view"). */
    private List<String> currentPermissions()
    {
        List<String> permissions = new ArrayList<>();
        for (String resource : RESOURCES)
        {
            for (String action : ACTIONS)
            {
                String permission = resource + ":" + action;
                if (caller.has(permission))
                {
                    permissions.add(permission);
                }
                if (OWNED_RESOURCES.contains(resource) && caller.has(permission + "-own"))
                {
                    permissions.add(permission + "-own");
                }
            }
        }
        return permissions;
    }

    /** Signing requests waiting for the user as internal signatory in workflows that are in progress. */
    private long pendingSignatures(OrganisationUser user)
    {
        Specification<Signatory> waiting = (root, query, builder) -> builder.and(
            builder.equal(root.get("internalUser").get("id"), user.getId()),
            root.get("status").in(AWAITING_SIGNATURE),
            builder.equal(root.get("workflow").get("status"), WorkflowStatus.IN_PROGRESS));
        return signatoryRepository.count(waiting);
    }

    /**
     * The organisation a request may look at: callers with a tenant are limited to their own organisation,
     * platform administrators (no tenant) to the one they ask for, or all when they ask for none.
     */
    private String scopedOrganisation(String requested)
    {
        if (caller.tenantId().isEmpty())
        {
            return StringUtils.trimToNull(requested);
        }

        String own = caller.organisationId()
            .orElseThrow(() -> new OrganisationUserServiceException("Your account is not linked to an organisation"));
        if (StringUtils.isNotBlank(requested) && !own.equals(requested.trim()))
        {
            throw OrganisationUserServiceException.notFound("The organisation does not exist");
        }
        return own;
    }

    private OrganisationUser loadInTenant(String id)
    {
        OrganisationUser user = organisationUserRepository.findById(uuid(id, "user"))
            .filter(found -> found.getDeletedAt() == null)
            .orElseThrow(() -> OrganisationUserServiceException.notFound("The user does not exist"));

        // Users of other organisations are reported as missing rather than forbidden
        if (caller.tenantId().isPresent()
            && !user.getOrganisation().getId().toString().equals(caller.organisationId().orElse(null)))
        {
            throw OrganisationUserServiceException.notFound("The user does not exist");
        }
        return user;
    }

    private void checkNotSelf(OrganisationUser user, String message)
    {
        if (user.getKeycloakUserId().equals(caller.userId()))
        {
            throw new OrganisationUserServiceException(message);
        }
    }

    private void touch(OrganisationUser user)
    {
        user.setModifiedAt(LocalDateTime.now());
        user.setModifiedBy(caller.userId());
    }

    private OrganisationUserDTO toDTO(OrganisationUser user)
    {
        OrganisationUserDTO dto = organisationUserMapper.toOrganisationUserDTO(user);
        dto.setOrganisationId(user.getOrganisation().getId().toString());
        dto.setOrganisationName(user.getOrganisation().getName());
        return dto;
    }

    private static Sort sort(String field, SortDirection direction)
    {
        Sort.Direction order = direction == SortDirection.DESC ? Sort.Direction.DESC : Sort.Direction.ASC;
        Set<String> sortable = Set.of("firstName", "lastName", "emailAddress", "username", "status", "lastLoginAt",
            "createdAt");
        if (StringUtils.isNotBlank(field) && sortable.contains(field.trim()))
        {
            return Sort.by(order, field.trim()).and(Sort.by("id"));
        }
        return Sort.by(order, "lastName", "firstName", "id");
    }

    private void audit(AuditEventType type, OrganisationUser user, String details)
    {
        auditTrailService.record(event(type, user, details));
    }

    private AuditEventDTO event(AuditEventType type, OrganisationUser user, String details)
    {
        AuditEventDTO event = new AuditEventDTO();
        event.setEventType(type);
        event.setOccurredAt(LocalDateTime.now());
        event.setOrganisationId(user.getOrganisation().getId().toString());
        event.setChainKey(user.getOrganisation().getTenantId());
        event.setOutcome(AuditOutcome.SUCCESS);
        event.setEntityType("OrganisationUser");
        event.setEntityId(user.getId() == null ? null : user.getId().toString());
        event.setDetails(details);
        if (type != AuditEventType.USER_LOGIN)
        {
            event.setActorId(caller.userId());
            event.setActorType(caller.tenantId().isPresent() ? ActorType.ORGANISATION_USER : ActorType.PLATFORM_ADMIN);
        }
        return event;
    }

    private static String displayName(OrganisationUser user)
    {
        return StringUtils.trimToNull(StringUtils.defaultString(user.getFirstName()) + " "
            + StringUtils.defaultString(user.getLastName()));
    }

    private static UUID uuid(String id, String what)
    {
        try
        {
            return UUID.fromString(id.trim());
        }
        catch (IllegalArgumentException | NullPointerException e)
        {
            throw OrganisationUserServiceException.notFound("'" + id + "' is not the id of a " + what);
        }
    }
}
