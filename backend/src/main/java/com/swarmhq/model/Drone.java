package com.swarmhq.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.locationtech.jts.geom.Point;

import java.time.Instant;

@Entity
@Table(name = "drones")
public class Drone {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String type;

    @Column(columnDefinition = "geometry(Point,4326)")
    private Point position;

    @Column(name = "battery_percent", nullable = false)
    private int batteryPercent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DroneStatus status;

    @Column(name = "last_update_at", nullable = false)
    private Instant lastUpdateAt;

    protected Drone() {
        // JPA
    }

    public Drone(String type, DroneStatus status, int batteryPercent) {
        this.type = type;
        this.status = status;
        this.batteryPercent = batteryPercent;
        this.lastUpdateAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Point getPosition() {
        return position;
    }

    public void setPosition(Point position) {
        this.position = position;
    }

    public int getBatteryPercent() {
        return batteryPercent;
    }

    public void setBatteryPercent(int batteryPercent) {
        this.batteryPercent = batteryPercent;
    }

    public DroneStatus getStatus() {
        return status;
    }

    public void setStatus(DroneStatus status) {
        this.status = status;
    }

    public Instant getLastUpdateAt() {
        return lastUpdateAt;
    }

    public void setLastUpdateAt(Instant lastUpdateAt) {
        this.lastUpdateAt = lastUpdateAt;
    }
}
