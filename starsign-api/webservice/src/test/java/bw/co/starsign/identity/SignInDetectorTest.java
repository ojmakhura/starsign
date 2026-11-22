package bw.co.starsign.identity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

class SignInDetectorTest {

    private static final ZoneId ZONE = ZoneId.of("Africa/Gaborone");
    private static final LocalDateTime LAST_LOGIN = LocalDateTime.of(2026, 11, 21, 11, 0, 0, 500_000_000);
    private static final Instant LAST_LOGIN_INSTANT = LAST_LOGIN.atZone(ZONE).toInstant();

    @Test
    void firstSignInIsRecorded() {
        assertTrue(SignInDetector.isNewSignIn(null, Instant.now(), UserStatus.PENDING_VERIFICATION, ZONE));
        assertTrue(SignInDetector.isNewSignIn(null, null, UserStatus.INVITED, ZONE));
        assertTrue(SignInDetector.isNewSignIn(null, null, UserStatus.ACTIVE, ZONE));
    }

    @Test
    void laterAuthenticationIsANewSignIn() {
        assertTrue(SignInDetector.isNewSignIn(LAST_LOGIN, LAST_LOGIN_INSTANT.plusSeconds(3600), UserStatus.ACTIVE, ZONE));
    }

    @Test
    void sameSessionIsNotRecordedAgain() {
        // auth_time is whole seconds and the sign-in was recorded just after it
        Instant authTime = LAST_LOGIN_INSTANT.minusMillis(500);
        assertFalse(SignInDetector.isNewSignIn(LAST_LOGIN, authTime, UserStatus.ACTIVE, ZONE));
        assertFalse(SignInDetector.isNewSignIn(LAST_LOGIN, LAST_LOGIN_INSTANT.minusSeconds(60), UserStatus.ACTIVE, ZONE));
    }

    @Test
    void withoutAuthTimeOnlyUsersAwaitingActivationAreRecorded() {
        assertTrue(SignInDetector.isNewSignIn(LAST_LOGIN, null, UserStatus.PENDING_VERIFICATION, ZONE));
        assertTrue(SignInDetector.isNewSignIn(LAST_LOGIN, null, UserStatus.INVITED, ZONE));
        assertFalse(SignInDetector.isNewSignIn(LAST_LOGIN, null, UserStatus.ACTIVE, ZONE));
    }

    @Test
    void lockedAndDeactivatedUsersAreNeverRecorded() {
        assertFalse(SignInDetector.isNewSignIn(null, Instant.now(), UserStatus.LOCKED, ZONE));
        assertFalse(SignInDetector.isNewSignIn(LAST_LOGIN, LAST_LOGIN_INSTANT.plusSeconds(3600), UserStatus.DEACTIVATED, ZONE));
    }
}
