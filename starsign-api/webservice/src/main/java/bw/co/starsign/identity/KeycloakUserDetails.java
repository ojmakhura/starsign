package bw.co.starsign.identity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/**
 * Fills in the user details Keycloak owns, which the services leave out of the user DTOs because the database no
 * longer keeps them: the StarSign roles (Keycloak realm roles), phone number, job title and locale (user attributes),
 * email verification, whether a second factor is set up, and a brute-force lockout (reported as
 * {@link UserStatus#LOCKED}).
 *
 * <p>Keycloak failures are logged and leave the details empty: users still list and load when Keycloak is slow or
 * down. A list reads the users' accounts in parallel, one Keycloak account per user.
 */
@Component
public class KeycloakUserDetails
{
    private static final Logger logger = LoggerFactory.getLogger(KeycloakUserDetails.class);

    /** Matches no organisation, so {@link RoleService#findAll(String)} returns the built-in roles only. */
    private static final String NO_ORGANISATION = new UUID(0L, 0L).toString();

    private final KeycloakAdminService keycloakAdminService;
    private final RoleService roleService;

    /** Virtual threads: the work is waiting on Keycloak; the admin client's connection pool bounds the calls. */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public KeycloakUserDetails(KeycloakAdminService keycloakAdminService, RoleService roleService)
    {
        this.keycloakAdminService = keycloakAdminService;
        this.roleService = roleService;
    }

    @PreDestroy
    void close()
    {
        executor.shutdown();
    }

    /** The current user: as {@link #fill(OrganisationUserDTO)}, also for platform administrators without profile. */
    public CurrentUserDTO fill(CurrentUserDTO current)
    {
        if (current != null)
        {
            fill(current.getUser());
        }
        return current;
    }

    /**
     * Adds everything Keycloak owns about one user. Users of an organisation get the codes of the StarSign roles
     * they hold; accounts without an organisation (platform administrators) their realm roles.
     */
    public OrganisationUserDTO fill(OrganisationUserDTO user)
    {
        if (user == null || StringUtils.isBlank(user.getKeycloakUserId()))
        {
            return user;
        }
        String userId = user.getKeycloakUserId();

        KeycloakUserDTO account = account(userId);
        if (account != null)
        {
            // Accounts without a profile have only their Keycloak id so far
            user.setUsername(StringUtils.defaultIfBlank(user.getUsername(), account.getUsername()));
            user.setEmailAddress(StringUtils.defaultIfBlank(user.getEmailAddress(), account.getEmail()));
            user.setFirstName(StringUtils.defaultIfBlank(user.getFirstName(), account.getFirstName()));
            user.setLastName(StringUtils.defaultIfBlank(user.getLastName(), account.getLastName()));
            user.setEmailVerified(account.getEmailVerified());
            user.setPhoneNumber(KeycloakAttributes.first(account, KeycloakAttributes.PHONE_NUMBER));
            user.setJobTitle(KeycloakAttributes.first(account, KeycloakAttributes.JOB_TITLE));
            user.setLocale(KeycloakAttributes.first(account, KeycloakAttributes.LOCALE));
            user.setRoles(StringUtils.isBlank(user.getOrganisationId())
                ? sorted(account.getRealmRoles())
                : roleCodes(account.getRealmRoles(), catalogue(user.getOrganisationId())));
        }
        else if (user.getRoles() == null)
        {
            user.setRoles(new ArrayList<>());
        }

        user.setMfaEnabled(quietly(() -> keycloakAdminService.isMfaConfigured(userId), userId, "credentials"));
        if (user.getStatus() == UserStatus.ACTIVE
            && Boolean.TRUE.equals(quietly(() -> keycloakAdminService.isLockedOut(userId), userId, "lockout")))
        {
            user.setStatus(UserStatus.LOCKED);
        }
        return user;
    }

    /**
     * Adds the job title and StarSign role codes to list items.
     *
     * @param organisationId the organisation the users belong to; null for a list across organisations, whose
     *     items then get the built-in roles only (an organisation's own roles are named by their code in Keycloak)
     */
    public <C extends Collection<OrganisationUserListDTO>> C fill(C users, String organisationId)
    {
        if (users == null || users.isEmpty())
        {
            return users;
        }
        Map<String, String> catalogue = catalogue(organisationId);

        Set<String> ids = new LinkedHashSet<>();
        users.stream().map(OrganisationUserListDTO::getKeycloakUserId).filter(StringUtils::isNotBlank).forEach(ids::add);
        Map<String, CompletableFuture<KeycloakUserDTO>> pending = new HashMap<>();
        ids.forEach(id -> pending.put(id, CompletableFuture.supplyAsync(() -> account(id), executor)));

        for (OrganisationUserListDTO user : users)
        {
            KeycloakUserDTO account = user.getKeycloakUserId() == null ? null
                : pending.get(user.getKeycloakUserId()).join();
            if (account == null)
            {
                user.setRoles(new ArrayList<>());
                continue;
            }
            user.setJobTitle(KeycloakAttributes.first(account, KeycloakAttributes.JOB_TITLE));
            user.setRoles(roleCodes(account.getRealmRoles(), catalogue));
        }
        return users;
    }

    /** The Keycloak account, or null when it cannot be read. */
    private KeycloakUserDTO account(String userId)
    {
        try
        {
            return keycloakAdminService.findUserById(userId);
        }
        catch (RuntimeException e)
        {
            logger.warn("Could not read Keycloak account {}: {}", userId, e.getMessage());
            return null;
        }
    }

    /** The realm role names of the organisation's StarSign roles (built-in and its own), mapped to their codes. */
    private Map<String, String> catalogue(String organisationId)
    {
        Map<String, String> codes = new HashMap<>();
        try
        {
            String scope = StringUtils.isBlank(organisationId) ? NO_ORGANISATION : organisationId.trim();
            for (RoleDTO role : roleService.findAll(scope))
            {
                if (StringUtils.isNotBlank(role.getKeycloakRoleName()) && StringUtils.isNotBlank(role.getCode()))
                {
                    codes.putIfAbsent(role.getKeycloakRoleName(), role.getCode());
                }
            }
        }
        catch (RuntimeException e)
        {
            logger.warn("Could not read the roles of organisation {}: {}", organisationId, e.getMessage());
        }
        return codes;
    }

    private static List<String> roleCodes(Collection<String> realmRoles, Map<String, String> catalogue)
    {
        if (realmRoles == null)
        {
            return new ArrayList<>();
        }
        return new ArrayList<>(realmRoles.stream()
            .filter(name -> !UserRoles.PLATFORM_ROLES.contains(name))
            .map(catalogue::get)
            .filter(Objects::nonNull)
            .distinct()
            .sorted()
            .toList());
    }

    private static List<String> sorted(Collection<String> values)
    {
        return values == null ? new ArrayList<>() : new ArrayList<>(values.stream().sorted().toList());
    }

    private static Boolean quietly(java.util.function.Supplier<Boolean> call, String userId, String what)
    {
        try
        {
            return call.get();
        }
        catch (RuntimeException e)
        {
            logger.warn("Could not read the {} of Keycloak account {}: {}", what, userId, e.getMessage());
            return null;
        }
    }
}
