package com.campus.marketplace.dto;

/**
 * @param expiresIn access token lifetime in seconds
 */
public record AccessTokenResponse(
        String accessToken,
        String tokenType,
        long expiresIn
) {
}
