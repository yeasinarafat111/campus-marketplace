package com.campus.marketplace.security;

import com.campus.marketplace.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.DecodingException;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Issues and verifies HMAC-SHA signed JWTs. Each token carries a {@code type} claim so a refresh
 * token can never be used as an access token (and vice versa).
 */
@Service
public class JwtService {

    static final String TOKEN_TYPE_CLAIM = "type";

    private final SecretKey signingKey;
    private final Duration accessTokenExpiration;
    private final Duration refreshTokenExpiration;

    public JwtService(JwtProperties properties) {
        // Throws WeakKeyException at startup if the decoded key is shorter than 256 bits.
        this.signingKey = Keys.hmacShaKeyFor(decodeSecret(properties.secret()));
        this.accessTokenExpiration = properties.accessTokenExpiration();
        this.refreshTokenExpiration = properties.refreshTokenExpiration();
    }

    public String generateAccessToken(UserDetails user) {
        return generateToken(user.getUsername(), TokenType.ACCESS, accessTokenExpiration);
    }

    public String generateRefreshToken(UserDetails user) {
        return generateToken(user.getUsername(), TokenType.REFRESH, refreshTokenExpiration);
    }

    /**
     * @return the subject (user email) of a valid, unexpired access token
     * @throws JwtException if the token is malformed, tampered with, expired or not an access token
     */
    public String validateAccessToken(String token) {
        return parse(token, TokenType.ACCESS).getSubject();
    }

    /**
     * @return the subject (user email) of a valid, unexpired refresh token
     * @throws JwtException if the token is malformed, tampered with, expired or not a refresh token
     */
    public String validateRefreshToken(String token) {
        return parse(token, TokenType.REFRESH).getSubject();
    }

    public Duration getAccessTokenExpiration() {
        return accessTokenExpiration;
    }

    public Duration getRefreshTokenExpiration() {
        return refreshTokenExpiration;
    }

    private static byte[] decodeSecret(String secret) {
        try {
            return Decoders.BASE64.decode(secret);
        } catch (DecodingException e) {
            // Typically an unset JWT_SECRET (left as the literal "${JWT_SECRET}") or a pasted placeholder.
            throw new IllegalStateException(
                    "app.jwt.secret (env var JWT_SECRET) is missing or not valid Base64. "
                            + "Generate one with: openssl rand -base64 32", e);
        }
    }

    private String generateToken(String subject, TokenType type, Duration ttl) {
        Instant now = Instant.now();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(subject)
                .claim(TOKEN_TYPE_CLAIM, type.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(signingKey)
                .compact();
    }

    private Claims parse(String token, TokenType expectedType) {
        Claims claims = Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        if (!expectedType.name().equals(claims.get(TOKEN_TYPE_CLAIM, String.class))) {
            throw new JwtException("Expected a " + expectedType + " token");
        }
        if (claims.getSubject() == null || claims.getSubject().isBlank()) {
            throw new JwtException("Token has no subject");
        }
        return claims;
    }
}
