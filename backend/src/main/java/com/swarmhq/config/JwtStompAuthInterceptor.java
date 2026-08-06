package com.swarmhq.config;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.util.List;

/**
 * Authenticates the STOMP CONNECT frame itself. SecurityConfig's HTTP
 * filter chain only ever sees the initial SockJS handshake request
 * (permitAll'd - see its comment on {@code /ws/**}), not the STOMP frames
 * that follow over the same upgraded connection, so without this an
 * unauthenticated client could still open a live session and receive
 * every drone/event/mode broadcast. Read-only today - there's no
 * {@code /app}-prefixed destination a client can act through (see
 * WebSocketConfig) - but connecting at all shouldn't be free either.
 */
public class JwtStompAuthInterceptor implements ChannelInterceptor {

    private final JwtDecoder jwtDecoder;

    public JwtStompAuthInterceptor(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() != StompCommand.CONNECT) {
            return message;
        }

        String authHeader = accessor.getFirstNativeHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new MessagingException("Missing bearer token on STOMP CONNECT");
        }

        try {
            Jwt jwt = jwtDecoder.decode(authHeader.substring(7));
            // No roles needed here yet - there's nothing over this
            // channel to authorize beyond "connected at all" - but
            // setting a real principal now means a future /app
            // destination doesn't need this interceptor touched again.
            Authentication authentication = new UsernamePasswordAuthenticationToken(jwt.getSubject(), null, List.of());
            accessor.setUser(authentication);
        } catch (JwtException e) {
            throw new MessagingException("Invalid bearer token on STOMP CONNECT", e);
        }

        return message;
    }
}
