package com.campus.marketplace.dto;

import com.campus.marketplace.validation.BangladeshiPhone;
import com.campus.marketplace.validation.PhoneNumbers;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Both fields are optional: a null (or omitted) field is left unchanged. A blank one is rejected. */
public record UpdateProfileRequest(
        @Size(max = 100, message = "Full name must be at most 100 characters")
        @Pattern(regexp = ".*\\S.*", message = "Full name must not be blank")
        String fullName,

        @BangladeshiPhone
        @Pattern(regexp = ".*\\S.*", message = "Phone number must not be blank")
        String phone
) {
    public UpdateProfileRequest {
        fullName = fullName == null ? null : fullName.trim();
        phone = PhoneNumbers.normalize(phone);
    }
}
