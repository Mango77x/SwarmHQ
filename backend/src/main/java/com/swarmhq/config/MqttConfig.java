package com.swarmhq.config;

import jakarta.annotation.PreDestroy;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

/**
 * Wires a single MQTT client to the broker so the application can prove
 * connectivity at startup. Subscribing to telemetry topics and persisting
 * messages is the MQTT listener's job (Sprint 4), not this skeleton's.
 */
@Configuration
@EnableConfigurationProperties(MqttProperties.class)
public class MqttConfig {

    private static final Logger log = LoggerFactory.getLogger(MqttConfig.class);

    private MqttClient client;

    // destroyMethod disabled: Spring would otherwise infer MqttClient#close()
    // as the destroy method and call it before our own @PreDestroy disconnects,
    // which throws since close() requires the client to already be disconnected.
    @Bean(destroyMethod = "")
    public MqttClient mqttClient(MqttProperties properties) throws MqttException {
        // Suffixed with a random id: the broker allows only one active
        // connection per client id, so a fixed id collides (and silently
        // kicks the other side) whenever more than one instance of this
        // app - e.g. a local run and the Docker container - connects at
        // once. cleanStart=true means there's no session continuity to
        // lose by not reusing the same id across restarts anyway.
        String clientId = properties.clientId() + "-" + UUID.randomUUID().toString().substring(0, 8);
        client = new MqttClient(properties.brokerUrl(), clientId, new MemoryPersistence());
        MqttConnectionOptions options = new MqttConnectionOptions();
        options.setAutomaticReconnect(true);
        options.setCleanStart(true);
        client.connect(options);
        log.info("Connected to MQTT broker at {} as {}", properties.brokerUrl(), clientId);
        return client;
    }

    @PreDestroy
    public void shutdown() throws MqttException {
        if (client != null && client.isConnected()) {
            client.disconnect();
        }
    }
}
