package com.swarmhq.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "swarmhq.mqtt")
public record MqttProperties(String brokerUrl, String clientId, String username, String password, String caCertPath) {
}
