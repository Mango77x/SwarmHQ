package com.swarmhq.mqtt;

import com.swarmhq.service.AuctionCoordinatorService;
import jakarta.annotation.PostConstruct;
import org.eclipse.paho.mqttv5.client.IMqttMessageListener;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.MqttSubscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Consumes drone bids for {@code AuctionCoordinatorService} (Sprint 14,
 * auction mode only - same {@code @ConditionalOnProperty} gate, since
 * there's nothing to do with a bid when the centralized engine is active).
 * Same thin-listener/service-layer split as every other MQTT listener in
 * this project.
 */
@Component
@ConditionalOnProperty(prefix = "swarmhq.mission-assignment", name = "mode", havingValue = "auction")
public class MissionBidListener {

    private static final Logger log = LoggerFactory.getLogger(MissionBidListener.class);
    private static final String TOPIC_FILTER = "missions/+/bids";
    private static final Pattern TOPIC_PATTERN = Pattern.compile("^missions/([^/]+)/bids$");

    private final MqttClient mqttClient;
    private final AuctionCoordinatorService auctionCoordinatorService;
    private final ObjectMapper objectMapper;

    public MissionBidListener(MqttClient mqttClient, AuctionCoordinatorService auctionCoordinatorService,
            ObjectMapper objectMapper) {
        this.mqttClient = mqttClient;
        this.auctionCoordinatorService = auctionCoordinatorService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void subscribe() throws MqttException {
        // Same Paho 1.2.5 subscribe() overload workaround as every other listener.
        mqttClient.subscribe(
                new MqttSubscription[] {new MqttSubscription(TOPIC_FILTER, 1)},
                new IMqttMessageListener[] {this::onMessage});
        log.info("Subscribed to {}", TOPIC_FILTER);
    }

    private void onMessage(String topic, MqttMessage message) {
        Matcher matcher = TOPIC_PATTERN.matcher(topic);
        if (!matcher.matches()) {
            log.warn("Ignoring bid on unexpected topic {}", topic);
            return;
        }
        Long missionId;
        try {
            missionId = Long.valueOf(matcher.group(1));
        } catch (NumberFormatException e) {
            log.warn("Ignoring bid on non-numeric mission id in topic {}", topic);
            return;
        }

        try {
            MissionBidPayload payload = objectMapper.readValue(message.getPayload(), MissionBidPayload.class);
            auctionCoordinatorService.recordBid(missionId, payload);
        } catch (Exception e) {
            log.error("Failed to process bid on topic '{}': {}", topic, e.getMessage(), e);
        }
    }
}
