package com.campus.marketplace.dto;

import com.campus.marketplace.validation.AllowedEmailDomain;
import com.campus.marketplace.validation.BangladeshiPhone;
import com.campus.marketplace.validation.PhoneNumbers;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SignupRequest(
        @NotBlank(message = "Full name is required")
        @Size(max = 100, message = "Full name must be at most 100 characters")
        String fullName,

        @NotBlank(message = "Email is required")
        @Email(message = "Email must be a valid email address")
        @Size(max = 255, message = "Email must be at most 255 characters")
        @AllowedEmailDomain
        String email,

        // Accepts 01XXXXXXXXX, 8801... or +8801...; stored as +8801XXXXXXXXX (see the compact constructor).
        @NotBlank(message = "Phone number is required")
        @BangladeshiPhone
        String phone,

        // BCrypt only uses the first 72 bytes of input, so longer passwords are rejected rather than silently truncated.
        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
        String password
) {
    public SignupRequest {
        fullName = fullName == null ? null : fullName.trim();
        email = email == null ? null : email.trim();
        phone = phone == null || phone.isBlank() ? null : PhoneNumbers.normalize(phone);
    }
}
