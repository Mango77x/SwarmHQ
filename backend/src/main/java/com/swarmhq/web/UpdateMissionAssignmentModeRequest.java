package com.swarmhq.web;

/** PUT /api/mode body - {@code mode} must be "centralized" or "auction". */
public record UpdateMissionAssignmentModeRequest(String mode) {
}
