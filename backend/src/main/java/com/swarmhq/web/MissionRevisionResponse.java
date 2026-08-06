package com.swarmhq.web;

import com.swarmhq.model.MissionPriority;
import com.swarmhq.model.MissionStatus;

import java.time.Instant;

/**
 * One entry in a Mission's audit trail (hardening layer item 3) - a
 * Hibernate Envers revision, not a live row. {@code status}/{@code priority}/
 * {@code assignedDroneExternalId} are null for a DELETE revision (Envers'
 * default {@code store_data_at_delete=false}); irrelevant in practice since
 * nothing in this app ever deletes a Mission.
 */
public record MissionRevisionResponse(
        int revision,
        Instant occurredAt,
        String revisionType,
        MissionStatus status,
        MissionPriority priority,
        String assignedDroneExternalId
) {
}
