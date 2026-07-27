-- external_id is the stable business identifier used in MQTT topics
-- (drones/{externalId}/telemetry) to correlate incoming telemetry with a
-- Drone row. The table is guaranteed empty at this point in the project
-- (no simulator or ingestion existed before this sprint), so a direct
-- NOT NULL addition is safe here.
ALTER TABLE drones ADD COLUMN external_id VARCHAR(50) NOT NULL;
ALTER TABLE drones ADD CONSTRAINT uq_drones_external_id UNIQUE (external_id);
