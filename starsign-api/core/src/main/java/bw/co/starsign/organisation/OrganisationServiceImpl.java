// license-header java merge-point
/**
 * This is only generated once! It will never be overwritten.
 * You can (and have to!) safely modify it by hand.
 * TEMPLATE:    SpringServiceImpl.vsl in andromda-spring cartridge
 * MODEL CLASS: AndroMDAModel::backend::bw.co.starsign::organisation::OrganisationService
 * STEREOTYPE:  Service
 */
package bw.co.starsign.organisation;

import org.springframework.data.domain.Page;
import bw.co.starsign.common.Pages;
import bw.co.starsign.StarsignSpecifications;
import bw.co.starsign.audit.ActorType;
import bw.co.starsign.audit.AuditEventDTO;
import bw.co.starsign.audit.AuditEventType;
import bw.co.starsign.audit.AuditOutcome;
import bw.co.starsign.audit.AuditTrailService;
import bw.co.starsign.branding.BrandingService;
import bw.co.starsign.context.CallerContext;
import bw.co.starsign.identity.KeycloakAdminService;
import bw.co.starsign.identity.KeycloakAdminServiceException;
import bw.co.starsign.identity.KeycloakUserDTO;
import bw.co.starsign.identity.OrganisationUser;
import bw.co.starsign.identity.OrganisationUserRepository;
import bw.co.starsign.identity.Role;
import bw.co.starsign.identity.RoleRepository;
import bw.co.starsign.identity.SystemRole;
import bw.co.starsign.identity.UserRole;
import bw.co.starsign.identity.UserStatus;
import bw.co.starsign.messaging.OutboxEventDTO;
import bw.co.starsign.messaging.OutboxService;
import bw.co.starsign.platform.PlatformConfigurationService;
import bw.co.starsign.platform.SecurityPolicyDTO;
import bw.co.starsign.platform.SystemConfigurationRepository;
import bw.co.starsign.signing.SignatureAssuranceLevel;
import bw.co.starsign.signing.VerificationMethod;
import bw.co.starsign.subscription.SubscriptionPlanDTO;
import bw.co.starsign.subscription.SubscriptionService;
import bw.co.starsign.workflow.ExecutionMode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import java.net.URI;
import java.security.SecureRandom;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * @see bw.co.starsign.organisation.OrganisationService
 */
