package com.swarmhq.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The first test checks the zone Flyway-seeds (V3__add_risk_zones.sql,
 * "Sector 1 Perimeter Risk Zone") comes back over the REST endpoint; the
 * rest exercise the runtime write path an operator uses to declare a new
 * zone from the map (ZoneService.create).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class ZoneControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void listsSeededRiskZoneWithItsRing() throws Exception {
        mockMvc.perform(get("/api/zones"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$[0].name").value("Sector 1 Perimeter Risk Zone"))
                // Closed ring: 4 corners + the repeated first point.
                .andExpect(jsonPath("$[0].ring.length()").value(5));
    }

    @Test
    void createsAZoneFromAnOpenRingAndClosesItAutomatically() throws Exception {
        CreateZoneRequest request = new CreateZoneRequest("Test No-Fly Area", List.of(
                new double[] {-3.72, 40.40},
                new double[] {-3.71, 40.40},
                new double[] {-3.71, 40.41}));

        mockMvc.perform(post("/api/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Test No-Fly Area"))
                // 3 clicked points + the auto-appended closing point.
                .andExpect(jsonPath("$.ring.length()").value(4));
    }

    @Test
    void creatingAZoneWithFewerThanThreePointsIsRejected() throws Exception {
        CreateZoneRequest request = new CreateZoneRequest("Too Small", List.of(
                new double[] {-3.72, 40.40},
                new double[] {-3.71, 40.40}));

        mockMvc.perform(post("/api/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void creatingAZoneFromAnAlreadyClosedThreePointRingIsRejected() throws Exception {
        // 2 distinct vertices with the first repeated last - passes the
        // >= 3 points check but isn't a real polygon once closing is
        // skipped (it's already "closed"). ZoneService.create should
        // catch this itself rather than let JTS throw an unchecked
        // IllegalArgumentException that'd surface as a raw 500.
        CreateZoneRequest request = new CreateZoneRequest("Degenerate", List.of(
                new double[] {-3.72, 40.40},
                new double[] {-3.71, 40.40},
                new double[] {-3.72, 40.40}));

        mockMvc.perform(post("/api/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isBadRequest());
    }
}
