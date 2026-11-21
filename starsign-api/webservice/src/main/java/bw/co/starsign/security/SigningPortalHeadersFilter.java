package bw.co.starsign.security;

import java.io.IOException;

import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Responses of the external signing portal ({@code /signing/invitations/{token}/...}) carry the invitation
 * token in their URL: they must not be cached by browsers or proxies, and the URL must not leak to other
 * sites through the Referer header. Registered in the security filter chain (not a bean, so it runs once).
 */
public class SigningPortalHeadersFilter extends OncePerRequestFilter {

    static final String PREFIX = "/signing/invitations/";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().substring(request.getContextPath().length()).startsWith(PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Robots-Tag", "noindex, nofollow");
        chain.doFilter(request, response);
    }
}
