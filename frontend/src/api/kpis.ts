export interface KpiSummary {
  activeMissions: number;
  /** null, not 0, when no mission has ever completed or failed yet. */
  missionSuccessRatePercent: number | null;
  recentAlertCount: number;
  criticalBatteryDroneCount: number;
}

export async function fetchKpis(): Promise<KpiSummary> {
  const response = await fetch("/api/kpis");
  if (!response.ok) {
    throw new Error(`GET /api/kpis failed: ${response.status}`);
  }
  return response.json();
}
