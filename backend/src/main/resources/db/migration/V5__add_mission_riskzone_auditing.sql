-- Mission/RiskZone revision history (hardening layer item 3): who
-- touched a mission or zone last (Spring Data JPA auditing), plus a full
-- change history via Hibernate Envers. This schema was derived
-- empirically - started the app once against a throwaway database with
-- ddl-auto=update and Envers enabled, then transcribed exactly what
-- Hibernate generated, rather than hand-guessing Envers' expected table
-- shapes under this project's own ddl-auto=validate policy.

ALTER TABLE missions ADD COLUMN created_by VARCHAR(255);
ALTER TABLE missions ADD COLUMN last_modified_by VARCHAR(255);
ALTER TABLE missions ADD COLUMN last_modified_at TIMESTAMPTZ;

ALTER TABLE risk_zones ADD COLUMN created_at TIMESTAMPTZ;
ALTER TABLE risk_zones ADD COLUMN created_by VARCHAR(255);
ALTER TABLE risk_zones ADD COLUMN last_modified_by VARCHAR(255);
ALTER TABLE risk_zones ADD COLUMN last_modified_at TIMESTAMPTZ;

-- Envers' default revision entity. INCREMENT BY 50 matches Hibernate's own
-- default JPA @SequenceGenerator allocationSize (hi/lo batching) - not
-- just cosmetic, a mismatch here would let the ORM's in-memory id
-- allocation drift out of step with what's actually free in the sequence.
CREATE SEQUENCE revinfo_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE revinfo (
    rev      INTEGER NOT NULL PRIMARY KEY,
    revtstmp BIGINT
);

-- One row per revision of a tracked Mission, not the current-state columns
-- above (those stay on the live `missions` table). Nullable throughout
-- (besides id/rev) since a DELETE revision (revtype=2) doesn't carry the
-- deleted row's field values by default. assigned_drone_id is a plain
-- column, not a foreign key, since Drone itself isn't audited - a
-- historic revision has to survive that drone later being deleted.
CREATE TABLE missions_aud (
    id                BIGINT NOT NULL,
    rev               INTEGER NOT NULL REFERENCES revinfo (rev),
    revtype           SMALLINT,
    created_at        TIMESTAMPTZ,
    priority          VARCHAR(10),
    route             GEOMETRY(LineString, 4326),
    status            VARCHAR(20),
    assigned_drone_id BIGINT,
    PRIMARY KEY (rev, id)
);

-- Same shape as missions_aud above, for RiskZone.
CREATE TABLE risk_zones_aud (
    id      BIGINT NOT NULL,
    rev     INTEGER NOT NULL REFERENCES revinfo (rev),
    revtype SMALLINT,
    area    GEOMETRY(Polygon, 4326),
    name    VARCHAR(100),
    PRIMARY KEY (rev, id)
);
