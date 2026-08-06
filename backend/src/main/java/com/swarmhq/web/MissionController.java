package com.swarmhq.web;

import com.swarmhq.service.MissionService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/missions")
public class MissionController {

    private final MissionService missionService;

    public MissionController(MissionService missionService) {
        this.missionService = missionService;
    }

    @GetMapping
    public List<MissionResponse> listAll() {
        return missionService.listAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MissionResponse create(@RequestBody CreateMissionRequest request) {
        return missionService.create(request);
    }

    @PostMapping("/{id}/cancel")
    public MissionResponse cancel(@PathVariable Long id) {
        return missionService.cancel(id);
    }

    @PostMapping("/{id}/assign")
    public MissionResponse assign(@PathVariable Long id, @RequestBody ManualAssignmentRequest request) {
        return missionService.assignManually(id, request.droneExternalId());
    }
}
