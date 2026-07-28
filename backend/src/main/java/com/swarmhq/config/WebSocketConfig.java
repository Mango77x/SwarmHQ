package com.swarmhq.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP endpoint and broker wiring. {@code DroneService} publishes to
 * {@code /topic/drones} (Sprint 7) - clients connect to {@code /ws} and
 * subscribe there for live drone updates instead of polling REST.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic").setTaskScheduler(taskScheduler());
        registry.setApplicationDestinationPrefixes("/app");
    }

    /**
     * Explicit and shared on purpose (Sprint 16 postmortem): with no
     * scheduler wired here, {@code enableSimpleBroker} provisions its own
     * internal one for the STOMP broker's outbound processing - and
     * because it's the only {@code TaskScheduler} bean Spring can find
     * unambiguously, every {@code @Scheduled} method in the app
     * (MissionAssignmentService, AuctionCoordinatorService,
     * SignalMonitorService) silently ends up sharing THAT pool too,
     * instead of the {@code spring.task.scheduling.pool.size}-configured
     * one Boot would otherwise auto-create (its auto-configuration backs
     * off once any {@code TaskScheduler} bean already exists). Confirmed
     * by every {@code @Scheduled} log line showing thread names like
     * "MessageBroker-3", not "scheduling-3". That pool defaults small
     * enough that adding a real, always-on 1s tick
     * (AuctionCoordinatorService, Sprint 16) was enough contention for a
     * slower/more contended CI runner to delay it past a test's own
     * timeout - see HELP.md. A pool this small was already borderline for
     * three competing @Scheduled tasks regardless of the broker sharing
     * it, so this is sized for that, not just the broker's own needs.
     */
    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(6);
        scheduler.setThreadNamePrefix("scheduled-task-");
        scheduler.initialize();
        return scheduler;
    }
}
