package com.swarmhq.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class KpiControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void returnsAggregateCounts() throws Exception {
        mockMvc.perform(get("/api/kpis"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeMissions").exists())
                .andExpect(jsonPath("$.recentAlertCount").exists())
                .andExpect(jsonPath("$.criticalBatteryDroneCount").exists());
    }
}
