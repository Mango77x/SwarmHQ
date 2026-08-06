package com.swarmhq.service;

import com.swarmhq.model.DroneStatus;
import com.swarmhq.mqtt.TelemetryPayload;
import com.swarmhq.repository.DroneRepository;
import com.swarmhq.web.DroneResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Type;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Confirms DroneService.applyTelemetry() actually pushes the updated drone
 * over a live STOMP session, not just that it persists (DroneTelemetryListenerTests
 * covers the MQTT-ingest side). This needs a real WebSocket upgrade, so
 * unlike DroneControllerTests it can't use WebEnvironment.MOCK - on a
 * machine hit by the Tomcat/NIO loopback bug documented in HELP.md this
 * will hang/fail locally; verify via the Docker backend instead
 * (`docker compose run --rm backend ./mvnw test`, or build the
 * backend-build Dockerfile stage with tests un-skipped).
 *
 * The real JwtDecoder (auto-configured from swarmhq.mqtt's sibling
 * property, spring.security.oauth2.resourceserver.jwt.jwk-set-uri) needs
 * a running Keycloak to actually validate anything against - not
 * available in this test's context, and not worth starting one just for
 * this - so StubJwtDecoderConfig below replaces it with a decoder that
 * accepts any token value, purely to exercise JwtStompAuthInterceptor's
 * own CONNECT-frame handling (a missing/malformed Authorization header
 * still gets rejected either way, since that check happens before the
 * decoder is ever called).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(DroneServiceWebSocketTests.StubJwtDecoderConfig.class)
class DroneServiceWebSocketTests {

    private static final String EXTERNAL_ID = "ws-broadcast-test";

    @LocalServerPort
    private int port;

    @Autowired
    private DroneService droneService;

    @Autowired
    private DroneRepository droneRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void cleanup() {
        droneRepository.findByExternalId(EXTERNAL_ID).ifPresent(droneRepository::delete);
    }

    @Test
    void broadcastsUpdatedDroneOverStomp() throws Exception {
        WebSocketStompClient stompClient = new WebSocketStompClient(
                new SockJsClient(List.of(new WebSocketTransport(new StandardWebSocketClient()))));

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer test-token");

        StompSession session = stompClient
                .connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(), connectHeaders,
                        new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);

        BlockingQueue<byte[]> received = new LinkedBlockingQueue<>();
        session.subscribe(DroneService.DRONE_UPDATES_TOPIC, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                received.add((byte[]) payload);
            }
        });
        // Give the broker a moment to register the subscription before
        // publishing - otherwise the message can be sent before we're
        // actually listening.
        Thread.sleep(200);

        droneService.applyTelemetry(EXTERNAL_ID,
                new TelemetryPayload("quadcopter", 40.4168, -3.7038, 42, "PATROLLING", Instant.now()));

        byte[] payload = received.poll(5, TimeUnit.SECONDS);
        assertNotNull(payload, "no message received on " + DroneService.DRONE_UPDATES_TOPIC);

        DroneResponse response = objectMapper.readValue(payload, DroneResponse.class);
        assertEquals(EXTERNAL_ID, response.externalId());
        assertEquals(42, response.batteryPercent());
        assertEquals(DroneStatus.PATROLLING, response.status());

        session.disconnect();
    }

    @TestConfiguration
    static class StubJwtDecoderConfig {
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .claim("sub", "test-user")
                    .build();
        }
    }
}
