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
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Consumes drone bids for {@code AuctionCoordinatorService}. Stays
 * subscribed all the time rather than only while auction mode is active -
 * a bid that shows up during centralized mode just finds no open auction
 * in {@code AuctionCoordinatorService.recordBid} and gets dropped, the
 * same way a late bid on an already-closed auction would. Same
 * thin-listener/service-layer split as the other MQTT listeners here.
 */
@Component
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
