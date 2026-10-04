package com.campus.marketplace.dto;

public record SignupResponse(
        Long id,
        String fullName,
        String email,
        String message
) {
}
