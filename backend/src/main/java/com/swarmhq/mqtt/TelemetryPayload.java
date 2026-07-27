package com.swarmhq.mqtt;

import java.time.Instant;

/**
 * JSON body published to {@code drones/{externalId}/telemetry}. The drone's
 * external id comes from the topic itself, not this payload, so there's a
 * single source of truth for it.
 */
public record TelemetryPayload(
        String type,
        double lat,
        double lon,
        int batteryPercent,
        String status,
        Instant timestamp
) {
}
