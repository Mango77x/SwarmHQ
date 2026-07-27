package com.swarmhq.web;

import com.swarmhq.model.Drone;
import com.swarmhq.model.DroneStatus;
import com.swarmhq.model.Event;
import com.swarmhq.model.EventType;
import com.swarmhq.repository.DroneRepository;
import com.swarmhq.repository.EventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class EventControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DroneRepository droneRepository;

    @Autowired
    private EventRepository eventRepository;

    @Test
    void listsRecentEventsNewestFirst() throws Exception {
        Drone drone = new Drone("event-controller-test-drone", "quadcopter", DroneStatus.PATROLLING, 55);
        droneRepository.saveAndFlush(drone);
        eventRepository.saveAndFlush(new Event(drone, EventType.LOW_BATTERY, "Battery dropped to 15%"));

        mockMvc.perform(get("/api/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.droneExternalId == 'event-controller-test-drone')].type").value("LOW_BATTERY"))
                .andExpect(jsonPath("$[?(@.droneExternalId == 'event-controller-test-drone')].detail").value("Battery dropped to 15%"));
    }
}
