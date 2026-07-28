package com.swarmhq.repository;

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

    // LEFT (not inner) JOIN FETCH: PENDING missions have no assignedDrone
    // yet. Avoids a lazy-load per row from MissionResponse.from() in the
    // REST path (same reasoning as EventRepository.findRecent).
    @Query("SELECT m FROM Mission m LEFT JOIN FETCH m.assignedDrone ORDER BY m.createdAt DESC")
    List<Mission> findAllWithDrone();

    // Ordering by MissionPriority here would sort alphabetically by the
    // stored enum string ("HIGH" < "LOW" < "MEDIUM"), not by actual
    // priority - MissionAssignmentService sorts these itself instead.
    List<Mission> findByStatusOrderByCreatedAtAsc(MissionStatus status);

    // Inner (not left) JOIN FETCH: only used by MissionStatusListener,
    // where the mission necessarily already has an assigned drone (it's
    // reporting completion/failure of an assignment). Fetching it eagerly
    // avoids a LazyInitializationException once the entity outlives this
    // repository call's own transaction.
    @Query("SELECT m FROM Mission m JOIN FETCH m.assignedDrone WHERE m.id = :id")
    Optional<Mission> findByIdWithDrone(@Param("id") Long id);

    // Single atomic UPDATE ... WHERE status = 'ACTIVE' instead of a
    // read-then-write check in Java: MissionStatusListener's own
    // "already COMPLETED/FAILED, ignore the redelivery" guard was racy
    // under genuinely concurrent QoS1 redeliveries (two deliveries can
    // both read ACTIVE before either write commits, so both proceed and
    // both raise an Event). Postgres' row lock on the UPDATE means only
    // one caller ever gets rowsAffected == 1 for the same mission.
    // @Transactional required here: Spring Data's own default transactional
    // advice for repository methods is read-only, which a @Modifying query
    // can't run under - "No active transaction for update or delete query"
    // without this, even though plain save()/deleteById() calls elsewhere
    // in this codebase work fine without it (those go through
    // SimpleJpaRepository's own non-read-only @Transactional CRUD methods,
    // which a custom interface method like this one doesn't inherit).
    @Transactional
    @Modifying
    @Query("UPDATE Mission m SET m.status = :newStatus WHERE m.id = :id AND m.status = com.swarmhq.model.MissionStatus.ACTIVE")
    int updateStatusIfActive(@Param("id") Long id, @Param("newStatus") MissionStatus newStatus);
}
