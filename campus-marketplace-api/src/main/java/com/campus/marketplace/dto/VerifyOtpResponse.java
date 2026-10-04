package com.campus.marketplace.dto;

public record VerifyOtpResponse(
        String email,
        boolean enabled,
        String message
) {
}
