package com.campus.marketplace.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record ResendOtpRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Email must be a valid email address")
        String email
) {
    public ResendOtpRequest {
        email = email == null ? null : email.trim();
    }
}
