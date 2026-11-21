package bw.co.starsign.config;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Keycloak realm and client roles as {@code ROLE_*} authorities, and the token's OAuth scopes as
 * {@code SCOPE_*}. Endpoint checks use the Keycloak permissions ({@code SCOPE_<resource>:<action>},
 * see {@link bw.co.starsign.security.PermissionService}); roles are only used for rules about roles
 * themselves (e.g. who may assign PLATFORM_ADMIN).
 */
public class KeycloakRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {

        Set<GrantedAuthority> authorities = new HashSet<>();

        // Realm roles
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess != null && realmAccess.get("roles") instanceof Collection<?> roles) {
            for (Object role : roles) {
                authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
            }
        }

        // Client roles (portal access roles such as PORTAL_USER)
        Map<String, Object> resourceAccess = jwt.getClaim("resource_access");
        if (resourceAccess != null) {
            resourceAccess.forEach((clientId, clientObj) -> {
                if (clientObj instanceof Map<?, ?> clientMap && clientMap.get("roles") instanceof Collection<?> roles) {
                    for (Object role : roles) {
                        authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
                    }
                }
            });
        }

        // OAuth scopes (openid, profile, ...); they have no ':' so never clash with permissions
        String scope = jwt.getClaimAsString("scope");
        if (scope != null && !scope.isBlank()) {
            Arrays.stream(scope.split(" "))
                    .forEach(s -> authorities.add(new SimpleGrantedAuthority("SCOPE_" + s)));
        }

        return authorities;
    }
}
