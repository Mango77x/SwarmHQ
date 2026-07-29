export type MissionPriority = "LOW" | "MEDIUM" | "HIGH";
export type MissionStatus = "PENDING" | "ACTIVE" | "COMPLETED" | "FAILED";

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
