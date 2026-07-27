package com.swarmhq.service;

import com.swarmhq.repository.EventRepository;
import com.swarmhq.web.EventResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class EventService {

    /** Enough for a "recent alerts" panel without unbounded growth. */
    private static final int RECENT_LIMIT = 50;

    private final EventRepository eventRepository;

    public EventService(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    public List<EventResponse> listRecent() {
        return eventRepository.findRecent(PageRequest.of(0, RECENT_LIMIT)).stream()
                .map(EventResponse::from)
                .toList();
    }
}
