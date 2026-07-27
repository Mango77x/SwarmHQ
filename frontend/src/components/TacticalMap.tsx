import { useEffect, useRef, useState } from "react";
import { Map as MapLibreMap, Marker, NavigationControl } from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";
import { fetchDrones, type Drone } from "../api/drones";

const MAP_STYLE = "https://tiles.openfreemap.org/styles/dark";
const MADRID_CENTER: [number, number] = [-3.7038, 40.4168];
const REFRESH_INTERVAL_MS = 3000;

const STATUS_COLOR: Record<Drone["status"], string> = {
  PATROLLING: "#22c55e",
  ON_MISSION: "#3b82f6",
  RETURNING: "#f59e0b",
  SIGNAL_LOST: "#ef4444",
};

function droneMarkerElement(drone: Drone): HTMLDivElement {
  const el = document.createElement("div");
  el.title = `${drone.externalId} · ${drone.status} · ${drone.batteryPercent}%`;
  el.style.width = "14px";
  el.style.height = "14px";
  el.style.borderRadius = "50%";
  el.style.border = "2px solid rgba(255,255,255,0.85)";
  el.style.boxShadow = "0 0 6px rgba(0,0,0,0.6)";
  el.style.backgroundColor = STATUS_COLOR[drone.status] ?? "#94a3b8";
  return el;
}

export default function TacticalMap() {
  const containerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<MapLibreMap | null>(null);
  const markersRef = useRef<Map<string, Marker>>(new Map());
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!containerRef.current) return;

    const map = new MapLibreMap({
      container: containerRef.current,
      style: MAP_STYLE,
      center: MADRID_CENTER,
      zoom: 13,
    });
    map.addControl(new NavigationControl(), "top-right");
    mapRef.current = map;

    return () => {
      map.remove();
      mapRef.current = null;
    };
  }, []);

  useEffect(() => {
    let cancelled = false;

    async function refresh() {
      try {
        const drones = await fetchDrones();
        if (cancelled || !mapRef.current) return;
        setError(null);

        const seen = new Set<string>();
        for (const drone of drones) {
          if (drone.lat == null || drone.lon == null) continue;
          seen.add(drone.externalId);

          const existing = markersRef.current.get(drone.externalId);
          if (existing) {
            existing.setLngLat([drone.lon, drone.lat]);
            existing.getElement().title = `${drone.externalId} · ${drone.status} · ${drone.batteryPercent}%`;
            existing.getElement().style.backgroundColor = STATUS_COLOR[drone.status] ?? "#94a3b8";
          } else {
            const marker = new Marker({ element: droneMarkerElement(drone) })
              .setLngLat([drone.lon, drone.lat])
              .addTo(mapRef.current);
            markersRef.current.set(drone.externalId, marker);
          }
        }

        for (const [externalId, marker] of markersRef.current) {
          if (!seen.has(externalId)) {
            marker.remove();
            markersRef.current.delete(externalId);
          }
        }
      } catch (err) {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : "Failed to load drones");
        }
      }
    }

    refresh();
    const interval = setInterval(refresh, REFRESH_INTERVAL_MS);
    return () => {
      cancelled = true;
      clearInterval(interval);
    };
  }, []);

  return (
    <div className="relative h-full w-full">
      <div ref={containerRef} className="h-full w-full" />
      {error && (
        <div className="absolute top-4 left-4 rounded bg-red-950/90 px-3 py-2 text-sm text-red-200 shadow-lg">
          {error}
        </div>
      )}
    </div>
  );
}
