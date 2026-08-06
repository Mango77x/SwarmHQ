package com.swarmhq.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;
import org.locationtech.jts.geom.Polygon;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * A geofenced danger area. AlertService raises ENTERED_RISK_ZONE/
 * EXITED_RISK_ZONE events when a drone's position crosses one of these.
 * The one seeded at Flyway time (V3__add_risk_zones.sql) stays in place,
 * but an operator can also declare new ones at runtime (see ZoneService).
 *
 * {@code @Audited} (hardening layer item 3): a change to a zone's name or
 * boundary is captured as a revision in {@code risk_zones_aud} - same
 * reasoning as {@link Mission}. See that class for why the
 * {@code createdBy}/{@code lastModifiedBy}/timestamp fields below are
 * {@code @NotAudited}.
 */
@Entity
@Table(name = "risk_zones")
@Audited
@EntityListeners(AuditingEntityListener.class)
public class RiskZone {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, columnDefinition = "geometry(Polygon,4326)")
    private Polygon area;

    @NotAudited
    @CreatedDate
    @Column(name = "created_at")
    private Instant createdAt;

    @NotAudited
    @CreatedBy
    @Column(name = "created_by")
    private String createdBy;

    @NotAudited
    @LastModifiedBy
    @Column(name = "last_modified_by")
    private String lastModifiedBy;

    @NotAudited
    @LastModifiedDate
    @Column(name = "last_modified_at")
    private Instant lastModifiedAt;

    protected RiskZone() {
        // JPA
    }

    public RiskZone(String name, Polygon area) {
        this.name = name;
        this.area = area;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Polygon getArea() {
        return area;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public String getLastModifiedBy() {
        return lastModifiedBy;
    }

    public Instant getLastModifiedAt() {
        return lastModifiedAt;
    }
}
