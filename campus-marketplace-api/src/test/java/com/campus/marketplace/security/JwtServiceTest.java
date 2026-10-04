package com.campus.marketplace.security;

import com.campus.marketplace.config.JwtProperties;
import com.campus.marketplace.entity.Role;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.WeakKeyException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "dGVzdC1zZWNyZXQta2V5LWZvci11bml0LXRlc3RzLW9ubHktMzJieXRlcyE=";
    private static final String OTHER_SECRET = "YW5vdGhlci1zZWNyZXQta2V5LXRoYXQtaXMtYWxzby0zMi1ieXRlcyE=";

    private final UserPrincipal user = new UserPrincipal(1L, "alice@campus.edu", "x", Role.STUDENT, true);
    private final JwtService jwtService = service(SECRET, Duration.ofMinutes(15), Duration.ofDays(7));

    @Test
    void accessToken_roundTrip() {
        String token = jwtService.generateAccessToken(user);
        assertThat(jwtService.validateAccessToken(token)).isEqualTo("alice@campus.edu");
    }

    @Test
    void refreshToken_roundTrip() {
        String token = jwtService.generateRefreshToken(user);
        assertThat(jwtService.validateRefreshToken(token)).isEqualTo("alice@campus.edu");
    }

    @Test
    void refreshToken_isRejectedAsAccessToken() {
        String refresh = jwtService.generateRefreshToken(user);
        assertThatThrownBy(() -> jwtService.validateAccessToken(refresh)).isInstanceOf(JwtException.class);
    }

    @Test
    void accessToken_isRejectedAsRefreshToken() {
        String access = jwtService.generateAccessToken(user);
        assertThatThrownBy(() -> jwtService.validateRefreshToken(access)).isInstanceOf(JwtException.class);
    }

    @Test
    void expiredToken_isRejected() {
        JwtService expiring = service(SECRET, Duration.ofSeconds(-1), Duration.ofSeconds(-1));
        String token = expiring.generateAccessToken(user);
        assertThatThrownBy(() -> jwtService.validateAccessToken(token)).isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void tokenSignedWithDifferentKey_isRejected() {
        String foreign = service(OTHER_SECRET, Duration.ofMinutes(15), Duration.ofDays(7)).generateAccessToken(user);
        assertThatThrownBy(() -> jwtService.validateAccessToken(foreign)).isInstanceOf(JwtException.class);
    }

    @Test
    void tamperedToken_isRejected() {
        String token = jwtService.generateAccessToken(user);
        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." + parts[1] + "x." + parts[2];
        assertThatThrownBy(() -> jwtService.validateAccessToken(tampered)).isInstanceOf(JwtException.class);
    }

    @Test
    void garbage_isRejected() {
        assertThatThrownBy(() -> jwtService.validateAccessToken("not.a.jwt")).isInstanceOf(JwtException.class);
    }

    @Test
    void secretShorterThan256Bits_failsAtStartup() {
        String shortSecret = "c2hvcnQ="; // "short"
        assertThatThrownBy(() -> service(shortSecret, Duration.ofMinutes(15), Duration.ofDays(7)))
                .isInstanceOf(WeakKeyException.class);
    }

    @Test
    void unresolvedOrNonBase64Secret_failsWithClearMessage() {
        assertThatThrownBy(() -> service("${JWT_SECRET}", Duration.ofMinutes(15), Duration.ofDays(7)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void defaultExpiriesFromConfig() {
        assertThat(jwtService.getAccessTokenExpiration()).isEqualTo(Duration.ofMinutes(15));
        assertThat(jwtService.getRefreshTokenExpiration()).isEqualTo(Duration.ofDays(7));
    }

    private static JwtService service(String secret, Duration access, Duration refresh) {
        return new JwtService(new JwtProperties(secret, access, refresh));
    }
}
