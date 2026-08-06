package com.swarmhq.web;

import com.swarmhq.model.Drone;
import com.swarmhq.model.DroneStatus;
import com.swarmhq.model.Mission;
import com.swarmhq.model.MissionPriority;
import com.swarmhq.model.MissionStatus;
import com.swarmhq.repository.DroneRepository;
import com.swarmhq.repository.MissionRepository;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class MissionControllerTests {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MissionRepository missionRepository;

    @Autowired
    private DroneRepository droneRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void listsMissionsWithRouteAndNoAssignedDroneYet() throws Exception {
        Mission mission = new Mission(
                GEOMETRY_FACTORY.createLineString(new Coordinate[] {
                        new Coordinate(-3.7038, 40.4168),
                        new Coordinate(-3.6978, 40.4228)
                }),
                MissionPriority.HIGH);
        missionRepository.saveAndFlush(mission);

        mockMvc.perform(get("/api/missions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$[?(@.id == " + mission.getId() + ")].status").value("PENDING"))
                .andExpect(jsonPath("$[?(@.id == " + mission.getId() + ")].priority").value("HIGH"));
    }

    @Test
    void createsAPendingMissionFromARoute() throws Exception {
        CreateMissionRequest request = new CreateMissionRequest(
                java.util.List.of(new double[] {-3.7038, 40.4168}, new double[] {-3.6978, 40.4228}),
                MissionPriority.LOW);

        mockMvc.perform(post("/api/missions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.priority").value("LOW"))
                .andExpect(jsonPath("$.route.length()").value(2));
    }

    @Test
    void cancellingAPendingMissionMarksItCancelledImmediately() throws Exception {
        Mission mission = missionRepository.saveAndFlush(pendingMission());

        mockMvc.perform(post("/api/missions/{id}/cancel", mission.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void cancellingAnAlreadyTerminalMissionIsRejected() throws Exception {
        Mission mission = pendingMission();
        mission.setStatus(MissionStatus.COMPLETED);
        missionRepository.saveAndFlush(mission);

        mockMvc.perform(post("/api/missions/{id}/cancel", mission.getId()))
                .andExpect(status().isConflict());
    }

    @Test
    void cancellingAnUnknownMissionReturnsNotFound() throws Exception {
        mockMvc.perform(post("/api/missions/{id}/cancel", 9_999_999L))
                .andExpect(status().isNotFound());
    }

    @Test
    void cancellingAnActiveMissionPublishesTheCommandAndLeavesItActiveUntilConfirmed() throws Exception {
        // The mission only flips to CANCELLED once the drone's own
        // mission-status report comes back (see MissionStatusListenerTests)
        // - this just proves the cancel command goes out without error and
        // the mission isn't optimistically marked cancelled up front.
        Drone drone = droneRepository.saveAndFlush(
                new Drone("mission-cancel-test-drone", "quadcopter", DroneStatus.ON_MISSION, 80));
        Mission mission = pendingMission();
        mission.setAssignedDrone(drone);
        mission.setStatus(MissionStatus.ACTIVE);
        missionRepository.saveAndFlush(mission);

        mockMvc.perform(post("/api/missions/{id}/cancel", mission.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        assertEquals(MissionStatus.ACTIVE, missionRepository.findById(mission.getId()).orElseThrow().getStatus());
    }

    @Test
    void manuallyAssigningAPendingMissionActivatesItAndFliesTheNamedDrone() throws Exception {
        Drone drone = droneRepository.saveAndFlush(
                new Drone("manual-assign-test-drone", "quadcopter", DroneStatus.PATROLLING, 90));
        Mission mission = missionRepository.saveAndFlush(pendingMission());

        mockMvc.perform(post("/api/missions/{id}/assign", mission.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new ManualAssignmentRequest(drone.getExternalId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.assignedDroneExternalId").value(drone.getExternalId()));

        assertEquals(DroneStatus.ON_MISSION, droneRepository.findById(drone.getId()).orElseThrow().getStatus());
    }

    @Test
    void manuallyAssigningAnAlreadyActiveMissionIsRejected() throws Exception {
        Drone drone = droneRepository.saveAndFlush(
                new Drone("manual-assign-active-test-drone", "quadcopter", DroneStatus.PATROLLING, 90));
        Mission mission = pendingMission();
        mission.setStatus(MissionStatus.ACTIVE);
        missionRepository.saveAndFlush(mission);

        mockMvc.perform(post("/api/missions/{id}/assign", mission.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new ManualAssignmentRequest(drone.getExternalId()))))
                .andExpect(status().isConflict());
    }

    @Test
    void manuallyAssigningToABusyDroneIsRejected() throws Exception {
        Drone drone = droneRepository.saveAndFlush(
                new Drone("manual-assign-busy-test-drone", "quadcopter", DroneStatus.ON_MISSION, 90));
        Mission mission = missionRepository.saveAndFlush(pendingMission());

        mockMvc.perform(post("/api/missions/{id}/assign", mission.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new ManualAssignmentRequest(drone.getExternalId()))))
                .andExpect(status().isConflict());
    }

    @Test
    void manuallyAssigningToAnUnknownDroneReturnsNotFound() throws Exception {
        Mission mission = missionRepository.saveAndFlush(pendingMission());

        mockMvc.perform(post("/api/missions/{id}/assign", mission.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new ManualAssignmentRequest("no-such-drone"))))
                .andExpect(status().isNotFound());
    }

    private Mission pendingMission() {
        return new Mission(
                GEOMETRY_FACTORY.createLineString(new Coordinate[] {
                        new Coordinate(-3.7038, 40.4168),
                        new Coordinate(-3.6978, 40.4228)
                }),
                MissionPriority.MEDIUM);
    }
}
