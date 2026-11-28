package bw.co.starsign.config;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import bw.co.starsign.ratelimit.RateLimitFilter;
import bw.co.starsign.ratelimit.RateLimitProperties;
import bw.co.starsign.ratelimit.RateLimiter;
import bw.co.starsign.security.CorsProperties;
import bw.co.starsign.security.KeycloakAuthzProperties;
import bw.co.starsign.security.PermissionService;
import bw.co.starsign.security.PublicEndpoints;
import bw.co.starsign.security.SigningPortalHeadersFilter;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.DispatcherType;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@EnableConfigurationProperties({ KeycloakAuthzProperties.class, RateLimitProperties.class, CorsProperties.class })
public class SpringSecurityConfig {

    /**
     * Requests need a valid Keycloak token except for the endpoints in {@link PublicEndpoints} (organisation
     * registration, public branding and the external signing portal, whose invitation token the signing services
     * validate). What an authenticated caller may do is decided per handler by {@code @PreAuthorize} (Keycloak
     * permission scopes, Pattern B) and {@code @RequiresPermission} (ownership of a document or workflow,
     * Pattern A). Tenant isolation is enforced again in the services from the token's {@code tenant_id}.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
            JwtAuthenticationConverter jwtAuthenticationConverter, RateLimiter rateLimiter,
            RateLimitProperties rateLimitProperties) throws Exception {

        http
                .cors(Customizer.withDefaults())
                // Stateless bearer tokens: no session cookie for CSRF to ride on
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(authz -> {
                    authz.requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**",
                            "/actuator/health", "/actuator/health/**", "/actuator/info", "/error").permitAll();
                    authz.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll();
                    // Server-Sent Events (/notifications/me/stream) finish on an async dispatch; the request
                    // itself was authorised, and a stateless chain has no authentication left to check there
                    authz.dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll();
                    for (PublicEndpoints.Endpoint endpoint : PublicEndpoints.PROTECTED_OVERLAPS) {
                        authz.requestMatchers(endpoint.method(), endpoint.pattern()).authenticated();
                    }
                    for (PublicEndpoints.Endpoint endpoint : PublicEndpoints.ENDPOINTS) {
                        authz.requestMatchers(endpoint.method(), endpoint.pattern()).permitAll();
                    }
                    authz.anyRequest().authenticated();
                })
                .sessionManagement(management -> management
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .oauth2ResourceServer(
                        oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                // After token authentication, so signed-in callers are limited per user
                .addFilterAfter(new RateLimitFilter(rateLimiter, rateLimitProperties),
                        BearerTokenAuthenticationFilter.class)
                // Invitation links must never be cached or leak through the Referer header
                .addFilterBefore(new SigningPortalHeadersFilter(), BearerTokenAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Enable Security for threads spawned by Resilience4j and async work
     */
    @PostConstruct
    public void enableAuthenticationContextOnSpawnedThreads() {
        SecurityContextHolder.setStrategyName(SecurityContextHolder.MODE_INHERITABLETHREADLOCAL);
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        // The portals' origins only (app.cors.allowed-origins); never "*" with credentials
        configuration.setAllowedOriginPatterns(properties.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of("ETag", "Retry-After", "X-RateLimit-Remaining", "Content-Disposition"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter(PermissionService permissionService) {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        KeycloakRoleConverter roles = new KeycloakRoleConverter();

        // Realm/client roles as ROLE_*, plus the Keycloak Authorization Services
        // permissions granted to the token as SCOPE_<resource>:<action>
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Set<GrantedAuthority> authorities = new HashSet<>(roles.convert(jwt));
            permissionService.grantedScopes(jwt)
                    .forEach(scope -> authorities.add(new SimpleGrantedAuthority("SCOPE_" + scope)));
            return authorities;
        });

        converter.setPrincipalClaimName("preferred_username");

        return converter;
    }
}
