package com.swarmhq.repository;

import com.swarmhq.model.Event;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

public interface EventRepository extends JpaRepository<Event, Long> {

    // JOIN FETCH so EventResponse.from() (called outside any Hibernate
    // session, in the REST path) doesn't lazy-load Event.drone per row.
    @Query("SELECT e FROM Event e JOIN FETCH e.drone ORDER BY e.occurredAt DESC")
    List<Event> findRecent(Pageable pageable);

    long countByOccurredAtAfter(Instant threshold);
}
