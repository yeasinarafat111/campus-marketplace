package com.campus.marketplace.security;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Authenticates requests that carry {@code Authorization: Bearer <access token>}.
 * <p>
 * This filter never rejects a request itself. If the token is missing or bad, the request simply
 * continues unauthenticated, and the authorization rules in {@link SecurityConfig} decide whether
 * that is allowed (public route) or ends in a 401 via {@link RestAuthenticationEntryPoint}.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    /** Request attribute holding why the token was rejected; read by the entry point for the 401 message. */
    public static final String AUTH_ERROR_ATTRIBUTE = JwtAuthFilter.class.getName() + ".error";

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)
                || SecurityContextHolder.getContext().getAuthentication() != null) {
            chain.doFilter(request, response);
            return;
        }

        String token = header.substring(BEARER_PREFIX.length()).trim();
        try {
            JwtService.ParsedToken parsed = jwtService.parseAccessToken(token);
            String email = parsed.subject();
            // Reload the user so disabled accounts and role changes take effect immediately.
            UserDetails user = userDetailsService.loadUserByUsername(email);
            if (!user.isAccountNonLocked()) {
                request.setAttribute(AUTH_ERROR_ATTRIBUTE, "Account has been deactivated");
            } else if (!user.isEnabled()) {
                request.setAttribute(AUTH_ERROR_ATTRIBUTE, "Account is not enabled");
            } else if (user instanceof UserPrincipal principal && principal.isIssuedBeforePasswordChange(parsed.issuedAt())) {
                request.setAttribute(AUTH_ERROR_ATTRIBUTE, "Password was changed. Please log in again.");
            } else {
                authenticate(user, request);
            }
        } catch (ExpiredJwtException e) {
            request.setAttribute(AUTH_ERROR_ATTRIBUTE, "Access token has expired");
        } catch (JwtException | IllegalArgumentException | UsernameNotFoundException e) {
            request.setAttribute(AUTH_ERROR_ATTRIBUTE, "Invalid access token");
        }

        chain.doFilter(request, response);
    }

    private void authenticate(UserDetails user, HttpServletRequest request) {
        var authentication = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }
}
