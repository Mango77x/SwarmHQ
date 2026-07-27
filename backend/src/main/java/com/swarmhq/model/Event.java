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

import java.time.Instant;

/**
 * Append-only audit log entry. Never updated or deleted once written -
 * same convention as MOLS's Movement log.
 */
@Entity
@Table(name = "events")
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "drone_id", nullable = false)
    private Drone drone;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mission_id")
    private Mission mission;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private EventType type;

    @Column(length = 500)
    private String detail;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected Event() {
        // JPA
    }

    public Event(Drone drone, EventType type, String detail) {
        this.drone = drone;
        this.type = type;
        this.detail = detail;
        this.occurredAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Drone getDrone() {
        return drone;
    }

    public Mission getMission() {
        return mission;
    }

    public void setMission(Mission mission) {
        this.mission = mission;
    }

    public EventType getType() {
        return type;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
