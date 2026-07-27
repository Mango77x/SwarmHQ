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
        client = new MqttClient(properties.brokerUrl(), properties.clientId(), new MemoryPersistence());
        MqttConnectionOptions options = new MqttConnectionOptions();
        options.setAutomaticReconnect(true);
        options.setCleanStart(true);
        client.connect(options);
        log.info("Connected to MQTT broker at {}", properties.brokerUrl());
        return client;
    }

    @PreDestroy
    public void shutdown() throws MqttException {
        if (client != null && client.isConnected()) {
            client.disconnect();
        }
    }
}
