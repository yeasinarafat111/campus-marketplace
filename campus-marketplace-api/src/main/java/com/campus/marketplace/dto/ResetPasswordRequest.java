package com.campus.marketplace.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** The code field is named {@code code}, like {@link VerifyOtpRequest}. */
public record ResetPasswordRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Email must be a valid email address")
        String email,
        @NotBlank(message = "Code is required")
        @Pattern(regexp = "\\d{6}", message = "Code must be 6 digits")
        String code,
        // Same rule as SignupRequest: BCrypt only uses the first 72 bytes, so longer passwords are rejected.
        @NotBlank(message = "New password is required")
        @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
        String newPassword
) {
    public ResetPasswordRequest {
        email = email == null ? null : email.trim();
        code = code == null ? null : code.trim();
    }
}