@Service("organisationService")
@Transactional(propagation = Propagation.REQUIRED, readOnly = false)
public class OrganisationServiceImpl
    extends OrganisationServiceBase
{
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern PHONE = Pattern.compile("^\\+?[0-9 ()-]{6,32}$");
    private static final String DEFAULT_TIME_ZONE = "Africa/Gaborone";
    private static final String DEFAULT_LOCALE = "en";
    private static final String ADMIN_ROLE = "ORGANISATION_ADMIN";
    private static final List<String> ADMIN_ACTIONS = List.of("VERIFY_EMAIL", "UPDATE_PASSWORD");
    /** Platform setting: whether a verified organisation becomes ACTIVE without a platform administrator. */
    static final String AUTO_ACTIVATE_KEY = "organisations.auto-activate";
    private static final String DEFAULT_CONSENT = "By signing electronically you agree that your electronic signature"
            + " is the legal equivalent of your handwritten signature and that you consent to do business electronically.";

    /** The OrganisationStatus state machine: where each status may go. */
    private static final Map<OrganisationStatus, Set<OrganisationStatus>> TRANSITIONS = Map.of(
            OrganisationStatus.PENDING_VERIFICATION, EnumSet.of(OrganisationStatus.ACTIVE, OrganisationStatus.CLOSED),
            OrganisationStatus.ACTIVE, EnumSet.of(OrganisationStatus.SUSPENDED, OrganisationStatus.INACTIVE,
                    OrganisationStatus.CLOSED),
            OrganisationStatus.SUSPENDED, EnumSet.of(OrganisationStatus.ACTIVE, OrganisationStatus.INACTIVE,
                    OrganisationStatus.CLOSED),
            OrganisationStatus.INACTIVE, EnumSet.of(OrganisationStatus.ACTIVE, OrganisationStatus.CLOSED),
            OrganisationStatus.CLOSED, EnumSet.noneOf(OrganisationStatus.class));

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper JSON = new ObjectMapper();

    private final CallerContext caller;
    private final PlatformConfigurationService platformConfigurationService;
    private final SystemConfigurationRepository systemConfigurationRepository;
    private final OrganisationUserRepository organisationUserRepository;
    private final RoleRepository roleRepository;

    public OrganisationServiceImpl(
        @Lazy KeycloakAdminService keycloakAdminService,
        @Lazy BrandingService brandingService,
        @Lazy AuditTrailService auditTrailService,
        @Lazy OutboxService outboxService,
        @Lazy SubscriptionService subscriptionService,
        OrganisationRepository organisationRepository,
        OrganisationMapper organisationMapper,
        OrganisationSigningPolicyRepository organisationSigningPolicyRepository,
        OrganisationSigningPolicyMapper organisationSigningPolicyMapper,
        OrganisationNotificationSettingsRepository organisationNotificationSettingsRepository,
        OrganisationNotificationSettingsMapper organisationNotificationSettingsMapper,
        CallerContext caller,
        @Lazy PlatformConfigurationService platformConfigurationService,
        SystemConfigurationRepository systemConfigurationRepository,
        OrganisationUserRepository organisationUserRepository,
        RoleRepository roleRepository) {

        super(
            keycloakAdminService,
            brandingService,
            auditTrailService,
            outboxService,
            subscriptionService,
            organisationRepository,
            organisationMapper,
            organisationSigningPolicyRepository,
            organisationSigningPolicyMapper,
            organisationNotificationSettingsRepository,
            organisationNotificationSettingsMapper);
        this.caller = caller;
        this.platformConfigurationService = platformConfigurationService;
        this.systemConfigurationRepository = systemConfigurationRepository;
        this.organisationUserRepository = organisationUserRepository;
        this.roleRepository = roleRepository;
    }

    /**
     * @see bw.co.starsign.organisation.OrganisationService#register(@Valid OrganisationRegistrationDTO)
     */
    @Override
    protected OrganisationDTO handleRegister(@Valid OrganisationRegistrationDTO registration)
        throws Exception
    {
        checkRegistration(registration);

        String registrationNumber = registration.getRegistrationNumber().trim();
        if (organisationRepository.findByRegistrationNumberIgnoreCase(registrationNumber).isPresent())
        {
            throw new OrganisationServiceException(
                    "An organisation with the registration number " + registrationNumber + " is already registered");
        }
        String adminEmail = registration.getAdminEmailAddress().trim().toLowerCase(Locale.ROOT);
        if (keycloakUserExists(adminEmail))
        {
            throw new OrganisationServiceException(
                    "There is already an account for " + adminEmail + ": sign in or use another email address");
        }

        // Nobody is signed in yet: the registration is its own author
        String author = "self-registration";
        LocalDateTime now = LocalDateTime.now();

        Organisation organisation = Organisation.Factory.newInstance();
        organisation.setTenantId(newTenantId(registration.getName()));
        organisation.setName(registration.getName().trim());
        organisation.setRegistrationNumber(registrationNumber);
        organisation.setTradingName(StringUtils.trimToNull(registration.getTradingName()));
        organisation.setEmailAddress(registration.getEmailAddress().trim().toLowerCase(Locale.ROOT));
        organisation.setPhoneNumber(StringUtils.trimToNull(registration.getPhoneNumber()));
        organisation.setWebsite(StringUtils.trimToNull(registration.getWebsite()));
        applyPhysicalAddress(organisation, registration.getPhysicalAddress());
        applyPostalAddress(organisation, registration.getPostalAddress());
        organisation.setStatus(OrganisationStatus.PENDING_VERIFICATION);
        organisation.setEmailVerified(false);
        organisation.setTimeZone(StringUtils.defaultIfBlank(StringUtils.trimToNull(registration.getTimeZone()),
                DEFAULT_TIME_ZONE));
        organisation.setLocale(StringUtils.defaultIfBlank(StringUtils.trimToNull(registration.getLocale()),
                DEFAULT_LOCALE));
        organisation.setSigningPolicy(defaultSigningPolicy(author, now));
        organisation.setNotificationSettings(defaultNotificationSettings(author, now));
        organisation.setCreatedBy(author);
        organisation.setCreatedAt(now);
        organisation = organisationRepository.save(organisation);
        String organisationId = organisation.getId().toString();

        // Keycloak is not part of the transaction: it is called last, once the organisation is valid and stored
        String keycloakOrganisationId = keycloakAdminService.createOrganisation(organisation.getName(),
                organisation.getTenantId(), organisationDomain(organisation));
        organisation.setKeycloakOrganisationId(keycloakOrganisationId);

        KeycloakUserDTO admin = new KeycloakUserDTO();
        admin.setUsername(adminEmail);
        admin.setEmail(adminEmail);
        admin.setFirstName(registration.getAdminFirstName().trim());
        admin.setLastName(registration.getAdminLastName().trim());
        admin.setEnabled(true);
        admin.setEmailVerified(false);
        admin.setOrganisationId(organisationId);
        // The realm's tenant_id / organisation_id token mappers read these user attributes
        Map<String, List<String>> attributes = new HashMap<String, List<String>>();
        attributes.put("tenantId", List.of(organisation.getTenantId()));
        attributes.put("organisationId", List.of(organisationId));
        if (StringUtils.isNotBlank(registration.getAdminPhoneNumber()))
        {
            attributes.put("phoneNumber", List.of(registration.getAdminPhoneNumber().trim()));
        }
        admin.setAttributes(attributes);
        admin.setRealmRoles(List.of(ADMIN_ROLE));
        admin.setRequiredActions(ADMIN_ACTIONS);
        KeycloakUserDTO created = keycloakAdminService.createUser(admin, ADMIN_ACTIONS);
        if (created == null || StringUtils.isBlank(created.getUserId()))
        {
            throw new OrganisationServiceException("Keycloak did not return the administrator's account",
                    new IllegalStateException("createUser returned no user id"));
        }
        keycloakAdminService.assignRealmRoles(created.getUserId(), List.of(ADMIN_ROLE));
        if (keycloakOrganisationId != null)
        {
            keycloakAdminService.addOrganisationMember(keycloakOrganisationId, created.getUserId());
        }
        addAdministrator(organisation, created, registration, author, now);
        organisation = organisationRepository.save(organisation);

        startTrial(organisationId);

        audit(organisation, AuditEventType.ORGANISATION_REGISTERED, ActorType.ANONYMOUS, null,
                Map.of("tenantId", organisation.getTenantId(), "administrator", adminEmail));
        publish(organisation, "ORGANISATION_REGISTERED", "organisation.registered", Map.of(
                "organisationId", organisationId,
                "tenantId", organisation.getTenantId(),
                "name", organisation.getName(),
                "administratorUserId", created.getUserId(),
                "administratorEmail", adminEmail));
        return toDTO(organisation);
    }

    /**
     * @see bw.co.starsign.organisation.OrganisationService#verifyEmail(String)
     */
    @Override
    protected OrganisationDTO handleVerifyEmail(String organisationId)
        throws Exception
    {
        Organisation organisation = load(organisationId);
        if (organisation.isEmailVerified())
        {
            return toDTO(organisation);
        }
        LocalDateTime now = LocalDateTime.now();
        organisation.setEmailVerified(true);
        organisation.setEmailVerifiedAt(now);
        organisation.setModifiedBy("system");
        organisation.setModifiedAt(now);
        // Without auto-activation a platform administrator approves the organisation through changeStatus
        if (organisation.getStatus() == OrganisationStatus.PENDING_VERIFICATION && autoActivate())
        {
            organisation.setStatus(OrganisationStatus.ACTIVE);
            organisation.setActivatedAt(now);
            organisation.setStatusReason("Email address verified");
            audit(organisation, AuditEventType.ORGANISATION_STATUS_CHANGED, ActorType.SYSTEM, null,
                    Map.of("from", OrganisationStatus.PENDING_VERIFICATION.name(),
                            "to", OrganisationStatus.ACTIVE.name(), "reason", "Email address verified"));
            publish(organisation, "ORGANISATION_STATUS_CHANGED", "organisation.status-changed", Map.of(
                    "organisationId", organisation.getId().toString(),
                    "from", OrganisationStatus.PENDING_VERIFICATION.name(),
                    "to", OrganisationStatus.ACTIVE.name()));
        }
        return toDTO(organisationRepository.save(organisation));
    }

    /**
     * @see bw.co.starsign.organisation.OrganisationService#findById(String)
     */
    @Override
    protected OrganisationDTO handleFindById(String id)
        throws Exception
    {
        return toDTO(loadVisible(id));
    }

    /**
     * @see bw.co.starsign.organisation.OrganisationService#findByTenantId(String)
     */
    @Override
    protected OrganisationDTO handleFindByTenantId(String tenantId)
        throws Exception
    {
        Organisation organisation = organisationRepository.findByTenantId(tenantId.trim())
                .filter(found -> found.getDeletedAt() == null)
                .orElseThrow(() -> OrganisationServiceException.notFound("The organisation does not exist"));
        checkVisible(organisation);
        return toDTO(organisation);
    }

    /**
     * @see bw.co.starsign.organisation.OrganisationService#getCurrentOrganisation()
     */
    @Override
    protected OrganisationDTO handleGetCurrentOrganisation()
        throws Exception
    {
        // Platform administrators carry neither claim: they have no organisation of their own
        if (caller.organisationId().isPresent())
        {
            return toDTO(loadVisible(caller.organisationId().get()));
        }
        if (caller.tenantId().isPresent())
        {
            return handleFindByTenantId(caller.tenantId().get());
        }
        throw OrganisationServiceException.notFound("You do not belong to an organisation");
    }

    /**
     * @see bw.co.starsign.organisation.OrganisationService#search(@Valid OrganisationSearchCriteria)
     */
    @Override
    protected List<OrganisationListDTO> handleSearch(@Valid OrganisationSearchCriteria criteria)
        throws Exception
    {
        requirePlatformAdministrator("search organisations");
        return organisationRepository.findListItems(searchSpecification(criteria), SEARCH_SORT);
    }

    /**
     * @see bw.co.starsign.organisation.OrganisationService#pagedSearch(OrganisationSearchCriteria)
     */
    @Override
    protected Page<OrganisationListDTO> handlePagedSearch(@Valid OrganisationSearchCriteria criteria)
        throws Exception
    {
        requirePlatformAdministrator("search organisations");
        return organisationRepository.findListItemPage(searchSpecification(criteria),
                Pages.request(criteria, 25, 200, SEARCH_SORT));
    }

    /** Organisations are listed by name. */
    private static final Sort SEARCH_SORT = Sort.by(Sort.Direction.ASC, "name").and(Sort.by("id"));

    private static Specification<Organisation> searchSpecification(OrganisationSearchCriteria criteria)
    {
        Specification<Organisation> spec = StarsignSpecifications.findByAttributeNull("deletedAt");
        if (StringUtils.isNotBlank(criteria.getName()))
        {
            String name = criteria.getName().trim();
            spec = spec.and(StarsignSpecifications.<Organisation, String>findByAttributeContainingIgnoreCase(name, "name")
                    .or(StarsignSpecifications.findByAttributeContainingIgnoreCase(name, "tradingName")));
        }
        if (StringUtils.isNotBlank(criteria.getRegistrationNumber()))
        {
            spec = spec.and(StarsignSpecifications.findByAttributeContainingIgnoreCase(
                    criteria.getRegistrationNumber().trim(), "registrationNumber"));
        }
        if (criteria.getStatus() != null)
        {
            spec = spec.and(StarsignSpecifications.findByAttributeEquals(criteria.getStatus(), "status"));
        }
        if (criteria.getCreatedFrom() != null)
        {
            spec = spec.and(StarsignSpecifications.findByAttributeGreaterThanEqual(
                    criteria.getCreatedFrom().atStartOfDay(), "createdAt"));
        }
        if (criteria.getCreatedTo() != null)
        {
            spec = spec.and(StarsignSpecifications.findByAttributeLessThan(
                    criteria.getCreatedTo().plusDays(1).atStartOfDay(), "createdAt"));
        }
        return spec;
    }

    /**
     * @see bw.co.starsign.organisation.OrganisationService#updateProfile(String, @Valid OrganisationDTO)
     */
    @Override
    protected OrganisationDTO handleUpdateProfile(String id, @Valid OrganisationDTO organisation)
        throws Exception
    {
        Organisation entity = loadVisible(id);
        if (entity.getStatus() == OrganisationStatus.CLOSED)
        {
            throw new OrganisationServiceException("A closed organisation cannot be changed");
        }
        if (organisation.getRowVersion() != null && entity.getRowVersion() != null
                && !organisation.getRowVersion().equals(entity.getRowVersion()))
        {
            throw new OrganisationServiceException(
                    "The organisation was changed by someone else in the meantime: reload it and try again");
        }

        // A PATCH: what is not given stays as it is
        List<String> errors = new ArrayList<String>();
        if (organisation.getName() != null && StringUtils.isBlank(organisation.getName()))
        {
            errors.add("The name cannot be empty");
        }
        if (organisation.getEmailAddress() != null && !EMAIL.matcher(organisation.getEmailAddress().trim()).matches())
        {
            errors.add("The email address is not valid");
        }
        if (StringUtils.isNotBlank(organisation.getPhoneNumber())
                && !PHONE.matcher(organisation.getPhoneNumber().trim()).matches())
        {
            errors.add("The phone number is not valid");
        }
        if (StringUtils.isNotBlank(organisation.getWebsite()) && !validWebsite(organisation.getWebsite()))
        {
            errors.add("The website is not a valid address");
        }
        if (organisation.getPhysicalAddress() != null)
        {
            checkAddress(organisation.getPhysicalAddress(), "physical", true, errors);
        }
        if (StringUtils.isNotBlank(organisation.getTimeZone()) && !validTimeZone(organisation.getTimeZone()))
        {
            errors.add("The time zone is not known");
        }
        if (StringUtils.isNotBlank(organisation.getRegistrationNumber())
                && !organisation.getRegistrationNumber().trim().equalsIgnoreCase(entity.getRegistrationNumber()))
        {
            // The registration number identifies the company: only the platform may correct it
            if (!isPlatformAdministrator())
            {
                errors.add("Only a platform administrator can change the registration number");
            }
            else if (organisationRepository.findByRegistrationNumberIgnoreCase(organisation.getRegistrationNumber().trim())
                    .filter(other -> !other.getId().equals(entity.getId())).isPresent())
            {
                errors.add("Another organisation has the registration number " + organisation.getRegistrationNumber().trim());
            }
        }
        if (!errors.isEmpty())
        {
            throw new OrganisationServiceException(String.join("; ", errors));
        }

        if (organisation.getName() != null)
        {
            entity.setName(organisation.getName().trim());
        }
        if (StringUtils.isNotBlank(organisation.getRegistrationNumber()))
        {
            entity.setRegistrationNumber(organisation.getRegistrationNumber().trim());
        }
        if (organisation.getTradingName() != null)
        {
            entity.setTradingName(StringUtils.trimToNull(organisation.getTradingName()));
        }
        if (organisation.getEmailAddress() != null)
        {
            entity.setEmailAddress(organisation.getEmailAddress().trim().toLowerCase(Locale.ROOT));
        }
        if (organisation.getPhoneNumber() != null)
        {
            entity.setPhoneNumber(StringUtils.trimToNull(organisation.getPhoneNumber()));
        }
        if (organisation.getWebsite() != null)
        {
            entity.setWebsite(StringUtils.trimToNull(organisation.getWebsite()));
        }
        if (organisation.getPhysicalAddress() != null)
        {
            applyPhysicalAddress(entity, organisation.getPhysicalAddress());
        }
        if (organisation.getPostalAddress() != null)
        {
            applyPostalAddress(entity, organisation.getPostalAddress());
        }
        if (StringUtils.isNotBlank(organisation.getTimeZone()))
        {
            entity.setTimeZone(organisation.getTimeZone().trim());
        }
        if (StringUtils.isNotBlank(organisation.getLocale()))
        {
            entity.setLocale(organisation.getLocale().trim());
        }
        entity.setModifiedBy(caller.userId());
        entity.setModifiedAt(LocalDateTime.now());

        Organisation saved = organisationRepository.save(entity);
        audit(saved, AuditEventType.ORGANISATION_UPDATED, actorType(), caller.userId(), Map.of("change", "profile"));
        return toDTO(saved);
    }

    /**
     * @see bw.co.starsign.organisation.OrganisationService#changeStatus(String, @Valid OrganisationStatusChangeDTO)
     */
    @Override
    protected OrganisationDTO handleChangeStatus(String id, @Valid OrganisationStatusChangeDTO change)
        throws Exception
    {
        requirePlatformAdministrator("change the status of an organisation");
        Organisation organisation = load(id);
        OrganisationStatus from = organisation.getStatus();
        OrganisationStatus to = change.getStatus();
        String reason = StringUtils.trimToNull(change.getReason());

        if (to == null)
        {
            throw new OrganisationServiceException("Say which status the organisation moves to");
        }
        if (to == from)
        {
            throw new OrganisationServiceException("The organisation is already " + from);
        }
        if (!TRANSITIONS.get(from).contains(to))
        {
            throw new OrganisationServiceException("An organisation that is " + from + " cannot become " + to);
        }
        if (reason == null && to != OrganisationStatus.ACTIVE)
        {
            throw new OrganisationServiceException("Give the reason the organisation becomes " + to);
        }

        LocalDateTime now = LocalDateTime.now();
        organisation.setStatus(to);
        organisation.setStatusReason(reason);
        switch (to)
        {
            case ACTIVE:
                organisation.setActivatedAt(now);
                organisation.setSuspendedAt(null);
                break;
            case SUSPENDED:
                organisation.setSuspendedAt(now);
                break;
            case CLOSED:
                organisation.setClosedAt(now);
                break;
            default:
                break;
        }
        organisation.setModifiedBy(caller.userId());
        organisation.setModifiedAt(now);
        Organisation saved = organisationRepository.save(organisation);

        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("from", from.name());
        details.put("to", to.name());
        details.put("reason", reason);
        audit(saved, AuditEventType.ORGANISATION_STATUS_CHANGED, ActorType.PLATFORM_ADMIN, caller.userId(), details);
        // Consumers react to it: users of a suspended or closed organisation lose access, a closed one stops billing
        Map<String, Object> payload = new LinkedHashMap<String, Object>(details);
        payload.put("organisationId", saved.getId().toString());
        payload.put("tenantId", saved.getTenantId());
        publish(saved, "ORGANISATION_STATUS_CHANGED", "organisation.status-changed", payload);
        return toDTO(saved);
    }

    /**
     * @see bw.co.starsign.organisation.OrganisationService#getSigningPolicy(String)
     */
    @Override
    protected OrganisationSigningPolicyDTO handleGetSigningPolicy(String id)
        throws Exception
    {
        Organisation organisation = loadVisible(id);
        return toDTO(organisation, signingPolicyOf(organisation));
    }

    /**
     * @see bw.co.starsign.organisation.OrganisationService#updateSigningPolicy(String, @Valid OrganisationSigningPolicyDTO)
     */
    @Override
    protected OrganisationSigningPolicyDTO handleUpdateSigningPolicy(String id, @Valid OrganisationSigningPolicyDTO policy)
        throws Exception
    {
        Organisation organisation = loadVisible(id);
        if (organisation.getStatus() == OrganisationStatus.CLOSED)
        {
            throw new OrganisationServiceException("A closed organisation cannot be changed");
        }
        OrganisationSigningPolicy entity = signingPolicyOf(organisation);
        checkSigningPolicy(organisation, entity, policy);

        // Null fields keep their current value
        if (policy.getDefaultExecutionMode() != null) entity.setDefaultExecutionMode(policy.getDefaultExecutionMode());
        if (policy.getDefaultExpiryDays() != null) entity.setDefaultExpiryDays(policy.getDefaultExpiryDays());
        if (policy.getInvitationValidityHours() != null) entity.setInvitationValidityHours(policy.getInvitationValidityHours());
        if (policy.getSingleUseInvitationLinks() != null) entity.setSingleUseInvitationLinks(policy.getSingleUseInvitationLinks());
        if (policy.getDefaultVerificationMethod() != null) entity.setDefaultVerificationMethod(policy.getDefaultVerificationMethod());
        if (policy.getMinimumAssuranceLevel() != null) entity.setMinimumAssuranceLevel(policy.getMinimumAssuranceLevel());
        if (policy.getAllowDrawnSignature() != null) entity.setAllowDrawnSignature(policy.getAllowDrawnSignature());
        if (policy.getAllowTypedSignature() != null) entity.setAllowTypedSignature(policy.getAllowTypedSignature());
        if (policy.getAllowUploadedSignatureImage() != null) entity.setAllowUploadedSignatureImage(policy.getAllowUploadedSignatureImage());
        if (policy.getAllowSignatoryDecline() != null) entity.setAllowSignatoryDecline(policy.getAllowSignatoryDecline());
        if (policy.getDeclineTerminatesWorkflow() != null) entity.setDeclineTerminatesWorkflow(policy.getDeclineTerminatesWorkflow());
        if (policy.getRequireDeclineReason() != null) entity.setRequireDeclineReason(policy.getRequireDeclineReason());
        if (policy.getSendCompletedCopyToSignatories() != null) entity.setSendCompletedCopyToSignatories(policy.getSendCompletedCopyToSignatories());
        if (policy.getDigitalSignatureEnabled() != null) entity.setDigitalSignatureEnabled(policy.getDigitalSignatureEnabled());
        if (policy.getCertificateAlias() != null) entity.setCertificateAlias(StringUtils.trimToNull(policy.getCertificateAlias()));
        if (policy.getMaxUploadSizeMb() != null) entity.setMaxUploadSizeMb(policy.getMaxUploadSizeMb());
        if (policy.getDocumentRetentionDays() != null) entity.setDocumentRetentionDays(policy.getDocumentRetentionDays());
        if (policy.getAuditRetentionDays() != null) entity.setAuditRetentionDays(policy.getAuditRetentionDays());
        if (policy.getConsentText() != null) entity.setConsentText(StringUtils.defaultIfBlank(policy.getConsentText().trim(), DEFAULT_CONSENT));
        entity.setModifiedBy(caller.userId());
        entity.setModifiedAt(LocalDateTime.now());

        OrganisationSigningPolicy saved = organisationSigningPolicyRepository.save(entity);
        audit(organisation, AuditEventType.SETTINGS_CHANGED, actorType(), caller.userId(), Map.of("change", "signing-policy"));
        return toDTO(organisation, saved);
    }

    /**
     * @see bw.co.starsign.organisation.OrganisationService#getNotificationSettings(String)
     */
    @Override
    protected OrganisationNotificationSettingsDTO handleGetNotificationSettings(String id)
        throws Exception
    {
        Organisation organisation = loadVisible(id);
        return toDTO(organisation, notificationSettingsOf(organisation));
    }

    /**
     * @see bw.co.starsign.organisation.OrganisationService#updateNotificationSettings(String, @Valid OrganisationNotificationSettingsDTO)
     */
    @Override
    protected OrganisationNotificationSettingsDTO handleUpdateNotificationSettings(String id, @Valid OrganisationNotificationSettingsDTO settings)
        throws Exception
    {
        Organisation organisation = loadVisible(id);
        if (organisation.getStatus() == OrganisationStatus.CLOSED)
        {
            throw new OrganisationServiceException("A closed organisation cannot be changed");
        }
        OrganisationNotificationSettings entity = notificationSettingsOf(organisation);
        checkNotificationSettings(entity, settings);

        if (settings.getEmailEnabled() != null) entity.setEmailEnabled(settings.getEmailEnabled());
        if (settings.getSmsEnabled() != null) entity.setSmsEnabled(settings.getSmsEnabled());
        if (settings.getInAppEnabled() != null) entity.setInAppEnabled(settings.getInAppEnabled());
        if (settings.getSenderDisplayName() != null) entity.setSenderDisplayName(StringUtils.trimToNull(settings.getSenderDisplayName()));
        if (settings.getReplyToEmail() != null) entity.setReplyToEmail(StringUtils.trimToNull(settings.getReplyToEmail()));
        if (settings.getRemindersEnabled() != null) entity.setRemindersEnabled(settings.getRemindersEnabled());
        if (settings.getReminderBeforeDeadlineHours() != null) entity.setReminderBeforeDeadlineHours(settings.getReminderBeforeDeadlineHours());
        if (settings.getReminderAfterInactivityHours() != null) entity.setReminderAfterInactivityHours(settings.getReminderAfterInactivityHours());
        if (settings.getReminderRepeatIntervalHours() != null) entity.setReminderRepeatIntervalHours(settings.getReminderRepeatIntervalHours());
        if (settings.getMaxReminders() != null) entity.setMaxReminders(settings.getMaxReminders());
        if (settings.getEscalateToOwner() != null) entity.setEscalateToOwner(settings.getEscalateToOwner());
        if (settings.getEscalateAfterReminders() != null) entity.setEscalateAfterReminders(settings.getEscalateAfterReminders());
        entity.setModifiedBy(caller.userId());
        entity.setModifiedAt(LocalDateTime.now());

        OrganisationNotificationSettings saved = organisationNotificationSettingsRepository.save(entity);
        audit(organisation, AuditEventType.SETTINGS_CHANGED, actorType(), caller.userId(),
                Map.of("change", "notification-settings"));
        return toDTO(organisation, saved);
    }

    private Organisation load(String id)
    {
        return organisationRepository.findById(uuid(id, "organisation"))
                .filter(organisation -> organisation.getDeletedAt() == null)
                .orElseThrow(() -> OrganisationServiceException.notFound("The organisation does not exist"));
    }

    /** The organisation, when the caller may see it. */
    private Organisation loadVisible(String id)
    {
        Organisation organisation = load(id);
        checkVisible(organisation);
        return organisation;
    }

    /**
     * Tenant isolation: organisation users only see their own organisation; to them another one does not exist.
     * Platform administrators carry no tenant and see every organisation.
     */
    private void checkVisible(Organisation organisation)
    {
        caller.tenantId().ifPresent(tenantId -> {
            if (!tenantId.equals(organisation.getTenantId()))
            {
                throw OrganisationServiceException.notFound("The organisation does not exist");
            }
        });
    }

    private boolean isPlatformAdministrator()
    {
        return caller.has("organisations:administer");
    }

    private void requirePlatformAdministrator(String what)
    {
        if (!isPlatformAdministrator())
        {
            throw new OrganisationServiceException("Only a platform administrator can " + what);
        }
    }

    private ActorType actorType()
    {
        return caller.tenantId().isPresent() ? ActorType.ORGANISATION_USER : ActorType.PLATFORM_ADMIN;
    }

    private static UUID uuid(String id, String what)
    {
        try
        {
            return UUID.fromString(id.trim());
        }
        catch (IllegalArgumentException e)
        {
            throw OrganisationServiceException.notFound("'" + id + "' is not the id of an " + what);
        }
    }

    private OrganisationDTO toDTO(Organisation entity)
    {
        OrganisationDTO dto = organisationMapper.toOrganisationDTO(entity);
        // The entity stores the addresses as columns; the mapper does not assemble them
        dto.setPhysicalAddress(new AddressDTO(entity.getPhysicalAddressLine1(), entity.getPhysicalAddressLine2(),
                entity.getPhysicalCity(), null, entity.getPhysicalCountry()));
        if (entity.getPostalAddressLine1() != null || entity.getPostalCity() != null || entity.getPostalCountry() != null)
        {
            dto.setPostalAddress(new AddressDTO(entity.getPostalAddressLine1(), entity.getPostalAddressLine2(),
                    entity.getPostalCity(), entity.getPostalCode(), entity.getPostalCountry()));
        }
        return dto;
    }

    private OrganisationSigningPolicyDTO toDTO(Organisation organisation, OrganisationSigningPolicy policy)
    {
        OrganisationSigningPolicyDTO dto = organisationSigningPolicyMapper.toOrganisationSigningPolicyDTO(policy);
        dto.setOrganisationId(organisation.getId().toString());
        return dto;
    }

    private OrganisationNotificationSettingsDTO toDTO(Organisation organisation, OrganisationNotificationSettings settings)
    {
        OrganisationNotificationSettingsDTO dto =
                organisationNotificationSettingsMapper.toOrganisationNotificationSettingsDTO(settings);
        dto.setOrganisationId(organisation.getId().toString());
        return dto;
    }

    /** The organisation's signing policy; organisations created without one get the defaults. */
    private OrganisationSigningPolicy signingPolicyOf(Organisation organisation)
    {
        if (organisation.getSigningPolicy() == null)
        {
            organisation.setSigningPolicy(defaultSigningPolicy("system", LocalDateTime.now()));
            organisationRepository.save(organisation);
        }
        return organisation.getSigningPolicy();
    }

    /** The organisation's notification settings; organisations created without them get the defaults. */
    private OrganisationNotificationSettings notificationSettingsOf(Organisation organisation)
    {
        if (organisation.getNotificationSettings() == null)
        {
            organisation.setNotificationSettings(defaultNotificationSettings("system", LocalDateTime.now()));
            organisationRepository.save(organisation);
        }
        return organisation.getNotificationSettings();
    }

    private static OrganisationSigningPolicy defaultSigningPolicy(String author, LocalDateTime now)
    {
        OrganisationSigningPolicy policy = OrganisationSigningPolicy.Factory.newInstance();
        policy.setDefaultExecutionMode(ExecutionMode.SEQUENTIAL);
        policy.setDefaultExpiryDays(30);
        policy.setInvitationValidityHours(168);
        policy.setSingleUseInvitationLinks(false);
        policy.setDefaultVerificationMethod(VerificationMethod.EMAIL_OTP);
        policy.setMinimumAssuranceLevel(SignatureAssuranceLevel.ELECTRONIC_BASIC);
        policy.setAllowDrawnSignature(true);
        policy.setAllowTypedSignature(true);
        policy.setAllowUploadedSignatureImage(false);
        policy.setAllowSignatoryDecline(true);
        policy.setDeclineTerminatesWorkflow(true);
        policy.setRequireDeclineReason(true);
        policy.setSendCompletedCopyToSignatories(true);
        policy.setDigitalSignatureEnabled(true);
        policy.setMaxUploadSizeMb(25);
        // Seven years for documents, ten for the evidence that they were signed
        policy.setDocumentRetentionDays(2555);
        policy.setAuditRetentionDays(3650);
        policy.setConsentText(DEFAULT_CONSENT);
        policy.setCreatedBy(author);
        policy.setCreatedAt(now);
        return policy;
    }

    private static OrganisationNotificationSettings defaultNotificationSettings(String author, LocalDateTime now)
    {
        OrganisationNotificationSettings settings = OrganisationNotificationSettings.Factory.newInstance();
        settings.setEmailEnabled(true);
        settings.setSmsEnabled(false);
        settings.setInAppEnabled(true);
        settings.setRemindersEnabled(true);
        settings.setReminderBeforeDeadlineHours(48);
        settings.setReminderAfterInactivityHours(72);
        settings.setReminderRepeatIntervalHours(48);
        settings.setMaxReminders(3);
        settings.setEscalateToOwner(true);
        settings.setEscalateAfterReminders(2);
        settings.setCreatedBy(author);
        settings.setCreatedAt(now);
        return settings;
    }

    private static void checkRegistration(OrganisationRegistrationDTO registration)
    {
        List<String> errors = new ArrayList<String>();
        if (StringUtils.isBlank(registration.getName()))
        {
            errors.add("The organisation name is required");
        }
        if (StringUtils.isBlank(registration.getRegistrationNumber()))
        {
            errors.add("The company registration number is required");
        }
        if (StringUtils.isBlank(registration.getEmailAddress())
                || !EMAIL.matcher(registration.getEmailAddress().trim()).matches())
        {
            errors.add("A valid organisation email address is required");
        }
        if (StringUtils.isNotBlank(registration.getPhoneNumber())
                && !PHONE.matcher(registration.getPhoneNumber().trim()).matches())
        {
            errors.add("The organisation phone number is not valid");
        }
        if (StringUtils.isNotBlank(registration.getWebsite()) && !validWebsite(registration.getWebsite()))
        {
            errors.add("The website is not a valid address");
        }
        if (registration.getPhysicalAddress() == null)
        {
            errors.add("The physical address is required");
        }
        else
        {
            checkAddress(registration.getPhysicalAddress(), "physical", true, errors);
        }
        if (StringUtils.isBlank(registration.getAdminFirstName()) || StringUtils.isBlank(registration.getAdminLastName()))
        {
            errors.add("The administrator's first and last name are required");
        }
        if (StringUtils.isBlank(registration.getAdminEmailAddress())
                || !EMAIL.matcher(registration.getAdminEmailAddress().trim()).matches())
        {
            errors.add("A valid administrator email address is required");
        }
        if (StringUtils.isNotBlank(registration.getAdminPhoneNumber())
                && !PHONE.matcher(registration.getAdminPhoneNumber().trim()).matches())
        {
            errors.add("The administrator phone number is not valid");
        }
        if (!Boolean.TRUE.equals(registration.getAcceptTerms()))
        {
            errors.add("The terms of service must be accepted");
        }
        if (StringUtils.isNotBlank(registration.getTimeZone()) && !validTimeZone(registration.getTimeZone()))
        {
            errors.add("The time zone is not known");
        }
        if (!errors.isEmpty())
        {
            throw new OrganisationServiceException(String.join("; ", errors));
        }
    }

    private static void checkAddress(AddressDTO address, String which, boolean required, List<String> errors)
    {
        if (required && (StringUtils.isBlank(address.getLine1()) || StringUtils.isBlank(address.getCity())
                || StringUtils.isBlank(address.getCountry())))
        {
            errors.add("The " + which + " address needs a first line, a city and a country");
        }
    }

    private void checkSigningPolicy(Organisation organisation, OrganisationSigningPolicy current,
            OrganisationSigningPolicyDTO policy)
    {
        List<String> errors = new ArrayList<String>();
        SecurityPolicyDTO platform = platformConfigurationService.getSecurityPolicy();

        if (policy.getDefaultExpiryDays() != null && (policy.getDefaultExpiryDays() < 1 || policy.getDefaultExpiryDays() > 365))
        {
            errors.add("Workflows expire after 1 to 365 days");
        }
        if (policy.getInvitationValidityHours() != null)
        {
            int max = platform.getMaxInvitationValidityHours() == null ? 8760 : platform.getMaxInvitationValidityHours();
            if (policy.getInvitationValidityHours() < 1 || policy.getInvitationValidityHours() > max)
            {
                errors.add("Signing invitations are valid for 1 to " + max + " hours");
            }
        }
        if (policy.getMinimumAssuranceLevel() != null && platform.getMinimumAssuranceLevel() != null
                && policy.getMinimumAssuranceLevel().ordinal() < platform.getMinimumAssuranceLevel().ordinal())
        {
            errors.add("The platform requires at least the " + platform.getMinimumAssuranceLevel() + " assurance level");
        }
        if (policy.getMaxUploadSizeMb() != null)
        {
            int max = platform.getMaxUploadSizeMb() == null ? 100 : platform.getMaxUploadSizeMb();
            if (policy.getMaxUploadSizeMb() < 1 || policy.getMaxUploadSizeMb() > max)
            {
                errors.add("Uploads may be limited to 1 to " + max + " MB");
            }
        }
        int documentRetention = policy.getDocumentRetentionDays() != null
                ? policy.getDocumentRetentionDays() : current.getDocumentRetentionDays();
        int auditRetention = policy.getAuditRetentionDays() != null
                ? policy.getAuditRetentionDays() : current.getAuditRetentionDays();
        if (documentRetention < 1 || auditRetention < 1)
        {
            errors.add("Retention periods must be at least one day");
        }
        else if (auditRetention < documentRetention)
        {
            // The evidence must outlive the documents it is about
            errors.add("The audit trail must be kept at least as long as the documents");
        }
        boolean allowDrawn = policy.getAllowDrawnSignature() != null ? policy.getAllowDrawnSignature() : current.isAllowDrawnSignature();
        boolean allowTyped = policy.getAllowTypedSignature() != null ? policy.getAllowTypedSignature() : current.isAllowTypedSignature();
        boolean allowUploaded = policy.getAllowUploadedSignatureImage() != null
                ? policy.getAllowUploadedSignatureImage() : current.isAllowUploadedSignatureImage();
        if (!allowDrawn && !allowTyped && !allowUploaded)
        {
            errors.add("Allow at least one way to make a signature: drawn, typed or uploaded");
        }
        if (Boolean.TRUE.equals(policy.getDigitalSignatureEnabled()) && !current.isDigitalSignatureEnabled()
                && !subscriptionService.checkLimit(organisation.getId().toString(),
                        bw.co.starsign.subscription.UsageMetric.DIGITAL_SIGNATURES, 1))
        {
            errors.add("The organisation's subscription does not include digital signatures");
        }
        if (!errors.isEmpty())
        {
            throw new OrganisationServiceException(String.join("; ", errors));
        }
    }

    private static void checkNotificationSettings(OrganisationNotificationSettings current,
            OrganisationNotificationSettingsDTO settings)
    {
        List<String> errors = new ArrayList<String>();
        if (StringUtils.isNotBlank(settings.getReplyToEmail()) && !EMAIL.matcher(settings.getReplyToEmail().trim()).matches())
        {
            errors.add("The reply-to address is not a valid email address");
        }
        if (settings.getSenderDisplayName() != null && settings.getSenderDisplayName().trim().length() > 100)
        {
            errors.add("The sender name is limited to 100 characters");
        }
        checkHours(settings.getReminderBeforeDeadlineHours(), "The reminder before the deadline", errors);
        checkHours(settings.getReminderAfterInactivityHours(), "The reminder after inactivity", errors);
        checkHours(settings.getReminderRepeatIntervalHours(), "The interval between reminders", errors);
        int maxReminders = settings.getMaxReminders() != null ? settings.getMaxReminders() : current.getMaxReminders();
        int escalateAfter = settings.getEscalateAfterReminders() != null
                ? settings.getEscalateAfterReminders() : current.getEscalateAfterReminders();
        if (maxReminders < 0 || maxReminders > 20)
        {
            errors.add("Send 0 to 20 reminders");
        }
        boolean escalate = settings.getEscalateToOwner() != null ? settings.getEscalateToOwner() : current.isEscalateToOwner();
        if (escalate && (escalateAfter < 1 || escalateAfter > Math.max(maxReminders, 1)))
        {
            errors.add("Escalate after 1 to " + Math.max(maxReminders, 1) + " reminders");
        }
        boolean email = settings.getEmailEnabled() != null ? settings.getEmailEnabled() : current.isEmailEnabled();
        boolean sms = settings.getSmsEnabled() != null ? settings.getSmsEnabled() : current.isSmsEnabled();
        if (!email && !sms)
        {
            // Signatories outside the organisation are only reached by email or SMS
            errors.add("Keep email or SMS enabled: signatories are invited through them");
        }
        if (!errors.isEmpty())
        {
            throw new OrganisationServiceException(String.join("; ", errors));
        }
    }

    private static void checkHours(Integer hours, String what, List<String> errors)
    {
        if (hours != null && (hours < 1 || hours > 720))
        {
            errors.add(what + " must be 1 to 720 hours");
        }
    }

    private static void applyPhysicalAddress(Organisation organisation, AddressDTO address)
    {
        organisation.setPhysicalAddressLine1(address.getLine1().trim());
        organisation.setPhysicalAddressLine2(StringUtils.trimToNull(address.getLine2()));
        organisation.setPhysicalCity(address.getCity().trim());
        organisation.setPhysicalCountry(address.getCountry().trim());
    }

    private static void applyPostalAddress(Organisation organisation, AddressDTO address)
    {
        organisation.setPostalAddressLine1(address == null ? null : StringUtils.trimToNull(address.getLine1()));
        organisation.setPostalAddressLine2(address == null ? null : StringUtils.trimToNull(address.getLine2()));
        organisation.setPostalCity(address == null ? null : StringUtils.trimToNull(address.getCity()));
        organisation.setPostalCode(address == null ? null : StringUtils.trimToNull(address.getPostalCode()));
        organisation.setPostalCountry(address == null ? null : StringUtils.trimToNull(address.getCountry()));
    }

    private static boolean validTimeZone(String zone)
    {
        try
        {
            ZoneId.of(zone.trim());
            return true;
        }
        catch (DateTimeException e)
        {
            return false;
        }
    }

    private static boolean validWebsite(String website)
    {
        return domainOf(website) != null;
    }

    /** The host of the website, without "www.", or null when it is not a web address. */
    /**
     * The domain recorded on the Keycloak organisation, which KeycloakAdminService requires: the website's
     * host when one is given, otherwise the domain of the organisation's email address.
     */
    private static String organisationDomain(Organisation organisation)
    {
        String domain = domainOf(organisation.getWebsite());
        if (domain != null)
        {
            return domain;
        }
        String email = StringUtils.trimToEmpty(organisation.getEmailAddress());
        int at = email.lastIndexOf('@');
        return at > 0 && at < email.length() - 1 ? email.substring(at + 1).toLowerCase(Locale.ROOT) : null;
    }

    private static String domainOf(String website)
    {
        if (StringUtils.isBlank(website))
        {
            return null;
        }
        String address = website.trim();
        if (!address.contains("://"))
        {
            address = "https://" + address;
        }
        try
        {
            String host = URI.create(address).getHost();
            if (host == null || !host.contains("."))
            {
                return null;
            }
            host = host.toLowerCase(Locale.ROOT);
            return host.startsWith("www.") ? host.substring(4) : host;
        }
        catch (IllegalArgumentException e)
        {
            return null;
        }
    }

    /**
     * A tenant id that reads like the organisation (it shows up in storage keys and the Keycloak organisation
     * alias) with a random suffix, so that it reveals nothing guessable and never collides.
     */
    private String newTenantId(String name)
    {
        String slug = StringUtils.left(StringUtils.strip(StringUtils.stripAccents(name).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-"), "-"), 40);
        slug = StringUtils.defaultIfBlank(StringUtils.strip(slug, "-"), "org");
        String tenantId;
        do
        {
            tenantId = slug + "-" + Integer.toHexString(0x100000 + RANDOM.nextInt(0xF00000));
        }
        while (organisationRepository.existsByTenantId(tenantId));
        return tenantId;
    }

    private boolean keycloakUserExists(String email)
    {
        try
        {
            return keycloakAdminService.findUserByEmail(email) != null;
        }
        catch (KeycloakAdminServiceException e)
        {
            if (e.isNotFound())
            {
                return false;
            }
            throw e;
        }
    }

    /**
     * The local account of the primary administrator, holding the built-in ORGANISATION_ADMIN role. It waits
     * for the administrator to verify the email address and set a password in Keycloak.
     */
    private void addAdministrator(Organisation organisation, KeycloakUserDTO account,
            OrganisationRegistrationDTO registration, String author, LocalDateTime now)
    {
        OrganisationUser user = OrganisationUser.Factory.newInstance();
        user.setKeycloakUserId(account.getUserId());
        user.setUsername(StringUtils.defaultIfBlank(account.getUsername(), account.getEmail()));
        user.setEmailAddress(account.getEmail());
        user.setFirstName(account.getFirstName());
        user.setLastName(account.getLastName());
        user.setPhoneNumber(StringUtils.trimToNull(registration.getAdminPhoneNumber()));
        user.setStatus(UserStatus.PENDING_VERIFICATION);
        user.setEmailVerified(false);
        user.setMfaEnabled(false);
        user.setFailedLoginCount(0);
        user.setLocale(organisation.getLocale());
        user.setOrganisation(organisation);
        user.setCreatedBy(author);
        user.setCreatedAt(now);

        Specification<Role> builtInAdmin = StarsignSpecifications.<Role, Object>findByAttributeNull("organisation")
                .and(StarsignSpecifications.findByAttributeEquals(SystemRole.ORGANISATION_ADMIN, "systemRole"));
        List<Role> roles = roleRepository.findAll(builtInAdmin);
        if (roles.isEmpty())
        {
            logger.warn("The built-in ORGANISATION_ADMIN role is not seeded: the administrator of organisation {}"
                    + " holds it in Keycloak only", organisation.getId());
        }
        else
        {
            UserRole role = UserRole.Factory.newInstance();
            role.setRole(roles.get(0));
            role.setUser(user);
            role.setAssignedAt(now);
            role.setAssignedBy(author);
            role.setCreatedBy(author);
            role.setCreatedAt(now);
            user.getRoles().add(role);
        }
        organisationUserRepository.save(user);
    }

    /** Every new organisation starts on a trial of the cheapest public plan, when the platform offers one. */
    private void startTrial(String organisationId)
    {
        List<SubscriptionPlanDTO> plans = subscriptionService.findPlans(false);
        SubscriptionPlanDTO plan = plans.stream()
                .filter(candidate -> Boolean.TRUE.equals(candidate.getActive()) && Boolean.TRUE.equals(candidate.getPublicPlan()))
                .min(Comparator.comparing((SubscriptionPlanDTO candidate) -> candidate.getTrialDays() == null
                                || candidate.getTrialDays() == 0)
                        .thenComparing(SubscriptionPlanDTO::getMonthlyPrice, Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(null);
        if (plan == null)
        {
            logger.warn("No public subscription plan is active: organisation {} starts without a subscription",
                    organisationId);
            return;
        }
        subscriptionService.startTrial(organisationId, plan.getCode());
    }

    private boolean autoActivate()
    {
        return systemConfigurationRepository.findByConfigKey(AUTO_ACTIVATE_KEY)
                .map(configuration -> Boolean.parseBoolean(configuration.getConfigValue().trim()))
                .orElse(true);
    }

    private void audit(Organisation organisation, AuditEventType type, ActorType actorType, String actorId,
            Map<String, ?> details)
    {
        AuditEventDTO event = new AuditEventDTO();
        event.setChainKey("organisation:" + organisation.getId());
        event.setEventType(type);
        event.setOccurredAt(LocalDateTime.now());
        event.setOrganisationId(organisation.getId().toString());
        event.setActorType(actorType);
        event.setActorId(actorId);
        event.setOutcome(AuditOutcome.SUCCESS);
        event.setEntityType("Organisation");
        event.setEntityId(organisation.getId().toString());
        event.setDetails(json(details));
        auditTrailService.record(event);
    }

    private void publish(Organisation organisation, String eventType, String routingKey, Map<String, ?> payload)
    {
        OutboxEventDTO event = new OutboxEventDTO();
        event.setAggregateType("Organisation");
        event.setAggregateId(organisation.getId().toString());
        event.setEventType(eventType);
        event.setOrganisationId(organisation.getId().toString());
        event.setRoutingKey(routingKey);
        event.setPayload(json(payload));
        event.setIdempotencyKey(eventType + ":" + organisation.getId() + ":" + UUID.randomUUID());
        outboxService.enqueue(event);
    }

    private static String json(Map<String, ?> values)
    {
        try
        {
            return JSON.writeValueAsString(values);
        }
        catch (JsonProcessingException e)
        {
            throw new OrganisationServiceException("Could not write the event details", e);
        }
    }
}
