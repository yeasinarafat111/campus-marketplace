package com.campus.marketplace.dto;

import com.campus.marketplace.entity.Role;
import com.campus.marketplace.entity.User;

import java.time.Instant;

/** User as seen by admins. Never includes the password hash. */
public record AdminUserResponse(
        Long id,
        String fullName,
        String email,
        Role role,
        boolean enabled,
        boolean active,
        Instant createdAt
) {
    public static AdminUserResponse from(User user) {
        return new AdminUserResponse(user.getId(), user.getFullName(), user.getEmail(), user.getRole(),
                user.isEnabled(), user.isActive(), user.getCreatedAt());
    }
}
