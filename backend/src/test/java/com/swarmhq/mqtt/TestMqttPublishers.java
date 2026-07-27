package com.swarmhq.mqtt;

import com.swarmhq.config.MqttConfig;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;

import java.nio.charset.StandardCharsets;

/**
 * TLS + auth (Sprint 11) means a test that wants to publish a message as
 * if it came from a drone needs its own credentialed MQTT connection, not
 * just a plain {@code tcp://} one. Reuses {@link MqttConfig#trustingOnly}
 * rather than duplicating the CA-trust setup. "test-drone-1"/"test-drone-2"
 * are provisioned test-only identities (infra/mosquitto/setup/generate.sh)
 * - deliberately not reusing "drone-1" etc., which a real simulator run
 * might be using concurrently.
 */
final class TestMqttPublishers {

    private static final String CA_CERT_PATH = "../infra/mosquitto/certs/ca.crt";
    private static final String PASSWORD = "changeme";

    private TestMqttPublishers() {
    }

    static MqttClient connect(String clientId, String username) throws Exception {
        MqttClient client = new MqttClient("ssl://localhost:8883", clientId, new MemoryPersistence());
        MqttConnectionOptions options = new MqttConnectionOptions();
        // Paho MQTTv5 defaults cleanStart to false: reconnecting with the
        // same clientId (every test in this class reuses one) would
        // otherwise resume the previous test's session, including any
        // QoS1 message still "in flight" (published, but not yet PUBACK'd
        // before that test's publisher.disconnect()) - which the broker
        // then redelivers, making the listener process it a second time.
        options.setCleanStart(true);
        options.setUserName(username);
        options.setPassword(PASSWORD.getBytes(StandardCharsets.UTF_8));
        options.setSocketFactory(MqttConfig.trustingOnly(CA_CERT_PATH));
        client.connect(options);
        return client;
    }
}
