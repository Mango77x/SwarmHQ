package com.swarmhq.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Every read (GET) stays public - this is a demo tactical map, not a
 * classified system, and locking the map itself down would just get in
 * the way of showing the project. Every mutating call an operator can make
 * (dispatch/cancel/assign a mission, declare a zone, switch the live
 * assignment mode) requires the OPERATOR realm role from a Keycloak-issued
 * JWT.
 *
 * Deliberately not covered yet: the {@code /ws} STOMP endpoint. Its
 * telemetry/alert/mode broadcasts are read-only same as the GETs above, so
 * the actual gap is narrower than it looks - there's no way today to
 * *act* over that channel, only to receive - but authenticating the STOMP
 * handshake itself (a CONNECT-frame header, not a URL Spring Security's
 * HTTP filter chain sees) is a separate mechanism from everything below
 * and is tracked as follow-up work, not silently skipped.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/ws/**").permitAll()
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/**").permitAll()
                        .requestMatchers("/api/**").hasRole("OPERATOR")
                        .anyRequest().permitAll())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
        return http.build();
    }

    /**
     * Keycloak puts realm roles under the token's own {@code realm_access.roles}
     * claim, not the {@code scope}/{@code scp} claim
     * {@link JwtGrantedAuthoritiesConverter} reads by default - so
     * {@code hasRole("OPERATOR")} would silently never match without this.
     * {@code ROLE_} is prefixed here because that's the prefix
     * {@code hasRole(...)} itself always adds before comparing, not
     * something Keycloak's claim already includes.
     */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(this::realmRoleAuthorities);
        return converter;
    }

    @SuppressWarnings("unchecked")
    private Collection<GrantedAuthority> realmRoleAuthorities(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess == null || !(realmAccess.get("roles") instanceof List<?> roles)) {
            return List.of();
        }
        return roles.stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .collect(Collectors.toList());
    }
}
