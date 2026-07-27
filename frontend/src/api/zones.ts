export interface Zone {
  name: string;
  /** Exterior ring, [lon, lat] pairs, closed (first point repeated last). */
  ring: [number, number][];
}

export async function fetchZones(): Promise<Zone[]> {
  const response = await fetch("/api/zones");
  if (!response.ok) {
    throw new Error(`GET /api/zones failed: ${response.status}`);
  }
  return response.json();
}
