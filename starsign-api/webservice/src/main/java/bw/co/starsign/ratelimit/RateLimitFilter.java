package bw.co.starsign.ratelimit;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Applies {@link RateLimitProperties} to every request. Registered in the security filter chain after bearer
 * token authentication, so signed-in callers are limited per user and anonymous ones per client IP.
 * Not a bean: a Filter bean would also be registered as a plain servlet filter and run twice.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String DEFAULT_LIMIT = "default";

    private record MatchedRule(RequestMatcher matcher, RateLimitProperties.Rule rule) {
    }

    private final RateLimiter rateLimiter;
    private final RateLimitProperties properties;
    private final List<MatchedRule> rules = new ArrayList<>();

    public RateLimitFilter(RateLimiter rateLimiter, RateLimitProperties properties) {
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        for (RateLimitProperties.Rule rule : properties.rules()) {
            rules.add(new MatchedRule(PathPatternRequestMatcher.withDefaults().matcher(rule.method(), rule.pattern()),
                    rule));
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !properties.enabled()
                || HttpMethod.OPTIONS.matches(request.getMethod())
                || path.startsWith("/actuator") || path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs") || path.equals("/error");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String client = clientOf(request);

        long perSecond = properties.requestsPerSecond();
        RateLimiter.Decision decision = rateLimiter.tryConsume(DEFAULT_LIMIT, client, perSecond, Duration.ofSeconds(1));

        for (MatchedRule matched : rules) {
            if (!decision.allowed()) {
                break;
            }
            if (matched.matcher().matches(request)) {
                RateLimitProperties.Rule rule = matched.rule();
                decision = rateLimiter.tryConsume(rule.name(), client, rule.capacity(), rule.period());
            }
        }

        if (!decision.allowed()) {
            reject(response, decision);
            return;
        }

        response.setHeader("X-RateLimit-Remaining", Long.toString(decision.remaining()));
        chain.doFilter(request, response);
    }

    /** The token subject for signed-in callers, the client IP otherwise (see server.forward-headers-strategy). */
    private static String clientOf(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token) {
            return "user:" + token.getToken().getSubject();
        }
        return "ip:" + request.getRemoteAddr();
    }

    private static void reject(HttpServletResponse response, RateLimiter.Decision decision) throws IOException {
        long seconds = Math.max(1, (decision.retryAfter().toMillis() + 999) / 1000);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", Long.toString(seconds));
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,"
                + "\"detail\":\"Rate limit exceeded. Retry after " + seconds + " seconds.\"}");
    }
}
