package com.swarmhq.web;

/**
 * Dashboard aggregate stats (Sprint 9). {@code missionSuccessRatePercent}
 * is {@code null} rather than 0 when no mission has ever completed or
 * failed yet - "no data" and "0% success" are different facts, and this
 * project has no mission-assignment path yet (a post-MVP differentiation
 * layer) so that's the expected state today, not a bug.
 */
public record KpiSummary(
        long activeMissions,
        Double missionSuccessRatePercent,
        long recentAlertCount,
        long criticalBatteryDroneCount
) {
}
