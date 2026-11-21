package bw.co.starsign.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Instance-level authorization (Pattern A), enforced by {@link OwnershipAspect}.
 * <p>
 * The record named by {@link #id()} must belong to the caller's tenant (token {@code tenant_id}), and the caller
 * either
 * <ol>
 * <li>holds the type-level Keycloak permission {@link #scope()} (document managers, auditors, administrators), or</li>
 * <li>holds {@link #ownScope()} <em>and</em> owns the record: they are the owner of the document or workflow, or
 * an internal signatory on it ({@link OwnershipResolver}).</li>
 * </ol>
 * Expressions are SpEL over the method's parameter names, e.g. {@code "#id"} or {@code "#workflow.documentId"}.
 * <p>
 * Written into the UML model by {@code keycloak/update_preauthorize.py}; AndroMDA puts it on the *ApiImpl
 * controller method.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {

    /** Type-level scope that grants access to every record of the tenant, e.g. {@code documents:view}. */
    String scope();

    /** Scope an owner-scoped user needs before ownership is checked, e.g. {@code documents:view-own}. */
    String ownScope();

    /** What {@link #id()} identifies. */
    OwnerType owner();

    /** The record being accessed; may evaluate to null only for creates (then the caller needs {@link #scope()}). */
    String id();
}
