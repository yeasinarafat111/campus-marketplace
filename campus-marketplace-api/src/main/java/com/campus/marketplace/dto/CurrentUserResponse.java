package com.campus.marketplace.dto;

import com.campus.marketplace.entity.Role;
import com.campus.marketplace.entity.User;

import java.time.Instant;

/**
 * The authenticated caller's own profile, as returned by {@code GET /api/users/me}. Includes the phone
 * number, so it must only ever be returned to the user it describes.
 */
public record CurrentUserResponse(Long id, String fullName, String email, String phone, Role role,
                                  Instant createdAt) {
    public static CurrentUserResponse from(User user) {
        return new CurrentUserResponse(user.getId(), user.getFullName(), user.getEmail(), user.getPhone(),
                user.getRole(), user.getCreatedAt());
    }
}
