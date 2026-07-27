package com.swarmhq.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Checks the zone Flyway-seeds (V3__add_risk_zones.sql, "Sector 1 Perimeter
 * Risk Zone") comes back over the REST endpoint - no test-created data
 * needed since defining zones isn't a use case yet (RiskZoneRepository has
 * no write path other than the migration).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class ZoneControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void listsSeededRiskZoneWithItsRing() throws Exception {
        mockMvc.perform(get("/api/zones"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$[0].name").value("Sector 1 Perimeter Risk Zone"))
                // Closed ring: 4 corners + the repeated first point.
                .andExpect(jsonPath("$[0].ring.length()").value(5));
    }
}
