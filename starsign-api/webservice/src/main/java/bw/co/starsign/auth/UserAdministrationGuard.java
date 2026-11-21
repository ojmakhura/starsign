package bw.co.starsign.auth;

import java.util.Collection;

import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import bw.co.starsign.context.CallerContext;
import bw.co.starsign.identity.KeycloakAdminService;
import bw.co.starsign.identity.KeycloakUserDTO;

/**
 * Rules for user administration on top of the endpoints' Keycloak permissions
 * (rules generated into {@link RoleAssignmentRules} by keycloak/generate_authz.py):
 * <ul>
 * <li>only application roles may be assigned;</li>
 * <li>privileged roles are assigned, and their holders managed, only by holders of the privileged grantor role;</li>
 * <li>organisation administrators manage users of their own organisation only and assign only organisation
 * roles; platform administrators (no tenant) may manage the accounts of any organisation for support.</li>
 * </ul>
 * Violations throw {@link AuthorizationDeniedException} (403). Call it from the UserController / RoleController /
 * invitation controllers before delegating to the services.
 */
@Component
public class UserAdministrationGuard {

    private final KeycloakAdminService keycloak;
    private final CallerContext caller;

    public UserAdministrationGuard(@Lazy KeycloakAdminService keycloak, CallerContext caller) {
        this.keycloak = keycloak;
        this.caller = caller;
    }

    /** The caller may administer the existing Keycloak user {@code userId}. */
    public void checkCanManageUser(String userId) {

        if (StringUtils.isBlank(userId)) {
            throw new AuthorizationDeniedException("Access denied: no user given");
        }

        Collection<String> roles = keycloak.findEffectiveRealmRoles(userId);
        if (!callerIsPrivilegedGrantor() && roles.stream().anyMatch(RoleAssignmentRules.PRIVILEGED_ROLES::contains)) {
            throw new AuthorizationDeniedException("Access denied: only " + RoleAssignmentRules.PRIVILEGED_GRANTOR
                    + " may manage this user");
        }

        if (caller.tenantId().isEmpty()) {
            return; // platform administrator: support actions on any organisation's users
        }

        KeycloakUserDTO target = keycloak.findUserById(userId);
        checkSameOrganisation(target == null ? null : target.getOrganisationId());
    }

    /** The caller may create (invite) a user in {@code organisationId}. */
    public void checkCanCreateUserIn(String organisationId) {
        if (caller.tenantId().isPresent()) {
            checkSameOrganisation(organisationId);
        }
    }

    /** The caller may assign (or revoke) {@code roles}. */
    public void checkCanAssign(Collection<String> roles) {

        boolean platformAdministrator = caller.tenantId().isEmpty();

        for (String role : roles) {
            if (!RoleAssignmentRules.ROLES.contains(role)) {
                throw new AuthorizationDeniedException("Access denied: unknown role " + role);
            }
            if (RoleAssignmentRules.PRIVILEGED_ROLES.contains(role) && !callerIsPrivilegedGrantor()) {
                throw new AuthorizationDeniedException("Access denied: only " + RoleAssignmentRules.PRIVILEGED_GRANTOR
                        + " may assign " + role);
            }
            if (!platformAdministrator && !RoleAssignmentRules.ORGANISATION_ROLES.contains(role)) {
                throw new AuthorizationDeniedException("Access denied: organisation administrators may only assign "
                        + RoleAssignmentRules.ORGANISATION_ROLES);
            }
        }
    }

    private void checkSameOrganisation(String organisationId) {
        String callerOrganisation = caller.organisationId().orElse(null);
        if (StringUtils.isBlank(callerOrganisation) || !callerOrganisation.equals(organisationId)) {
            throw new AuthorizationDeniedException("Access denied: user is not in the caller's organisation");
        }
    }

    private static boolean callerIsPrivilegedGrantor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String authority = "ROLE_" + RoleAssignmentRules.PRIVILEGED_GRANTOR; // realm roles, see KeycloakRoleConverter
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> authority.equals(a.getAuthority()));
    }
}
