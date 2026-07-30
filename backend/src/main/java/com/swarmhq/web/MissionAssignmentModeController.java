package com.swarmhq.web;

import com.swarmhq.service.MissionAssignmentModeHolder;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Lets the frontend read and switch the live mission-assignment
 * strategy. What used to be a fixed environment variable, read once at
 * startup, is now a runtime toggle backed by
 * {@link MissionAssignmentModeHolder}.
 */
@RestController
@RequestMapping("/api/mode")
public class MissionAssignmentModeController {

    private final MissionAssignmentModeHolder modeHolder;

    public MissionAssignmentModeController(MissionAssignmentModeHolder modeHolder) {
        this.modeHolder = modeHolder;
    }

    @GetMapping
    public MissionAssignmentModeResponse get() {
        return MissionAssignmentModeResponse.from(modeHolder.get());
    }

    @PutMapping
    public MissionAssignmentModeResponse update(@RequestBody UpdateMissionAssignmentModeRequest request) {
        MissionAssignmentModeHolder.Mode mode = switch (request.mode()) {
            case "centralized" -> MissionAssignmentModeHolder.Mode.CENTRALIZED;
            case "auction" -> MissionAssignmentModeHolder.Mode.AUCTION;
            case null, default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "mode must be 'centralized' or 'auction'");
        };
        modeHolder.set(mode);
        return MissionAssignmentModeResponse.from(mode);
    }
}
