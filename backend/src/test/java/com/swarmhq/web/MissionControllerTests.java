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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
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
                        .with(operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.priority").value("LOW"))
                .andExpect(jsonPath("$.route.length()").value(2))
                // JpaAuditingConfig's AuditorAware reads the operator's
                // identity off their JWT - "user" is jwt()'s own default
                // subject claim when a test doesn't override it.
                .andExpect(jsonPath("$.createdBy").value("user"));
    }

    @Test
    void creatingAMissionThatCrossesARiskZoneIsRejected() throws Exception {
        // Inside the seeded "Sector 1 Perimeter Risk Zone" (V3__add_risk_zones.sql):
        // lon [-3.7048, -3.7028], lat [40.4190, 40.4210]. A straight line
        // at lon -3.7038 sweeping through that lat range cuts right
        // through it.
        CreateMissionRequest request = new CreateMissionRequest(
                java.util.List.of(new double[] {-3.7038, 40.4180}, new double[] {-3.7038, 40.4220}),
                MissionPriority.HIGH);

        mockMvc.perform(post("/api/missions")
                        .with(operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isConflict());
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void historyShowsARevisionPerLifecycleChange() throws Exception {
        // Suspends this class's usual @Transactional-per-test-method
        // wrapper (every other test here relies on it for free rollback
        // cleanup) because Envers writes its revision rows via a
        // beforeTransactionCompletion hook that only fires on a genuine
        // commit - under Spring's test-managed rollback-only transaction,
        // that hook never runs at all, so missions_aud would still be
        // empty even after a successful flush. Each call below now really
        // commits, same as production traffic, so the later history read
        // actually sees what the earlier writes produced. Manual cleanup
        // at the end instead of automatic rollback; the _aud/revinfo rows
        // themselves are intentionally left alone - audit history, like
        // Event, is never deleted once written.
        Mission mission = missionRepository.saveAndFlush(pendingMission());
        try {
            // Deliberately a plain save(), not updateStatusIfPending - a
            // bulk JPQL UPDATE bypasses Hibernate's normal entity
            // lifecycle entirely, so Envers (hooked into that lifecycle)
            // never sees it as a change worth a revision. Every real
            // status transition in this app goes through a regular save()
            // (MissionStatusListener, MissionAssigner), so this is
            // representative, not a shortcut.
            mission.setStatus(MissionStatus.CANCELLED);
            missionRepository.saveAndFlush(mission);

            mockMvc.perform(get("/api/missions/{id}/history", mission.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].revisionType").value("ADD"))
                    .andExpect(jsonPath("$[0].status").value("PENDING"))
                    .andExpect(jsonPath("$[1].revisionType").value("MOD"))
                    .andExpect(jsonPath("$[1].status").value("CANCELLED"));
        } finally {
            missionRepository.deleteById(mission.getId());
        }
    }

    @Test
    void historyForAnUnknownMissionReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/missions/{id}/history", 9_999_999L))
                .andExpect(status().isNotFound());
    }

    @Test
    void cancellingAPendingMissionMarksItCancelledImmediately() throws Exception {
        Mission mission = missionRepository.saveAndFlush(pendingMission());

        mockMvc.perform(post("/api/missions/{id}/cancel", mission.getId()).with(operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void cancellingAnAlreadyTerminalMissionIsRejected() throws Exception {
        Mission mission = pendingMission();
        mission.setStatus(MissionStatus.COMPLETED);
        missionRepository.saveAndFlush(mission);

        mockMvc.perform(post("/api/missions/{id}/cancel", mission.getId()).with(operator()))
                .andExpect(status().isConflict());
    }

    @Test
    void cancellingAnUnknownMissionReturnsNotFound() throws Exception {
        mockMvc.perform(post("/api/missions/{id}/cancel", 9_999_999L).with(operator()))
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

        mockMvc.perform(post("/api/missions/{id}/cancel", mission.getId()).with(operator()))
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
                        .with(operator())
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
                        .with(operator())
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
                        .with(operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new ManualAssignmentRequest(drone.getExternalId()))))
                .andExpect(status().isConflict());
    }

    @Test
    void manuallyAssigningToAnUnknownDroneReturnsNotFound() throws Exception {
        Mission mission = missionRepository.saveAndFlush(pendingMission());

        mockMvc.perform(post("/api/missions/{id}/assign", mission.getId())
                        .with(operator())
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

    /** A mock JWT carrying the ROLE_OPERATOR authority SecurityConfig requires
     * on every mutating endpoint - stands in for a real Keycloak-issued token
     * without needing Keycloak itself running for these tests. */
    private static RequestPostProcessor operator() {
        return SecurityMockMvcRequestPostProcessors.jwt()
                .authorities(new SimpleGrantedAuthority("ROLE_OPERATOR"));
    }
}
