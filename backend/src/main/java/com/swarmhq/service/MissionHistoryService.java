package com.swarmhq.service;

import com.swarmhq.model.Mission;
import com.swarmhq.repository.MissionRepository;
import com.swarmhq.web.MissionRevisionResponse;
import jakarta.persistence.EntityManager;
import org.hibernate.envers.AuditReader;
import org.hibernate.envers.AuditReaderFactory;
import org.hibernate.envers.DefaultRevisionEntity;
import org.hibernate.envers.RevisionType;
import org.hibernate.envers.query.AuditEntity;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

/**
 * Reads {@code Mission}'s Hibernate Envers revision history (hardening
 * layer item 3) - the append-only counterpart to {@link MissionService},
 * which only ever exposes a mission's current row.
 */
@Service
public class MissionHistoryService {

    private final MissionRepository missionRepository;
    private final EntityManager entityManager;

    public MissionHistoryService(MissionRepository missionRepository, EntityManager entityManager) {
        this.missionRepository = missionRepository;
        this.entityManager = entityManager;
    }

    /**
     * Empty (not 404) for a mission that exists but predates this feature
     * shipping - Envers only ever tracks changes made after it's enabled,
     * never retroactively. 404 is reserved for a mission id that was never
     * real to begin with, same as {@code MissionService.cancel}.
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<MissionRevisionResponse> history(Long missionId) {
        if (!missionRepository.existsById(missionId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Mission " + missionId + " not found");
        }

        AuditReader reader = AuditReaderFactory.get(entityManager);
        List<Object[]> revisions = reader.createQuery()
                .forRevisionsOfEntity(Mission.class, false, true)
                .add(AuditEntity.id().eq(missionId))
                .addOrder(AuditEntity.revisionNumber().asc())
                .getResultList();

        return revisions.stream().map(this::toResponse).toList();
    }

    private MissionRevisionResponse toResponse(Object[] row) {
        Mission mission = (Mission) row[0];
        DefaultRevisionEntity revisionEntity = (DefaultRevisionEntity) row[1];
        RevisionType revisionType = (RevisionType) row[2];
        String assignedDroneExternalId = mission.getAssignedDrone() != null
                ? mission.getAssignedDrone().getExternalId()
                : null;
        return new MissionRevisionResponse(
                revisionEntity.getId(),
                Instant.ofEpochMilli(revisionEntity.getTimestamp()),
                revisionType.name(),
                mission.getStatus(),
                mission.getPriority(),
                assignedDroneExternalId);
    }
}
