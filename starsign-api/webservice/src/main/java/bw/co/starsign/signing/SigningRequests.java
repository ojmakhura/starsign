package bw.co.starsign.signing;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Web side of {@link SigningClient}: binds the caller's address, user agent and signing session cookie for the
 * signing services, and turns what they return into responses.
 */
final class SigningRequests
{
    /** HttpOnly cookie carrying the signing session issued after identity verification. */
    static final String SESSION_COOKIE = "starsign_signing_session";

    private SigningRequests()
    {
    }

    /** Binds the current request; close it when the call is done (try-with-resources). */
    static SigningClient bind()
    {
        HttpServletRequest request = request();
        if (request == null)
        {
            return SigningClient.bind(null, null, null);
        }
        String session = null;
        if (request.getCookies() != null)
        {
            for (Cookie cookie : request.getCookies())
            {
                if (SESSION_COOKIE.equals(cookie.getName()))
                {
                    session = cookie.getValue();
                }
            }
        }
        // The remote address honours trusted proxies (server.forward-headers-strategy)
        return SigningClient.bind(request.getRemoteAddr(), request.getHeader(HttpHeaders.USER_AGENT), session);
    }

    /** The Set-Cookie value for a signing session issued during this call, or null. */
    static String sessionCookie(SigningClient client)
    {
        if (client.issuedSessionToken() == null)
        {
            return null;
        }
        HttpServletRequest request = request();
        long seconds = Math.max(0, Duration.between(LocalDateTime.now(), client.issuedSessionExpiresAt()).getSeconds());
        return ResponseCookie.from(SESSION_COOKIE, client.issuedSessionToken())
                .httpOnly(true)
                .secure(request != null && request.isSecure())
                .sameSite("Strict")
                .path((request == null ? "" : request.getContextPath()) + "/signing/invitations")
                .maxAge(seconds)
                .build()
                .toString();
    }

    /** A file response; inline shows it in the browser, attachment downloads it. */
    static ResponseEntity<Resource> file(Resource resource, MediaType type, boolean inline)
    {
        String name = resource.getFilename() == null ? "document" : resource.getFilename();
        ContentDisposition disposition = (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(name, StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(resource);
    }

    private static HttpServletRequest request()
    {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest()
                : null;
    }
}
