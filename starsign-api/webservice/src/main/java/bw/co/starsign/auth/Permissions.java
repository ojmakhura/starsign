package bw.co.starsign.auth;

import java.util.Collections;
import java.util.SortedSet;
import java.util.TreeSet;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The caller's Keycloak permissions, for checks finer than an endpoint's @PreAuthorize
 * (e.g. whether a listing must be limited to the caller's own documents).
 */
public final class Permissions {

    /** Prefix of the permission authorities (see SpringSecurityConfig#jwtAuthenticationConverter). */
    public static final String AUTHORITY_PREFIX = "SCOPE_";

    private Permissions() {
    }

    /** Whether the caller holds the Keycloak permission scope, e.g. "documents:view". */
    public static boolean has(String permission) {

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }

        String authority = AUTHORITY_PREFIX + permission;
        return auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).anyMatch(authority::equals);
    }

    /**
     * Every permission scope the caller holds (e.g. "documents:view"), sorted. OAuth scopes in the
     * token (openid, profile, ...) share the authority prefix but have no ':' and are left out.
     */
    public static SortedSet<String> current() {

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return Collections.emptySortedSet();
        }

        String prefix = AUTHORITY_PREFIX;
        SortedSet<String> permissions = new TreeSet<>();
        auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith(prefix) && a.indexOf(':') > prefix.length())
                .forEach(a -> permissions.add(a.substring(prefix.length())));
        return permissions;
    }
}
