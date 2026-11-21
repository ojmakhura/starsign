package bw.co.starsign.context;

import java.util.Optional;

/**
 * Who is calling a service, as established by the web layer from the Keycloak token
 * ({@code bw.co.starsign.auth.SecurityCallerContext}). Services use it to enforce tenant isolation and to
 * limit owner-scoped users to their own records; they never trust tenant or user ids sent by clients.
 */
public interface CallerContext {

    /** The caller's Keycloak user id (token subject). */
    String userId();

    /** The caller's tenant ({@code tenant_id} claim); empty for platform administrators. */
    Optional<String> tenantId();

    /** The tenant, or an access-denied error when the caller has none. */
    String requireTenantId();

    /** The caller's StarSign organisation id ({@code organisation_id} claim). */
    Optional<String> organisationId();

    /** Whether the caller holds the Keycloak permission, e.g. {@code "documents:view"}. */
    boolean has(String permission);

    /**
     * True when the caller may act on {@code "<resource>:<action>"} only for records they own or sign (they hold
     * the {@code -own} permission but not the type-level one). Listing services must then filter to the
     * caller's records.
     */
    default boolean ownRecordsOnly(String permission) {
        return !has(permission) && has(permission + "-own");
    }
}
