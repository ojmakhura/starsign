// license-header java merge-point
/**
 * This is only generated once! It will never be overwritten.
 * You can (and have to!) safely modify it by hand.
 * TEMPLATE:    SpringServiceImpl.vsl in andromda-spring cartridge
 * MODEL CLASS: AndroMDAModel::backend::bw.co.starsign::identity::KeycloakAdminService
 * STEREOTYPE:  Service
 */
package bw.co.starsign.identity;

import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.apache.commons.lang3.StringUtils;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.representations.idm.ClientMappingsRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.MappingsRepresentation;
import org.keycloak.representations.idm.OrganizationDomainRepresentation;
import org.keycloak.representations.idm.OrganizationRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.representations.idm.UserSessionRepresentation;
import org.springframework.stereotype.Service;

/**
 * Keycloak admin API as the service account of the {@code starsign-api} client (client-credentials grant).
 * One admin client is shared by all calls: it is thread-safe and refreshes its own token.
 * <p>
 * Tenant membership is kept in the admin-only user attributes {@code tenantId} and {@code organisationId}, which
 * the realm maps to the {@code tenant_id} / {@code organisation_id} token claims.
 *
 * @see bw.co.starsign.identity.KeycloakAdminService
 */
@Service("keycloakAdminService")
public class KeycloakAdminServiceImpl
    extends KeycloakAdminServiceBase
{
    /** User attribute holding the StarSign organisation id (token claim {@code organisation_id}). */
    public static final String ORGANISATION_ATTRIBUTE = "organisationId";

    /** User attribute holding the tenant id (token claim {@code tenant_id}). */
    public static final String TENANT_ATTRIBUTE = "tenantId";

    /** Realm roles every realm user gets from Keycloak itself; they are not application roles. */
    private static final List<String> BUILT_IN_REALM_ROLES = List.of("offline_access", "uma_authorization");

    /** Credential types that are a second factor (Keycloak's OTP and WebAuthn credential types). */
    private static final Set<String> SECOND_FACTORS = Set.of("otp", "webauthn", "webauthn-passwordless");

    /** How many members of a role are read per call. */
    private static final int MEMBER_PAGE_SIZE = 500;

    private final KeycloakAdminProperties properties;

    private volatile Keycloak keycloak;

    public KeycloakAdminServiceImpl(
        KeycloakAdminProperties properties) {

        super(
            );

        this.properties = properties;
    }

    /**
     * Creates the user and, when actions are given, sets them as required actions and sends Keycloak's
     * execute-actions email (e.g. VERIFY_EMAIL, UPDATE_PASSWORD) so the user chooses their own password.
     *
     * @see bw.co.starsign.identity.KeycloakAdminService#createUser(KeycloakUserDTO, List<String>)
     */
    @Override
    protected KeycloakUserDTO handleCreateUser(@Valid KeycloakUserDTO user, List<String> requiredActions)
        throws Exception
    {
        if (StringUtils.isBlank(user.getEmail()))
        {
            throw new KeycloakAdminServiceException("A user needs an email address");
        }

        String email = user.getEmail().trim().toLowerCase();
        UserRepresentation representation = new UserRepresentation();
        representation.setUsername(StringUtils.defaultIfBlank(StringUtils.trimToNull(user.getUsername()), email));
        representation.setEmail(email);
        representation.setFirstName(StringUtils.trimToNull(user.getFirstName()));
        representation.setLastName(StringUtils.trimToNull(user.getLastName()));
        representation.setEnabled(user.getEnabled() == null || user.getEnabled());
        representation.setEmailVerified(Boolean.TRUE.equals(user.getEmailVerified()));
        representation.setAttributes(mergeAttributes(new HashMap<>(), user));

        List<String> actions = requiredActions == null ? List.of() : requiredActions;
        representation.setRequiredActions(new ArrayList<>(actions));

        String userId = call("The user", () -> {
            try (Response response = realm().users().create(representation))
            {
                if (response.getStatus() == Response.Status.CONFLICT.getStatusCode())
                {
                    throw new KeycloakAdminServiceException("A user with the email address " + email + " already exists");
                }
                return CreatedResponseUtil.getCreatedId(response);
            }
        });

        if (!actions.isEmpty())
        {
            sendActionsEmail(userId, actions, properties.getActionLifespanSeconds());
        }

        return toKeycloakUserDTO(userResource(userId).toRepresentation(), userId);
    }

    /**
     * @see bw.co.starsign.identity.KeycloakAdminService#findUserById(String)
     */
    @Override
    protected KeycloakUserDTO handleFindUserById(String userId)
        throws Exception
    {
        UserRepresentation representation = call("The user", () -> userResource(userId).toRepresentation());
        return toKeycloakUserDTO(representation, userId);
    }

    /**
     * The user with exactly this email address, or null when there is none.
     *
     * @see bw.co.starsign.identity.KeycloakAdminService#findUserByEmail(String)
     */
    @Override
    protected KeycloakUserDTO handleFindUserByEmail(String email)
        throws Exception
    {
        if (StringUtils.isBlank(email))
        {
            return null;
        }

        List<UserRepresentation> users = call("The user", () -> realm().users().searchByEmail(email.trim(), true));
        if (users == null || users.isEmpty())
        {
            return null;
        }
        UserRepresentation representation = users.get(0);
        return toKeycloakUserDTO(representation, representation.getId());
    }

    /**
     * Changes the given (non-null) fields; attributes are merged into the existing ones, so admin-only
     * attributes such as {@code tenantId} are kept.
     *
     * @see bw.co.starsign.identity.KeycloakAdminService#updateUser(KeycloakUserDTO)
     */
    @Override
    protected KeycloakUserDTO handleUpdateUser(@Valid KeycloakUserDTO user)
        throws Exception
    {
        if (StringUtils.isBlank(user.getUserId()))
        {
            throw new KeycloakAdminServiceException("Say which Keycloak user to update");
        }

        UserResource resource = userResource(user.getUserId());
        UserRepresentation representation = call("The user", resource::toRepresentation);

        if (user.getEmail() != null)
        {
            representation.setEmail(user.getEmail().trim().toLowerCase());
        }
        if (user.getFirstName() != null)
        {
            representation.setFirstName(StringUtils.trimToNull(user.getFirstName()));
        }
        if (user.getLastName() != null)
        {
            representation.setLastName(StringUtils.trimToNull(user.getLastName()));
        }
        if (user.getEnabled() != null)
        {
            representation.setEnabled(user.getEnabled());
        }
        if (user.getEmailVerified() != null)
        {
            representation.setEmailVerified(user.getEmailVerified());
        }
        if (user.getRequiredActions() != null)
        {
            representation.setRequiredActions(new ArrayList<>(user.getRequiredActions()));
        }

        Map<String, List<String>> attributes = representation.getAttributes() == null
            ? new HashMap<>() : new HashMap<>(representation.getAttributes());
        representation.setAttributes(mergeAttributes(attributes, user));

        call("The user", () -> {
            resource.update(representation);
            return null;
        });
        return toKeycloakUserDTO(resource.toRepresentation(), user.getUserId());
    }

    /**
     * @see bw.co.starsign.identity.KeycloakAdminService#setEnabled(String, boolean)
     */
    @Override
    protected void handleSetEnabled(String userId, boolean enabled)
        throws Exception
    {
        UserResource resource = userResource(userId);
        UserRepresentation representation = call("The user", resource::toRepresentation);
        representation.setEnabled(enabled);
        call("The user", () -> {
            resource.update(representation);
            return null;
        });
    }

    /**
     * @see bw.co.starsign.identity.KeycloakAdminService#sendRequiredActions(String, List<String>, int)
     */
    @Override
    protected void handleSendRequiredActions(String userId, List<String> actions, int lifespanSeconds)
        throws Exception
    {
        if (actions == null || actions.isEmpty())
        {
            throw new KeycloakAdminServiceException("Say which actions the user must perform");
        }
        sendActionsEmail(userId, actions, lifespanSeconds > 0 ? lifespanSeconds : properties.getActionLifespanSeconds());
    }

    /**
     * @see bw.co.starsign.identity.KeycloakAdminService#assignRealmRoles(String, List<String>)
     */
    @Override
    protected void handleAssignRealmRoles(String userId, List<String> roles)
        throws Exception
    {
        if (roles == null || roles.isEmpty())
        {
            return;
        }
        List<RoleRepresentation> representations = realmRoles(roles);
        call("The user", () -> {
            userResource(userId).roles().realmLevel().add(representations);
            return null;
        });
    }

    /**
     * @see bw.co.starsign.identity.KeycloakAdminService#removeRealmRoles(String, List<String>)
     */
    @Override
    protected void handleRemoveRealmRoles(String userId, List<String> roles)
        throws Exception
    {
        if (roles == null || roles.isEmpty())
        {
            return;
        }
        List<RoleRepresentation> representations = realmRoles(roles);
        call("The user", () -> {
            userResource(userId).roles().realmLevel().remove(representations);
            return null;
        });
    }

    /**
     * The realm's roles without Keycloak's own (default-roles-*, offline_access, uma_authorization).
     *
     * @see bw.co.starsign.identity.KeycloakAdminService#getRealmRoles()
     */
    @Override
    protected List<String> handleGetRealmRoles()
        throws Exception
    {
        return realm().roles().list().stream()
            .map(RoleRepresentation::getName)
            .filter(name -> !isKeycloakRole(name))
            .sorted()
            .toList();
    }

    /**
     * @see bw.co.starsign.identity.KeycloakAdminService#findEffectiveRealmRoles(String)
     */
    @Override
    protected List<String> handleFindEffectiveRealmRoles(String userId)
        throws Exception
    {
        List<RoleRepresentation> roles = call("The user",
            () -> userResource(userId).roles().realmLevel().listEffective());
        return roles.stream().map(RoleRepresentation::getName).sorted().toList();
    }

    /**
     * Idempotent: an existing role is left as it is.
     *
     * @see bw.co.starsign.identity.KeycloakAdminService#createRealmRole(String, String)
     */
    @Override
    protected void handleCreateRealmRole(String roleName, String description)
        throws Exception
    {
        if (StringUtils.isBlank(roleName))
        {
            throw new KeycloakAdminServiceException("A role needs a name");
        }

        String name = roleName.trim();
        boolean exists = realm().roles().list(name, true).stream().anyMatch(role -> name.equals(role.getName()));
        if (exists)
        {
            return;
        }

        RoleRepresentation role = new RoleRepresentation();
        role.setName(name);
        role.setDescription(description);
        call("The role", () -> {
            realm().roles().create(role);
            return null;
        });
    }

    /**
     * @see bw.co.starsign.identity.KeycloakAdminService#getUserSessions(String)
     */
    @Override
    protected List<KeycloakSessionDTO> handleGetUserSessions(String userId)
        throws Exception
    {
        List<UserSessionRepresentation> sessions = call("The user", () -> userResource(userId).getUserSessions());
        List<KeycloakSessionDTO> result = new ArrayList<>();
        for (UserSessionRepresentation session : sessions)
        {
            KeycloakSessionDTO dto = new KeycloakSessionDTO();
            dto.setSessionId(session.getId());
            dto.setIpAddress(session.getIpAddress());
            dto.setStarted(toLocalDateTime(session.getStart()));
            dto.setLastAccess(toLocalDateTime(session.getLastAccess()));
            dto.setClients(session.getClients() == null ? new ArrayList<>() : new ArrayList<>(session.getClients().values()));
            result.add(dto);
        }
        return result;
    }

    /**
     * Ends every session of the user (their refresh tokens stop working).
     *
     * @see bw.co.starsign.identity.KeycloakAdminService#logoutUser(String)
     */
    @Override
    protected void handleLogoutUser(String userId)
        throws Exception
    {
        call("The user", () -> {
            userResource(userId).logout();
            return null;
        });
    }

    /**
     * @see bw.co.starsign.identity.KeycloakAdminService#createOrganisation(String, String, String)
     */
    @Override
    protected String handleCreateOrganisation(String name, String alias, String domain)
        throws Exception
    {
        if (StringUtils.isBlank(name))
        {
            throw new KeycloakAdminServiceException("An organisation needs a name");
        }

        OrganizationRepresentation organisation = new OrganizationRepresentation();
        organisation.setName(name.trim());
        organisation.setAlias(StringUtils.trimToNull(alias));
        organisation.setEnabled(true);
        if (StringUtils.isNotBlank(domain))
        {
            organisation.addDomain(new OrganizationDomainRepresentation(domain.trim().toLowerCase()));
        }

        return call("The organisation", () -> {
            try (Response response = realm().organizations().create(organisation))
            {
                if (response.getStatus() == Response.Status.CONFLICT.getStatusCode())
                {
                    throw new KeycloakAdminServiceException(
                        "Keycloak already has an organisation with the name, alias or domain of " + name);
                }
                return CreatedResponseUtil.getCreatedId(response);
            }
        });
    }

    /**
     * Idempotent: a user who already is a member is left as they are.
     *
     * @see bw.co.starsign.identity.KeycloakAdminService#addOrganisationMember(String, String)
     */
    @Override
    protected void handleAddOrganisationMember(String keycloakOrganisationId, String userId)
        throws Exception
    {
        call("The organisation", () -> {
            try (Response response = realm().organizations().get(keycloakOrganisationId).members().addMember(userId))
            {
                int status = response.getStatus();
                if (status >= 400 && status != Response.Status.CONFLICT.getStatusCode())
                {
                    throw new KeycloakAdminServiceException("Keycloak refused to add the user to the organisation (HTTP "
                        + status + ")");
                }
                return null;
            }
        });
    }

    /**
     * Idempotent: removing a user who is no member does nothing.
     *
     * @see bw.co.starsign.identity.KeycloakAdminService#removeOrganisationMember(String, String)
     */
    @Override
    protected void handleRemoveOrganisationMember(String keycloakOrganisationId, String userId)
        throws Exception
    {
        try (Response response = realm().organizations().get(keycloakOrganisationId).members().member(userId).delete())
        {
            int status = response.getStatus();
            if (status >= 400 && status != Response.Status.NOT_FOUND.getStatusCode())
            {
                throw new KeycloakAdminServiceException("Keycloak refused to remove the user from the organisation (HTTP "
                    + status + ")");
            }
        }
        catch (NotFoundException e)
        {
            logger.debug("User {} is not a member of Keycloak organisation {}", userId, keycloakOrganisationId);
        }
    }

    /**
     * An authenticator app (OTP) or a security key (WebAuthn) counts as a second factor.
     *
     * @see bw.co.starsign.identity.KeycloakAdminService#isMfaConfigured(String)
     */
    @Override
    protected boolean handleIsMfaConfigured(String userId)
        throws Exception
    {
        List<CredentialRepresentation> credentials = call("The user", () -> userResource(userId).credentials());
        return credentials != null && credentials.stream()
            .map(CredentialRepresentation::getType)
            .anyMatch(SECOND_FACTORS::contains);
    }

    /**
     * Keycloak reports a user its brute-force detection has locked out as {@code disabled} (the account's own
     * enabled flag is untouched).
     *
     * @see bw.co.starsign.identity.KeycloakAdminService#isLockedOut(String)
     */
    @Override
    protected boolean handleIsLockedOut(String userId)
        throws Exception
    {
        Map<String, Object> status = call("The user",
            () -> realm().attackDetection().bruteForceUserStatus(userId.trim()));
        return status != null && Boolean.TRUE.equals(status.get("disabled"));
    }

    /**
     * @see bw.co.starsign.identity.KeycloakAdminService#clearLoginFailures(String)
     */
    @Override
    protected void handleClearLoginFailures(String userId)
        throws Exception
    {
        call("The user", () -> {
            realm().attackDetection().clearBruteForceForUser(userId.trim());
            return null;
        });
    }

    /**
     * Read in pages, so roles with many members are read in full.
     *
     * @see bw.co.starsign.identity.KeycloakAdminService#findRealmRoleMemberIds(String)
     */
    @Override
    protected List<String> handleFindRealmRoleMemberIds(String roleName)
        throws Exception
    {
        List<String> ids = new ArrayList<>();
        try
        {
            int first = 0;
            List<UserRepresentation> page;
            do
            {
                int offset = first;
                page = realm().roles().get(roleName.trim()).getUserMembers(offset, MEMBER_PAGE_SIZE);
                page.forEach(user -> ids.add(user.getId()));
                first += MEMBER_PAGE_SIZE;
            }
            while (page.size() == MEMBER_PAGE_SIZE);
        }
        catch (NotFoundException e)
        {
            return List.of();
        }
        return ids;
    }

    @PreDestroy
    void close()
    {
        Keycloak client = keycloak;
        if (client != null)
        {
            client.close();
        }
    }

    private Keycloak keycloak()
    {
        Keycloak client = keycloak;
        if (client == null)
        {
            synchronized (this)
            {
                if (keycloak == null)
                {
                    if (StringUtils.isBlank(properties.getServerUrl()))
                    {
                        throw new KeycloakAdminServiceException("The Keycloak admin connection is not configured (app.keycloak-admin.server-url)");
                    }
                    keycloak = KeycloakBuilder.builder()
                        .serverUrl(StringUtils.removeEnd(properties.getServerUrl().trim(), "/"))
                        .realm(properties.getRealm())
                        .clientId(properties.getClientId())
                        .clientSecret(properties.getClientSecret())
                        .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
                        .build();
                }
                client = keycloak;
            }
        }
        return client;
    }

    private RealmResource realm()
    {
        return keycloak().realm(properties.getRealm());
    }

    private UserResource userResource(String userId)
    {
        if (StringUtils.isBlank(userId))
        {
            throw new KeycloakAdminServiceException("Say which Keycloak user is meant");
        }
        return realm().users().get(userId.trim());
    }

    private void sendActionsEmail(String userId, List<String> actions, int lifespanSeconds)
    {
        call("The user", () -> {
            userResource(userId).executeActionsEmail(properties.getPortalClientId(), null, lifespanSeconds, actions);
            return null;
        });
    }

    private List<RoleRepresentation> realmRoles(Collection<String> names)
    {
        List<RoleRepresentation> roles = new ArrayList<>();
        for (String name : new LinkedHashSet<>(names))
        {
            try
            {
                roles.add(realm().roles().get(name).toRepresentation());
            }
            catch (NotFoundException e)
            {
                throw new KeycloakAdminServiceException("Keycloak has no role " + name);
            }
        }
        return roles;
    }

    /**
     * Runs a Keycloak call, turning "not found" into a 404 and "conflict" into a rule violation; other
     * failures (Keycloak unreachable, refused) propagate as errors.
     */
    private <T> T call(String what, Supplier<T> action)
    {
        try
        {
            return action.get();
        }
        catch (NotFoundException e)
        {
            throw KeycloakAdminServiceException.notFound(what + " does not exist in Keycloak");
        }
        catch (ClientErrorException e)
        {
            if (e.getResponse() != null && e.getResponse().getStatus() == Response.Status.CONFLICT.getStatusCode())
            {
                throw new KeycloakAdminServiceException(what + " conflicts with an existing Keycloak record");
            }
            throw e;
        }
    }

    private static boolean isKeycloakRole(String name)
    {
        return name == null || name.startsWith("default-roles-") || BUILT_IN_REALM_ROLES.contains(name);
    }

    /** The DTO's organisation id and attributes (single values or lists) on top of {@code attributes}. */
    private static Map<String, List<String>> mergeAttributes(Map<String, List<String>> attributes, KeycloakUserDTO user)
    {
        if (user.getAttributes() != null)
        {
            for (Object entry : user.getAttributes().entrySet())
            {
                Map.Entry<?, ?> attribute = (Map.Entry<?, ?>) entry;
                String key = String.valueOf(attribute.getKey());
                Object value = attribute.getValue();
                if (value == null)
                {
                    attributes.remove(key);
                }
                else if (value instanceof Collection<?> values)
                {
                    attributes.put(key, values.stream().filter(Objects::nonNull).map(String::valueOf).toList());
                }
                else
                {
                    attributes.put(key, List.of(String.valueOf(value)));
                }
            }
        }
        if (StringUtils.isNotBlank(user.getOrganisationId()))
        {
            attributes.put(ORGANISATION_ATTRIBUTE, List.of(user.getOrganisationId().trim()));
        }
        return attributes;
    }

    private KeycloakUserDTO toKeycloakUserDTO(UserRepresentation representation, String userId)
    {
        KeycloakUserDTO dto = new KeycloakUserDTO();
        dto.setUserId(StringUtils.defaultIfBlank(representation.getId(), userId));
        dto.setUsername(representation.getUsername());
        dto.setEmail(representation.getEmail());
        dto.setFirstName(representation.getFirstName());
        dto.setLastName(representation.getLastName());
        dto.setEnabled(representation.isEnabled());
        dto.setEmailVerified(representation.isEmailVerified());
        dto.setCreatedTimestamp(representation.getCreatedTimestamp());
        dto.setRequiredActions(representation.getRequiredActions() == null
            ? new ArrayList<>() : new ArrayList<>(representation.getRequiredActions()));

        Map<String, List<String>> attributes = representation.getAttributes() == null
            ? new HashMap<>() : new HashMap<>(representation.getAttributes());
        dto.setAttributes(attributes);
        List<String> organisation = attributes.get(ORGANISATION_ATTRIBUTE);
        dto.setOrganisationId(organisation == null || organisation.isEmpty() ? null : organisation.get(0));

        // Directly assigned roles; composites are resolved by findEffectiveRealmRoles
        List<String> realmRoles = new ArrayList<>();
        List<String> clientRoles = new ArrayList<>();
        MappingsRepresentation mappings = userResource(dto.getUserId()).roles().getAll();
        if (mappings != null)
        {
            if (mappings.getRealmMappings() != null)
            {
                mappings.getRealmMappings().stream()
                    .map(RoleRepresentation::getName)
                    .filter(name -> !isKeycloakRole(name))
                    .forEach(realmRoles::add);
            }
            if (mappings.getClientMappings() != null)
            {
                for (ClientMappingsRepresentation client : mappings.getClientMappings().values())
                {
                    if (client.getMappings() != null)
                    {
                        client.getMappings().forEach(role -> clientRoles.add(client.getClient() + ":" + role.getName()));
                    }
                }
            }
        }
        dto.setRealmRoles(realmRoles);
        dto.setClientRoles(clientRoles);
        return dto;
    }

    private static LocalDateTime toLocalDateTime(long epochMillis)
    {
        return epochMillis <= 0 ? null : LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
    }
}
