package com.swarmhq.web;

import com.swarmhq.model.Drone;
import com.swarmhq.model.DroneStatus;
import com.swarmhq.repository.DroneRepository;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Uses MockMvc rather than a live server on purpose: it drives the
 * DispatcherServlet in-memory with no real socket, so it isn't affected by
 * the local Tomcat/NIO issue documented in HELP.md.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class DroneControllerTests {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DroneRepository droneRepository;

    @Test
    void listsDronesWithLastKnownPosition() throws Exception {
        Drone drone = new Drone("controller-test-drone", "quadcopter", DroneStatus.PATROLLING, 55);
        drone.setPosition(GEOMETRY_FACTORY.createPoint(new Coordinate(-3.7038, 40.4168)));
        droneRepository.saveAndFlush(drone);

        mockMvc.perform(get("/api/drones"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$[?(@.externalId == 'controller-test-drone')].lat").value(40.4168))
                .andExpect(jsonPath("$[?(@.externalId == 'controller-test-drone')].lon").value(-3.7038))
                .andExpect(jsonPath("$[?(@.externalId == 'controller-test-drone')].batteryPercent").value(55))
                .andExpect(jsonPath("$[?(@.externalId == 'controller-test-drone')].status").value("PATROLLING"));
    }
}
