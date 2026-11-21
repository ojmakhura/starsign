package bw.co.starsign.auth;

import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import bw.co.starsign.context.CallerContext;

/**
 * The caller of the current request, from their Keycloak token. The {@code tenant_id} and
 * {@code organisation_id} claims come from admin-only user attributes that only the API's service account
 * sets (see keycloak/generate_authz.py), so users cannot change their own tenant. Services in core read it
 * through {@link CallerContext}.
 */
@Component
public class SecurityCallerContext implements CallerContext {

    public static final String TENANT_CLAIM = "tenant_id";
    public static final String ORGANISATION_CLAIM = "organisation_id";

    @Override
    public String userId() {
        return jwt().getSubject();
    }

    @Override
    public Optional<String> tenantId() {
        return claim(TENANT_CLAIM);
    }

    @Override
    public String requireTenantId() {
        return tenantId().orElseThrow(() -> new AccessDeniedException("No tenant in token"));
    }

    @Override
    public Optional<String> organisationId() {
        return claim(ORGANISATION_CLAIM);
    }

    @Override
    public boolean has(String permission) {
        return Permissions.has(permission);
    }

    private Optional<String> claim(String name) {
        String value = jwt().getClaimAsString(name);
        return StringUtils.isBlank(value) ? Optional.empty() : Optional.of(value);
    }

    private static Jwt jwt() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            throw new AccessDeniedException("Not signed in");
        }
        return token.getToken();
    }
}
