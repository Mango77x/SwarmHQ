package com.swarmhq.web;

import com.swarmhq.service.MissionAssignmentModeHolder;

public record MissionAssignmentModeResponse(String mode) {
    static MissionAssignmentModeResponse from(MissionAssignmentModeHolder.Mode mode) {
        return new MissionAssignmentModeResponse(mode.wireValue());
    }
}
