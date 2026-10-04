package com.campus.marketplace.dto;

/**
 * @param expiresIn access token lifetime in seconds
 */
public record AuthResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn
) {
}
