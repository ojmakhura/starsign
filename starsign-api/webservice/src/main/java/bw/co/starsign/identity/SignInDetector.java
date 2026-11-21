package bw.co.starsign.identity;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Decides whether a request carries a sign-in that has not been recorded yet.
 *
 * <p>The portal loads the current user ({@code GET /users/me}) right after every Keycloak sign-in and on every page
 * load. A sign-in is new when the token's {@code auth_time} (when the user actually authenticated) is later than the
 * last recorded sign-in, so reloading the page or refreshing the token does not record it again.
 *
 * <p>Without {@code auth_time} (a client that does not request the {@code basic} scope), only the first sign-in and
 * users still waiting for activation are recorded.
 */
final class SignInDetector {

    private SignInDetector() {
    }

    /**
     * @param lastLoginAt last recorded sign-in, in the server's time zone; null when never recorded
     * @param authTime the token's {@code auth_time}; null when the token has none
     * @param status the user's current status
     * @param zone the time zone {@code lastLoginAt} is stored in
     */
    static boolean isNewSignIn(LocalDateTime lastLoginAt, Instant authTime, UserStatus status, ZoneId zone) {

        if (status == UserStatus.LOCKED || status == UserStatus.DEACTIVATED) {
            return false;
        }
        if (lastLoginAt == null) {
            return true;
        }
        if (authTime == null) {
            return status == UserStatus.INVITED || status == UserStatus.PENDING_VERIFICATION;
        }
        // auth_time has whole seconds; lastLoginAt is set after it, at sub-second precision
        return authTime.isAfter(lastLoginAt.atZone(zone).toInstant());
    }
}
