package com.campus.marketplace.security;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

/**
 * Authenticates a WebSocket session at STOMP CONNECT, using the same rules as {@link JwtAuthFilter}: a valid,
 * unexpired <em>access</em> token in the {@code Authorization: Bearer …} header, for an account that is enabled,
 * not deactivated and whose password hasn't changed since the token was issued. The connection's principal name
 * is the user id, which is what the server pushes to ({@code convertAndSendToUser}). Anything invalid is
 * rejected and the connection closed.
 * <p>
 * The socket is receive-only: clients may only subscribe to their own {@code /user/queue/**} destinations and
 * may not send, because every write goes through REST.
 * <p>
 * An established connection is not re-checked later, so it outlives the access token (15 minutes) and a password
 * change; it can only receive that user's own pushes, and the REST API, which is re-checked on every request,
 * is what enforces access.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final String BEARER = "Bearer ";
    private static final String USER_QUEUE_PREFIX = "/user/queue/";

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        if (StompCommand.CONNECT.equals(command) || StompCommand.STOMP.equals(command)) {
            UserPrincipal user = authenticate(accessor.getFirstNativeHeader("Authorization"));
            accessor.setUser(new UsernamePasswordAuthenticationToken(String.valueOf(user.getId()), null, user.getAuthorities()));
        } else if (StompCommand.SUBSCRIBE.equals(command)) {
            String destination = accessor.getDestination();
            if (accessor.getUser() == null || destination == null || !destination.startsWith(USER_QUEUE_PREFIX)) {
                throw new MessagingException("Subscriptions are limited to your own /user/queue destinations");
            }
        } else if (StompCommand.SEND.equals(command)) {
            throw new MessagingException("Send messages through the REST API, not the socket");
        }
        return message;
    }

    private UserPrincipal authenticate(String header) {
        if (header == null || !header.startsWith(BEARER)) {
            throw new MessagingException("Missing access token");
        }
        try {
            JwtService.ParsedToken parsed = jwtService.parseAccessToken(header.substring(BEARER.length()).trim());
            UserDetails details = userDetailsService.loadUserByUsername(parsed.subject());
            if (!(details instanceof UserPrincipal user) || !user.isAccountNonLocked() || !user.isEnabled()
                    || user.isIssuedBeforePasswordChange(parsed.issuedAt())) {
                throw new MessagingException("Account is not allowed to connect");
            }
            return user;
        } catch (ExpiredJwtException e) {
            throw new MessagingException("Access token has expired");
        } catch (JwtException | IllegalArgumentException | UsernameNotFoundException e) {
            throw new MessagingException("Invalid access token");
        }
    }
}
