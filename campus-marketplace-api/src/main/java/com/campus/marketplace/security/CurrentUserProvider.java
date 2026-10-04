package com.campus.marketplace.security;

import com.campus.marketplace.entity.Role;
import com.campus.marketplace.exception.UnauthorizedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Resolves the authenticated user from the SecurityContext (set by {@link JwtAuthFilter}).
 * Inject this into services instead of reading SecurityContextHolder directly.
 */
@Component
public class CurrentUserProvider {

    /** The current user, or empty for anonymous requests. */
    public Optional<UserPrincipal> find() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken
                || !(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            return Optional.empty();
        }
        return Optional.of(principal);
    }

    /**
     * @throws UnauthorizedException if the request is not authenticated
     */
    public UserPrincipal require() {
        return find().orElseThrow(() -> new UnauthorizedException("Authentication required"));
    }

    public Long requireId() {
        return require().getId();
    }

    public boolean isAdmin() {
        return find().map(user -> user.getRole() == Role.ADMIN).orElse(false);
    }
}
