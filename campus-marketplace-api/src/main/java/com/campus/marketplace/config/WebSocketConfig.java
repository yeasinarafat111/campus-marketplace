package com.campus.marketplace.config;

import com.campus.marketplace.security.StompAuthChannelInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

import java.util.List;

/**
 * Real-time push for chat: plain WebSocket at {@code /ws} with STOMP on top, on the same port and the same JWT
 * rules as the REST API (see {@link StompAuthChannelInterceptor}). Why not Socket.IO: it is its own protocol, and a
 * Spring server would need a second server on a second port, which Cloud Run can't expose.
 * <p>
 * The broker is the in-memory simple broker, so a message pushed on one instance only reaches users connected to
 * that same instance. That is fine for one instance, which is how chat must be deployed
 * ({@code --max-instances=1}). The way to scale out is a STOMP broker relay (RabbitMQ) or Redis pub/sub in place of
 * the simple broker; not built.
 * <p>
 * Browsers can't set headers on a WebSocket handshake, so {@code /ws} is open at the HTTP level (SecurityConfig) and
 * the token is checked at STOMP CONNECT instead. The handshake itself only accepts the configured origins.
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final long HEARTBEAT_MS = 10_000;

    private final StompAuthChannelInterceptor authInterceptor;

    /** Same comma-separated list as the REST CORS setting (APP_CORS_ALLOWED_ORIGINS). */
    @Value("${app.cors.allowed-origins:http://localhost:5173}")
    private List<String> allowedOrigins;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns(allowedOrigins.toArray(String[]::new));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/queue")
                .setHeartbeatValue(new long[] {HEARTBEAT_MS, HEARTBEAT_MS})
                .setTaskScheduler(webSocketHeartbeatScheduler());
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authInterceptor);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        // Clients only send CONNECT / SUBSCRIBE / heartbeats, so frames are tiny.
        registration.setMessageSizeLimit(16 * 1024);
    }

    @Bean
    public ThreadPoolTaskScheduler webSocketHeartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        return scheduler;
    }
}
