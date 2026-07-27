package com.swarmhq.service;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Not a @SpringBootTest - KalmanFilterService has no dependencies to
 * inject, so a plain instance is enough and this stays fast.
 */
class KalmanFilterServiceTests {

    @Test
    void firstReadingForANewDroneIsReturnedUnmodified() {
        KalmanFilterService service = new KalmanFilterService();

        Coordinate result = service.smooth("kfs-test-drone", -3.7038, 40.4168, Instant.now());

        assertEquals(-3.7038, result.x);
        assertEquals(40.4168, result.y);
    }

    @Test
    void secondReadingIsBlendedNotPassedThroughVerbatim() {
        KalmanFilterService service = new KalmanFilterService();
        Instant first = Instant.now();

        service.smooth("kfs-test-drone", -3.7038, 40.4168, first);
        Coordinate second = service.smooth("kfs-test-drone", -3.71, 40.42, first.plusSeconds(2));

        assertNotEquals(-3.71, second.x);
        assertNotEquals(40.42, second.y);
    }

    @Test
    void tracksEachDroneIndependently() {
        KalmanFilterService service = new KalmanFilterService();
        Instant now = Instant.now();

        Coordinate a = service.smooth("kfs-drone-a", 0.0, 0.0, now);
        Coordinate b = service.smooth("kfs-drone-b", 10.0, 10.0, now);

        assertEquals(0.0, a.x);
        assertEquals(10.0, b.x);
    }
}
