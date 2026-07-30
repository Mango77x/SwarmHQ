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
 * STOMP endpoint and broker wiring. Clients connect to {@code /ws} and
 * subscribe to {@code /topic/drones} for live drone updates ({@code
 * DroneService} publishes there) rather than polling REST.
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
     * Explicit and shared: leave this unset and {@code enableSimpleBroker}
     * quietly provisions its own scheduler for the STOMP broker's outbound
     * processing. That becomes the only {@code TaskScheduler} bean Spring
     * can find, so every {@code @Scheduled} method in the app
     * (MissionAssignmentService, AuctionCoordinatorService,
     * SignalMonitorService) ends up sharing that same pool instead of the
     * one {@code spring.task.scheduling.pool.size} would otherwise
     * configure (Boot's auto-configuration backs off once any {@code
     * TaskScheduler} bean already exists). Found this by noticing
     * {@code @Scheduled} log lines using thread names like "MessageBroker-3"
     * instead of "scheduling-3". That default pool is small enough that
     * adding a real, always-on 1s tick (AuctionCoordinatorService) created
     * just enough contention to delay a scheduled task past a test's own
     * timeout on a slower CI runner - see HELP.md. Three competing
     * {@code @Scheduled} tasks would already have made a small pool
     * borderline even without the broker sharing it, so this size covers
     * both.
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
