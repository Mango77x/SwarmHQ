package com.swarmhq.mqtt;

/**
 * JSON body published to {@code drones/{externalId}/mission-status} once
 * the simulator finishes, aborts, or is told to cancel an assigned mission.
 * {@code status} is {@code "COMPLETED"}, {@code "FAILED"}, or
 * {@code "CANCELLED"}; {@code reason} is only present for a failure (e.g.
 * {@code "low_battery"}).
 */
public record MissionStatusPayload(Long missionId, String status, String reason) {
}
