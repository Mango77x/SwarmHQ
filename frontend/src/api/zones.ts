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

// ring doesn't need to already be closed - the backend appends the first
// point again if the caller didn't, so this can be exactly the points an
// operator clicked on the map.
export async function createZone(name: string, ring: [number, number][]): Promise<Zone> {
  const response = await fetch("/api/zones", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ name, ring }),
  });
  if (!response.ok) {
    throw new Error(`POST /api/zones failed: ${response.status}`);
  }
  return response.json();
}
