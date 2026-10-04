package com.campus.marketplace.dto;

import com.campus.marketplace.entity.Role;
import com.campus.marketplace.security.UserPrincipal;

/** The authenticated caller, as returned by {@code GET /api/users/me}. */
public record CurrentUserResponse(Long id, String email, Role role) {
    public static CurrentUserResponse from(UserPrincipal principal) {
        return new CurrentUserResponse(principal.getId(), principal.getEmail(), principal.getRole());
    }
}
