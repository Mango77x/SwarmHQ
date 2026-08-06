package com.swarmhq.repository;

import com.swarmhq.model.Drone;
import com.swarmhq.model.Mission;
import com.swarmhq.model.MissionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface MissionRepository extends JpaRepository<Mission, Long> {

    long countByStatus(MissionStatus status);

    // AlertService's geofence auto-recall: does this drone (which just
    // entered a RiskZone) have a mission to recall it from at all? A
    // PATROLLING drone has none - only the passive ENTERED_RISK_ZONE alert
    // applies to it. JOIN FETCH (not left) for the same reason as
    // findByIdWithDrone: an ACTIVE mission always has an assignedDrone, and
    // MissionCancelPublisher needs it outside this method's own
    // transaction - without eagerly fetching it here, that access would
    // hit a closed-session LazyInitializationException instead.
    @Query("SELECT m FROM Mission m JOIN FETCH m.assignedDrone WHERE m.assignedDrone = :drone AND m.status = :status")
    Optional<Mission> findByAssignedDroneAndStatus(@Param("drone") Drone drone, @Param("status") MissionStatus status);

    // LEFT (not inner) JOIN FETCH: PENDING missions have no assignedDrone
    // yet. Avoids a lazy-load per row from MissionResponse.from() in the
    // REST path (same reasoning as EventRepository.findRecent).
    @Query("SELECT m FROM Mission m LEFT JOIN FETCH m.assignedDrone ORDER BY m.createdAt DESC")
    List<Mission> findAllWithDrone();

    // Ordering by MissionPriority here would sort alphabetically by the
    // stored enum string ("HIGH" < "LOW" < "MEDIUM") instead of actual
    // priority, so MissionAssignmentService sorts these itself instead.
    List<Mission> findByStatusOrderByCreatedAtAsc(MissionStatus status);

    // Inner (not left) JOIN FETCH: only used by MissionStatusListener,
    // where the mission necessarily already has an assigned drone (it's
    // reporting completion/failure of an assignment). Fetching it eagerly
    // avoids a LazyInitializationException once the entity outlives this
    // repository call's own transaction.
    @Query("SELECT m FROM Mission m JOIN FETCH m.assignedDrone WHERE m.id = :id")
    Optional<Mission> findByIdWithDrone(@Param("id") Long id);

    // LEFT (not inner) JOIN FETCH, unlike findByIdWithDrone above: used by
    // MissionService.cancel(), which has to handle a PENDING mission (no
    // assignedDrone yet) as well as an ACTIVE one, so an inner join would
    // silently exclude the PENDING case entirely instead of just fetching
    // a null drone for it.
    @Query("SELECT m FROM Mission m LEFT JOIN FETCH m.assignedDrone WHERE m.id = :id")
    Optional<Mission> findByIdWithOptionalDrone(@Param("id") Long id);

    // A single atomic UPDATE ... WHERE status = 'ACTIVE', rather than a
    // read-then-write check in Java. MissionStatusListener's old "already
    // COMPLETED/FAILED, ignore the redelivery" guard was racy under
    // genuinely concurrent QoS1 redeliveries: two deliveries could both
    // read ACTIVE before either write committed, so both proceeded and
    // both raised an Event. Postgres' row lock on the UPDATE fixes that -
    // only one caller ever gets rowsAffected == 1 for the same mission.
    // @Transactional is required here because Spring Data's default
    // transactional advice for repository methods is read-only, and a
    // @Modifying query can't run under that ("No active transaction for
    // update or delete query" without this). Plain save()/deleteById()
    // calls elsewhere in this codebase work fine without it, since those
    // go through SimpleJpaRepository's own non-read-only @Transactional
    // CRUD methods, which a custom interface method like this one
    // doesn't inherit.
    @Transactional
    @Modifying
    @Query("UPDATE Mission m SET m.status = :newStatus WHERE m.id = :id AND m.status = com.swarmhq.model.MissionStatus.ACTIVE")
    int updateStatusIfActive(@Param("id") Long id, @Param("newStatus") MissionStatus newStatus);

    // Same atomic single-UPDATE idiom as updateStatusIfActive above, for
    // the mirror problem on the PENDING side: MissionService.cancel() and
    // .assignManually() both need to act on a PENDING mission without
    // racing MissionAssignmentService's/AuctionCoordinatorService's own
    // scheduled passes, which claim a PENDING mission the same way. A
    // plain find-then-save (what both methods did before this) reads a
    // detached Mission and merges it back wholesale on save() - if a
    // scheduler assigns the same mission in between, that merge silently
    // clobbers the assignment it just made. This closes that window the
    // same way updateStatusIfActive already closes it on the other side:
    // Postgres' row lock on the UPDATE, not application code, decides who
    // wins.
    // clearAutomatically = true matters here in a way it doesn't for
    // updateStatusIfActive above: both callers immediately re-read the
    // same mission by id right after this update, in the same request
    // (and therefore, under Spring's default open-in-view, the same
    // persistence context/first-level cache for the whole request). A
    // bulk @Modifying UPDATE writes straight to the database without
    // touching that cache, so without this flag the very next find-by-id
    // in the same request would silently hand back the pre-update entity
    // - a real bug this caught in MissionControllerTests, not just a
    // theoretical one.
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Mission m SET m.status = :newStatus WHERE m.id = :id AND m.status = com.swarmhq.model.MissionStatus.PENDING")
    int updateStatusIfPending(@Param("id") Long id, @Param("newStatus") MissionStatus newStatus);
}
