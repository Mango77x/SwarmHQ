package com.swarmhq.repository;

import com.swarmhq.model.Mission;
import com.swarmhq.model.MissionPriority;
import com.swarmhq.model.MissionStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proves {@code updateStatusIfActive} is the atomic guard
 * MissionStatusListener needs: exactly one caller ever "wins" the
 * transition for a given mission, no matter how many times it's called -
 * the scenario a genuinely concurrent QoS1 redelivery hits (see HELP.md
 * Troubleshooting).
 */
@SpringBootTest
@Transactional
class MissionRepositoryTests {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);

    @Autowired
    private MissionRepository missionRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void updateStatusIfActiveWinsOnceThenNoOpsOnEveryRepeat() {
        Mission mission = new Mission(
                GEOMETRY_FACTORY.createLineString(new Coordinate[] {
                        new Coordinate(10.0, 10.0),
                        new Coordinate(10.01, 10.01)
                }),
                MissionPriority.MEDIUM);
        mission.setStatus(MissionStatus.ACTIVE);
        mission = missionRepository.saveAndFlush(mission);

        int firstAttempt = missionRepository.updateStatusIfActive(mission.getId(), MissionStatus.COMPLETED);
        int redeliveredAttempt = missionRepository.updateStatusIfActive(mission.getId(), MissionStatus.COMPLETED);

        assertEquals(1, firstAttempt);
        assertEquals(0, redeliveredAttempt);

        // A bulk JPQL UPDATE bypasses the persistence context - without
        // clearing it, findById below would return the same L1-cached
        // Mission instance saveAndFlush() returned above, still showing
        // the pre-update ACTIVE status despite the row having genuinely
        // changed underneath it.
        entityManager.clear();
        assertEquals(MissionStatus.COMPLETED, missionRepository.findById(mission.getId()).orElseThrow().getStatus());
    }
}
