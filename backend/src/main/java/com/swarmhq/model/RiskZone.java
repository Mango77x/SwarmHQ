package com.swarmhq.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.locationtech.jts.geom.Polygon;

/**
 * A geofenced danger area. AlertService raises ENTERED_RISK_ZONE/
 * EXITED_RISK_ZONE events when a drone's position crosses one of these.
 * Static for now, seeded by Flyway - there's no CRUD API yet since
 * defining new zones at runtime isn't needed.
 */
@Entity
@Table(name = "risk_zones")
public class RiskZone {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, columnDefinition = "geometry(Polygon,4326)")
    private Polygon area;

    protected RiskZone() {
        // JPA
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
}
