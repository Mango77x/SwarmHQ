package com.swarmhq.web;

import com.swarmhq.model.Event;
import com.swarmhq.model.EventType;

import java.time.Instant;

/**
 * An alert/audit entry for the frontend - same "don't expose the internal
 * id, externalId is the public identifier" convention as DroneResponse.
 */
public record EventResponse(
        String droneExternalId,
        EventType type,
        String detail,
        Instant occurredAt
) {
    public static EventResponse from(Event event) {
        return new EventResponse(
                event.getDrone().getExternalId(),
                event.getType(),
                event.getDetail(),
                event.getOccurredAt());
    }
}
