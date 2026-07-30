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

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.UUID;

/**
 * Wires a single MQTT client to the broker so the application can prove
 * connectivity at startup. The listener classes handle subscribing to
 * telemetry topics and actually persisting anything.
 *
 * TLS + per-client auth: mosquitto's self-signed dev CA
 * (infra/mosquitto/setup/generate.sh) isn't in the JVM's default trust
 * store, so instead of provisioning a full Java keystore file, this just
 * builds a one-off SSLContext that trusts that one CA.
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
    public MqttClient mqttClient(MqttProperties properties) throws Exception {
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
        options.setUserName(properties.username());
        options.setPassword(properties.password().getBytes(StandardCharsets.UTF_8));
        options.setSocketFactory(trustingOnly(properties.caCertPath()));
        client.connect(options);
        log.info("Connected to MQTT broker at {} as {}", properties.brokerUrl(), clientId);
        return client;
    }

    /** Also used by tests that need their own MQTT connection (e.g. to
     * publish a message simulating a drone) against the same broker. */
    public static SSLSocketFactory trustingOnly(String caCertPath) throws Exception {
        CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
        Certificate ca;
        try (InputStream in = Files.newInputStream(Path.of(caCertPath))) {
            ca = certificateFactory.generateCertificate(in);
        }

        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        trustStore.setCertificateEntry("swarmhq-dev-ca", ca);

        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(trustStore);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustManagerFactory.getTrustManagers(), new SecureRandom());
        return sslContext.getSocketFactory();
    }

    @PreDestroy
    public void shutdown() throws MqttException {
        if (client != null && client.isConnected()) {
            client.disconnect();
        }
    }
}
