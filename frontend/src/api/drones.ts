import { apiFetch } from "./http";

export type DroneStatus = "PATROLLING" | "ON_MISSION" | "RETURNING" | "SIGNAL_LOST";

export interface Drone {
  externalId: string;
  type: string;
  lat: number | null;
  lon: number | null;
  batteryPercent: number;
  status: DroneStatus;
  lastUpdateAt: string;
}

export async function fetchDrones(): Promise<Drone[]> {
  const response = await apiFetch("/api/drones");
  if (!response.ok) {
    throw new Error(`GET /api/drones failed: ${response.status}`);
  }
  return response.json();
}
