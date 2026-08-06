package com.swarmhq.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Optional;

/**
 * Backs {@code Mission}/{@code RiskZone}'s {@code @CreatedBy}/
 * {@code @LastModifiedBy} fields (hardening layer item 3) with the current
 * operator's identity from their Keycloak JWT - {@code preferred_username}
 * over the raw {@code sub} claim (a UUID), since it's what an operator
 * actually recognizes as themselves. Empty, not a fabricated "system"
 * placeholder, when there's no authenticated request in progress (a
 * scheduled pass like MissionAssignmentService, or AlertService's geofence
 * auto-recall triggered off telemetry) - there genuinely is no "who" to
 * attribute a machine-driven change to, the same reasoning
 * PROJECT_OVERVIEW.md gives for building auth before this feature at all.
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class JpaAuditingConfig {

    @Bean
    public AuditorAware<String> auditorAware() {
        return () -> {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null || !authentication.isAuthenticated()
                    || !(authentication.getPrincipal() instanceof Jwt jwt)) {
                return Optional.empty();
            }
            String username = jwt.getClaimAsString("preferred_username");
            return Optional.of(username != null ? username : jwt.getSubject());
        };
    }
}
