-- Placed a short distance from drone-1/drone-2's patrol squares
-- (simulator/routes.py bases: (40.4168,-3.7038) and (40.4268,-3.6938),
-- side 0.006 deg) so MissionAssignmentService has a nearby eligible
-- drone to assign these to on a normal simulator run, same "demonstrable
-- out of the box" reasoning as V3's seeded risk zone.
INSERT INTO missions (route, status, priority, created_at) VALUES (
    ST_GeomFromText('LINESTRING(-3.6960 40.4230, -3.6920 40.4260)', 4326),
    'PENDING',
    'HIGH',
    now()
);

INSERT INTO missions (route, status, priority, created_at) VALUES (
    ST_GeomFromText('LINESTRING(-3.6800 40.4390, -3.6760 40.4420)', 4326),
    'PENDING',
    'LOW',
    now()
);
