package com.swarmhq.web;

/**
 * Dashboard aggregate stats. {@code missionSuccessRatePercent} comes
 * back {@code null} rather than 0 when no mission has ever completed or
 * failed - "no data" and "0% success" are different facts.
 */
public record KpiSummary(
        long activeMissions,
        Double missionSuccessRatePercent,
        long recentAlertCount,
        long criticalBatteryDroneCount
) {
}
