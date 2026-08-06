package com.swarmhq.web;

import com.swarmhq.service.MissionAssignmentModeHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link MissionAssignmentModeHolder} is a real process-wide singleton
 * shared by every test class in this context (no {@code @TestPropertySource}
 * here - see MissionBidListenerTests for why that matters), so every test
 * below resets it back to CENTRALIZED afterward regardless of outcome -
 * leaving it on AUCTION would make AuctionCoordinatorService's real
 * {@code @Scheduled} tick start actually running for every test class that
 * shares this context afterward.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class MissionAssignmentModeControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MissionAssignmentModeHolder modeHolder;

    @AfterEach
    void resetMode() {
        modeHolder.set(MissionAssignmentModeHolder.Mode.CENTRALIZED);
    }

    @Test
    void getReturnsTheCurrentMode() throws Exception {
        mockMvc.perform(get("/api/mode"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("centralized"));
    }

    @Test
    void putSwitchesToAuction() throws Exception {
        mockMvc.perform(put("/api/mode")
                        .with(operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"auction\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("auction"));

        assertEquals(MissionAssignmentModeHolder.Mode.AUCTION, modeHolder.get());
    }

    @Test
    void putRejectsAnUnknownMode() throws Exception {
        mockMvc.perform(put("/api/mode")
                        .with(operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"bogus\"}"))
                .andExpect(status().isBadRequest());

        assertEquals(MissionAssignmentModeHolder.Mode.CENTRALIZED, modeHolder.get());
    }

    private static RequestPostProcessor operator() {
        return SecurityMockMvcRequestPostProcessors.jwt()
                .authorities(new SimpleGrantedAuthority("ROLE_OPERATOR"));
    }
}
