import { useEffect, useRef, useState } from "react";
import { Map as MapLibreMap, Marker, NavigationControl, setWorkerUrl } from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";
import { fetchDrones, type Drone } from "../api/drones";
import { connectLiveDrones } from "../api/liveDrones";
import { fetchZones } from "../api/zones";
import AlertsPanel from "./AlertsPanel";

// Vite doesn't detect maplibre-gl's internal worker construction and
// never bundles maplibre-gl-worker.mjs as its own asset, so tiles never
// load (the map renders, but stays an empty background) unless we point
// at a copy served as a plain static file. scripts/copy-maplibre-worker.mjs
// places that copy - and the maplibre-gl-shared.mjs it imports - into
// public/ before dev/build.
setWorkerUrl(`${import.meta.env.BASE_URL}maplibre-gl-worker.mjs`);

const MAP_STYLE = "https://tiles.openfreemap.org/styles/dark";
const MADRID_CENTER: [number, number] = [-3.7038, 40.4168];
const RISK_ZONES_SOURCE_ID = "risk-zones";

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
  const [live, setLive] = useState(false);

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

    map.on("load", () => {
      fetchZones()
        .then((zones) => {
          if (!mapRef.current) return;
          mapRef.current.addSource(RISK_ZONES_SOURCE_ID, {
            type: "geojson",
            data: {
              type: "FeatureCollection",
              features: zones.map((zone) => ({
                type: "Feature",
                properties: { name: zone.name },
                geometry: { type: "Polygon", coordinates: [zone.ring] },
              })),
            },
          });
          mapRef.current.addLayer({
            id: `${RISK_ZONES_SOURCE_ID}-fill`,
            type: "fill",
            source: RISK_ZONES_SOURCE_ID,
            paint: { "fill-color": "#ef4444", "fill-opacity": 0.15 },
          });
          mapRef.current.addLayer({
            id: `${RISK_ZONES_SOURCE_ID}-outline`,
            type: "line",
            source: RISK_ZONES_SOURCE_ID,
            paint: { "line-color": "#ef4444", "line-width": 1.5, "line-opacity": 0.8 },
          });
        })
        .catch(() => {
          // Non-critical overlay - the map/drones still work without it.
        });
    });

    return () => {
      map.remove();
      mapRef.current = null;
    };
  }, []);

  useEffect(() => {
    function upsertMarker(drone: Drone) {
      if (drone.lat == null || drone.lon == null || !mapRef.current) return;

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

    let cancelled = false;

    // REST gives the baseline (drones that reported before this page loaded);
    // the STOMP subscription below then keeps it live - no more polling.
    fetchDrones()
      .then((drones) => {
        if (cancelled) return;
        setError(null);
        drones.forEach(upsertMarker);
      })
      .catch((err) => {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : "Failed to load drones");
        }
      });

    const disconnect = connectLiveDrones(
      (drone) => {
        if (cancelled) return;
        setError(null);
        upsertMarker(drone);
      },
      (connected) => {
        if (!cancelled) setLive(connected);
      },
    );

    return () => {
      cancelled = true;
      disconnect();
    };
  }, []);

  return (
    <div className="relative h-full w-full">
      <div ref={containerRef} className="h-full w-full" />
      <AlertsPanel />
      <div
        className="absolute bottom-4 left-4 flex items-center gap-1.5 rounded bg-slate-950/80 px-2 py-1 text-xs text-slate-300 shadow-lg"
        title={live ? "Live updates connected" : "Live updates disconnected - reconnecting…"}
      >
        <span
          className={`h-1.5 w-1.5 rounded-full ${live ? "bg-green-500" : "bg-slate-500"}`}
        />
        {live ? "live" : "connecting…"}
      </div>
      {error && (
        <div className="absolute top-4 left-4 rounded bg-red-950/90 px-3 py-2 text-sm text-red-200 shadow-lg">
          {error}
        </div>
      )}
    </div>
  );
}
