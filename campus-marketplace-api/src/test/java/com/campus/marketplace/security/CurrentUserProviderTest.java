package com.campus.marketplace.security;

import com.campus.marketplace.entity.Role;
import com.campus.marketplace.exception.UnauthorizedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrentUserProviderTest {

    private final CurrentUserProvider provider = new CurrentUserProvider();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void noAuthentication_isEmpty_andRequireThrows() {
        assertThat(provider.find()).isEmpty();
        assertThat(provider.isAdmin()).isFalse();
        assertThatThrownBy(provider::require).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void anonymousAuthentication_isTreatedAsUnauthenticated() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        assertThat(provider.find()).isEmpty();
        assertThatThrownBy(provider::requireId).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void authenticatedUser_isResolved() {
        authenticate(new UserPrincipal(42L, "a@seu.edu.bd", "x", Role.STUDENT, true));

        assertThat(provider.requireId()).isEqualTo(42L);
        assertThat(provider.isAdmin()).isFalse();
    }

    @Test
    void admin_isDetected() {
        authenticate(new UserPrincipal(1L, "admin@seu.edu.bd", "x", Role.ADMIN, true));

        assertThat(provider.isAdmin()).isTrue();
    }

    private static void authenticate(UserPrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
