package com.swarmhq.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.locationtech.jts.geom.LineString;

import java.time.Instant;

@Entity
@Table(name = "missions")
public class Mission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(columnDefinition = "geometry(LineString,4326)", nullable = false)
    private LineString route;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_drone_id")
    private Drone assignedDrone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MissionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private MissionPriority priority;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Mission() {
        // JPA
    }

    public Mission(LineString route, MissionPriority priority) {
        this.route = route;
        this.priority = priority;
        this.status = MissionStatus.PENDING;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public LineString getRoute() {
        return route;
    }

    public void setRoute(LineString route) {
        this.route = route;
    }

    public Drone getAssignedDrone() {
        return assignedDrone;
    }

    public void setAssignedDrone(Drone assignedDrone) {
        this.assignedDrone = assignedDrone;
    }

    public MissionStatus getStatus() {
        return status;
    }

    public void setStatus(MissionStatus status) {
        this.status = status;
    }

    public MissionPriority getPriority() {
        return priority;
    }

    public void setPriority(MissionPriority priority) {
        this.priority = priority;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
