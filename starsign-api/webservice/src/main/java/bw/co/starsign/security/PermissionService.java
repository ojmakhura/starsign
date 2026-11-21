package bw.co.starsign.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.keycloak.authorization.client.AuthorizationDeniedException;
import org.keycloak.representations.idm.authorization.AuthorizationRequest;
import org.keycloak.representations.idm.authorization.Permission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

/**
 * Asks Keycloak Authorization Services which scopes the caller has. Whether the caller owns a particular
 * document or workflow is decided in the API, from the database ({@link OwnershipAspect}).
 * <ul>
 * <li>{@link #grantedScopes(Jwt)}: the scopes ({@code <resource>:<action>}) granted on the API's resources,
 * which become {@code SCOPE_*} authorities (Pattern B). Taken from the token when it already is an RPT,
 * otherwise requested with the UMA grant and cached ({@link AuthzCache}: Caffeine, then Redis) for at most
 * the token's remaining lifetime.</li>
 * </ul>
 * Failures to reach Keycloak deny access (nothing granted) and are not cached.
 */
@Service
public class PermissionService {

    private static final Logger logger = LoggerFactory.getLogger(PermissionService.class);

    private static final String PERMISSIONS_KEY = "starsign:authz:perm:";

    private final AuthzClientProvider authzClient;
    private final KeycloakAuthzProperties properties;
    private final AuthzCache cache;

    public PermissionService(AuthzClientProvider authzClient, KeycloakAuthzProperties properties,
            AuthzCache cache) {
        this.authzClient = authzClient;
        this.properties = properties;
        this.cache = cache;
    }

    public Set<String> grantedScopes(Jwt jwt) {

        Map<String, Object> rpt = jwt.getClaimAsMap("authorization");
        if (rpt != null) {
            return scopesOf(rpt.get("permissions"));
        }

        String key = PERMISSIONS_KEY + sha256(jwt.getTokenValue());
        String cached = cache.get(key);
        if (cached != null) {
            return cached.isEmpty() ? Set.of() : Set.of(cached.split(" "));
        }

        AuthorizationRequest request = new AuthorizationRequest();
        request.setAudience(properties.clientId());
        AuthzResources.RESOURCES.forEach(request::addPermission);

        Set<String> scopes;
        try {
            List<Permission> permissions = authzClient.get().authorization(jwt.getTokenValue()).getPermissions(request);
            scopes = new LinkedHashSet<>();
            for (Permission permission : permissions) {
                if (permission.getScopes() != null) {
                    scopes.addAll(permission.getScopes());
                }
            }
        } catch (AuthorizationDeniedException e) {
            scopes = Set.of();
        } catch (RuntimeException e) {
            logger.error("Failed to obtain Keycloak permissions for {}", jwt.getSubject(), e);
            return Set.of();
        }

        cache.put(key, String.join(" ", scopes), AuthzCache.until(jwt.getExpiresAt(), properties.permissionCacheTtl()));
        return scopes;
    }

    private static Set<String> scopesOf(Object permissions) {
        Set<String> scopes = new LinkedHashSet<>();
        if (permissions instanceof Collection<?> list) {
            for (Object permission : list) {
                if (permission instanceof Map<?, ?> map && map.get("scopes") instanceof Collection<?> granted) {
                    granted.forEach(s -> scopes.add(s.toString()));
                }
            }
        }
        return scopes;
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
