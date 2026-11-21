package bw.co.starsign.identity;

import bw.co.starsign.StarsignSpecifications;
import bw.co.starsign.context.CallerContext;
import bw.co.starsign.organisation.Organisation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * Which roles a member of an organisation may hold, shared by user administration and invitations.
 * The web layer applies the full assignment rules (UserAdministrationGuard); this repeats the ones that must hold
 * whoever calls the services.
 */
@Component
public class UserRoles
{
    /** Base role every organisation user holds in Keycloak (keycloak/ROLE_MATRIX.md). */
    public static final String BASE_ROLE = "STARSIGN_USER";

    /** Roles only platform administrators may grant (RoleAssignmentRules.PRIVILEGED_ROLES). */
    public static final Set<String> PRIVILEGED_ROLES = Set.of("PLATFORM_ADMIN", "DEVELOPER");

    /** Platform roles carry no tenant: an organisation member never holds them on their own. */
    public static final Set<String> PLATFORM_ROLES = Set.of("PLATFORM_ADMIN");

    /** Granted to platform administrators (PLATFORM_ADMIN, DEVELOPER) only. */
    private static final String PLATFORM_PERMISSION = "organisations:administer";

    private final RoleRepository roleRepository;
    private final CallerContext caller;

    public UserRoles(RoleRepository roleRepository, CallerContext caller)
    {
        this.roleRepository = roleRepository;
        this.caller = caller;
    }

    /**
     * The roles with these codes that members of {@code organisation} may be given: built-in roles and the
     * organisation's own. Rule violations are reported through {@code error}.
     */
    public List<Role> resolve(Collection<String> codes, Organisation organisation, Function<String, RuntimeException> error)
    {
        List<Role> roles = new ArrayList<>();
        if (codes == null)
        {
            return roles;
        }

        Set<String> wanted = new LinkedHashSet<>();
        codes.stream().filter(StringUtils::isNotBlank).map(String::trim).forEach(wanted::add);
        if (wanted.isEmpty())
        {
            return roles;
        }

        List<Role> candidates = roleRepository.findAll(StarsignSpecifications.<Role, String>findByAttributeIn(wanted, "code"));
        List<String> problems = new ArrayList<>();
        for (String code : wanted)
        {
            Role role = candidates.stream()
                .filter(candidate -> code.equals(candidate.getCode()))
                .filter(candidate -> candidate.getOrganisation() == null
                    || candidate.getOrganisation().getId().equals(organisation.getId()))
                .findFirst()
                .orElse(null);

            if (role == null)
            {
                problems.add("There is no role " + code + " in this organisation");
            }
            else if (PLATFORM_ROLES.contains(role.getKeycloakRoleName()))
            {
                problems.add("The role " + role.getName() + " is for platform administrators and cannot be given to organisation members");
            }
            else if (PRIVILEGED_ROLES.contains(role.getKeycloakRoleName()) && !caller.has(PLATFORM_PERMISSION))
            {
                problems.add("Only platform administrators may give the role " + role.getName());
            }
            else
            {
                roles.add(role);
            }
        }

        if (!problems.isEmpty())
        {
            throw error.apply(String.join("; ", problems));
        }
        return roles;
    }

    /** The Keycloak realm roles that go with {@code roles}, including the base role. */
    public static List<String> keycloakRoles(Collection<Role> roles)
    {
        Set<String> names = new LinkedHashSet<>();
        names.add(BASE_ROLE);
        roles.stream().map(Role::getKeycloakRoleName).filter(StringUtils::isNotBlank).forEach(names::add);
        return new ArrayList<>(names);
    }
}
