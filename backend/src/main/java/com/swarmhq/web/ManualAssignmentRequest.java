package com.swarmhq.web;

/**
 * POST /api/missions/{id}/assign body - the operator names the drone
 * directly, bypassing whichever assignment engine (centralized or
 * auction) is currently active. See MissionService.assignManually.
 */
public record ManualAssignmentRequest(String droneExternalId) {
}
