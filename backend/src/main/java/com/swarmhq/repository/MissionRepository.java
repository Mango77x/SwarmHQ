package com.swarmhq.repository;

import com.swarmhq.model.Mission;
import com.swarmhq.model.MissionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
