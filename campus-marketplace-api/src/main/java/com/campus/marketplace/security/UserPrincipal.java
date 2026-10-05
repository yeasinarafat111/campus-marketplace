package com.campus.marketplace.security;

import com.campus.marketplace.entity.Role;
import com.campus.marketplace.entity.User;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;

/**
 * Authenticated user as seen by Spring Security. Inject with {@code @AuthenticationPrincipal UserPrincipal}.
 */
@Getter
public class UserPrincipal implements UserDetails {

    private final Long id;
    private final String email;
    private final String password;
    private final Role role;
    private final boolean enabled;
    private final boolean active;
    private final Instant passwordChangedAt;

    /** An active (not deactivated) principal. */
    public UserPrincipal(Long id, String email, String password, Role role, boolean enabled) {
        this(id, email, password, role, enabled, true);
    }

    public UserPrincipal(Long id, String email, String password, Role role, boolean enabled, boolean active) {
        this(id, email, password, role, enabled, active, null);
    }

    public UserPrincipal(Long id, String email, String password, Role role, boolean enabled, boolean active,
                         Instant passwordChangedAt) {
        this.passwordChangedAt = passwordChangedAt;
        this.id = id;
        this.email = email;
        this.password = password;
        this.role = role;
        this.enabled = enabled;
        this.active = active;
    }

    public static UserPrincipal from(User user) {
        return new UserPrincipal(user.getId(), user.getEmail(), user.getPassword(), user.getRole(),
                user.isEnabled(), user.isActive(), user.getPasswordChangedAt());
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * True if a token issued at {@code issuedAt} predates the last password reset. JWT timestamps are whole
     * seconds, so the reset time is truncated to match; a token issued in the same second as the reset
     * is still accepted.
     */
    public boolean isIssuedBeforePasswordChange(Instant issuedAt) {
        return passwordChangedAt != null && issuedAt.isBefore(passwordChangedAt.truncatedTo(ChronoUnit.SECONDS));
    }

    /** Deactivated accounts are reported as locked, so Spring Security's account checks reject them. */
    @Override
    public boolean isAccountNonLocked() {
        return active;
    }
}
