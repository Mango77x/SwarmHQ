package com.swarmhq.repository;

import com.swarmhq.model.Mission;
import com.swarmhq.model.MissionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MissionRepository extends JpaRepository<Mission, Long> {

    long countByStatus(MissionStatus status);
}
