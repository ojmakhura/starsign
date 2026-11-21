package bw.co.starsign.security;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import bw.co.starsign.auth.SecurityCallerContext;
import bw.co.starsign.security.OwnershipResolver.Owner;

/**
 * Enforces {@link RequiresPermission}, deciding ownership from the database rather than Keycloak.
 * <ol>
 * <li>The record must exist and belong to the caller's tenant ({@code tenant_id} claim): rule 18, checked for
 * every caller, so a type-level permission never opens another organisation's records.</li>
 * <li>A caller with the type-level scope (e.g. {@code documents:view}) may proceed.</li>
 * <li>Otherwise the caller needs the owner scope (e.g. {@code documents:view-own}) and must own the record: be
 * its owner or an internal signatory on it ({@link OwnershipResolver}).</li>
 * </ol>
 * Keycloak still decides which scopes a caller has (the {@code SCOPE_*} authorities); it is not asked about
 * individual records. Runs before the other aspects (auditing) so denied calls never reach them.
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class OwnershipAspect {

    private final OwnershipResolver ownershipResolver;
    private final SpelExpressionParser parser = new SpelExpressionParser();
    private final Map<String, Expression> expressions = new ConcurrentHashMap<>();

    public OwnershipAspect(OwnershipResolver ownershipResolver) {
        this.ownershipResolver = ownershipResolver;
    }

    @Around("@annotation(permission)")
    public Object authorize(ProceedingJoinPoint joinPoint, RequiresPermission permission) throws Throwable {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            throw new AccessDeniedException("Authentication required");
        }

        Set<String> authorities = AuthorityUtils.authorityListToSet(token.getAuthorities());
        boolean typeLevel = authorities.contains("SCOPE_" + permission.scope());
        boolean ownLevel = authorities.contains("SCOPE_" + permission.ownScope());
        if (!typeLevel && !ownLevel) {
            throw new AccessDeniedException("Missing permission " + permission.scope());
        }

        Jwt jwt = token.getToken();
        String tenantId = jwt.getClaimAsString(SecurityCallerContext.TENANT_CLAIM);
        if (StringUtils.isBlank(tenantId)) {
            throw new AccessDeniedException("No tenant in token");
        }

        Object id = evaluate(permission.id(), context(joinPoint));
        if (id == null) {
            // A create without a parent record: only type-level holders
            if (!typeLevel) {
                throw new AccessDeniedException("No owned record to authorize against");
            }
            return joinPoint.proceed();
        }

        Owner owner = ownershipResolver.resolve(permission.owner(), id, jwt.getSubject());
        if (owner == null || !tenantId.equals(owner.tenantId())) {
            // Same answer for "missing" and "another tenant's": do not reveal that the record exists
            throw new AccessDeniedException("Record not found");
        }
        if (!typeLevel && !owner.ownedByCaller()) {
            throw new AccessDeniedException("Record belongs to another user");
        }

        return joinPoint.proceed();
    }

    private EvaluationContext context(ProceedingJoinPoint joinPoint) {
        EvaluationContext context = SimpleEvaluationContext.forReadOnlyDataBinding().build();
        String[] names = ((MethodSignature) joinPoint.getSignature()).getParameterNames();
        Object[] args = joinPoint.getArgs();
        if (names == null) {
            throw new IllegalStateException("Compile with -parameters to use @RequiresPermission");
        }
        for (int i = 0; i < names.length; i++) {
            context.setVariable(names[i], args[i]);
        }
        return context;
    }

    private Object evaluate(String expression, EvaluationContext context) {
        if (StringUtils.isBlank(expression)) {
            return null;
        }
        try {
            return expressions.computeIfAbsent(expression, parser::parseExpression).getValue(context);
        } catch (RuntimeException e) {
            throw new AccessDeniedException("Cannot evaluate " + expression, e);
        }
    }
}
