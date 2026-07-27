package com.swarmhq.web;

import com.swarmhq.model.Mission;
import com.swarmhq.model.MissionPriority;
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
}
