export type MissionPriority = "LOW" | "MEDIUM" | "HIGH";
export type MissionStatus = "PENDING" | "ACTIVE" | "COMPLETED" | "FAILED" | "CANCELLED";

export interface Mission {
  id: number;
  /** [lon, lat] pairs, same order as every other geometry-bearing DTO. */
  route: [number, number][];
  assignedDroneExternalId: string | null;
  status: MissionStatus;
  priority: MissionPriority;
  createdAt: string;
}

export async function fetchMissions(): Promise<Mission[]> {
  const response = await fetch("/api/missions");
  if (!response.ok) {
    throw new Error(`GET /api/missions failed: ${response.status}`);
  }
  return response.json();
}

export async function createMission(route: [number, number][], priority: MissionPriority): Promise<Mission> {
  const response = await fetch("/api/missions", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ route, priority }),
  });
  if (!response.ok) {
    throw new Error(`POST /api/missions failed: ${response.status}`);
  }
  return response.json();
}

// A PENDING mission cancels immediately; an ACTIVE one recalls its drone
// to base but the response still reports it as ACTIVE - it only flips to
// CANCELLED once the drone's own status report comes back, same
// eventual-consistency the backend already uses for COMPLETED/FAILED.
export async function cancelMission(id: number): Promise<Mission> {
  const response = await fetch(`/api/missions/${id}/cancel`, { method: "POST" });
  if (!response.ok) {
    throw new Error(`POST /api/missions/${id}/cancel failed: ${response.status}`);
  }
  return response.json();
}

// Force-assigns a PENDING mission to a specific drone, bypassing whichever
// assignment engine (centralized or auction) is currently active. Only
// PENDING missions and PATROLLING drones are accepted by the backend.
export async function assignMission(id: number, droneExternalId: string): Promise<Mission> {
  const response = await fetch(`/api/missions/${id}/assign`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ droneExternalId }),
  });
  if (!response.ok) {
    throw new Error(`POST /api/missions/${id}/assign failed: ${response.status}`);
  }
  return response.json();
}
