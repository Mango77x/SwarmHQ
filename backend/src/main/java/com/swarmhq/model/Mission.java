package com.swarmhq.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;
import org.hibernate.envers.RelationTargetAuditMode;
import org.locationtech.jts.geom.LineString;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * {@code @Audited} (hardening layer item 3): every change to route,
 * status, priority, or assigned drone is captured as a revision in
 * {@code missions_aud}, giving a full history of a mission's lifecycle,
 * not just its current row. {@code createdBy}/{@code lastModifiedBy}/
 * {@code lastModifiedAt} (Spring Data JPA auditing, see
 * {@code com.swarmhq.config.JpaAuditingConfig}) cover "who did this last"
 * on the live row; {@code @NotAudited} on those
 * three keeps them out of the revision history itself, which only tracks
 * the domain fields above.
 */
@Entity
@Table(name = "missions")
@Audited
@EntityListeners(AuditingEntityListener.class)
public class Mission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(columnDefinition = "geometry(LineString,4326)", nullable = false)
    private LineString route;

    // Drone itself isn't @Audited (out of scope - only Mission/RiskZone
    // history was asked for), so Envers needs to be told explicitly not to
    // expect a revision history on the other side of this relation; it
    // still records which drone was assigned at each Mission revision, just
    // as a plain id rather than a tracked entity reference.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_drone_id")
    @Audited(targetAuditMode = RelationTargetAuditMode.NOT_AUDITED)
    private Drone assignedDrone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MissionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private MissionPriority priority;

    @Column(name = "created_at", nullable = false)
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
