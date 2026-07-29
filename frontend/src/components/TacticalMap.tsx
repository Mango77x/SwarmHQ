import { useEffect, useRef, useState } from "react";
import type { GeoJSONSource, MapMouseEvent } from "maplibre-gl";
import { Map as MapLibreMap, Marker, NavigationControl, setWorkerUrl } from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";
import { fetchDrones, type Drone } from "../api/drones";
import { connectLiveDrones } from "../api/liveDrones";
import { createMission, fetchMissions, type Mission, type MissionPriority } from "../api/missions";
import { fetchZones } from "../api/zones";
import AlertsPanel from "./AlertsPanel";
import MissionDispatchControl from "./MissionDispatchControl";

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
const MISSIONS_SOURCE_ID = "missions";
const PENDING_MISSION_SOURCE_ID = "pending-mission";
// Same cadence as KpiBar - aggregate/overlay data, not something that
// needs push-on-every-write freshness the way drone positions do.
const MISSIONS_POLL_INTERVAL_MS = 5000;

const STATUS_COLOR: Record<Drone["status"], string> = {
  PATROLLING: "#22c55e",
  ON_MISSION: "#3b82f6",
  RETURNING: "#f59e0b",
  SIGNAL_LOST: "#ef4444",
};

// Only PENDING/ACTIVE render - a live tactical map showing current orders,
// not a full mission history (that's a future mission-list panel's job).
const MISSION_STATUS_COLOR: Record<"PENDING" | "ACTIVE", string> = {
  PENDING: "#f59e0b",
  ACTIVE: "#3b82f6",
};

// Not typed as GeoJSON.FeatureCollection - tsconfig's explicit "types"
// list doesn't include @types/geojson's ambient global namespace, same as
// every other GeoJSON literal already in this file (see the zones source
// above); maplibre-gl's own setData() still structurally accepts this.
function missionsToGeoJson(missions: Mission[]) {
  return {
    type: "FeatureCollection" as const,
    features: missions
      .filter((mission): mission is Mission & { status: "PENDING" | "ACTIVE" } =>
        mission.status === "PENDING" || mission.status === "ACTIVE",
      )
      .map((mission) => ({
        type: "Feature" as const,
        properties: { status: mission.status, color: MISSION_STATUS_COLOR[mission.status] },
        geometry: { type: "LineString" as const, coordinates: mission.route },
      })),
  };
}

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
  const mapLoadedRef = useRef(false);
  const [error, setError] = useState<string | null>(null);
  const [live, setLive] = useState(false);

  // Mission dispatch (map-click capture): a stable click handler is
  // registered once below, alongside the map itself, so it reads the
  // latest values through these refs rather than closing over stale state.
  const [dispatching, setDispatching] = useState(false);
  const [pendingPoints, setPendingPoints] = useState<[number, number][]>([]);
  const [pendingPriority, setPendingPriority] = useState<MissionPriority>("MEDIUM");
  const [sending, setSending] = useState(false);
  const dispatchingRef = useRef(dispatching);
  const pendingPointsRef = useRef(pendingPoints);
  dispatchingRef.current = dispatching;
  pendingPointsRef.current = pendingPoints;

  function refreshMissions() {
    if (!mapRef.current || !mapLoadedRef.current) return;
    fetchMissions()
      .then((missions) => {
        const source = mapRef.current?.getSource(MISSIONS_SOURCE_ID);
        if (source && "setData" in source) {
          (source as GeoJSONSource).setData(missionsToGeoJson(missions));
        }
      })
      .catch(() => {
        // Non-critical overlay - the map/drones still work without it.
      });
  }

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

    map.on("click", (event: MapMouseEvent) => {
      if (!dispatchingRef.current || pendingPointsRef.current.length >= 2) return;
      setPendingPoints((current) => [...current, [event.lngLat.lng, event.lngLat.lat]]);
    });

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

      mapRef.current?.addSource(MISSIONS_SOURCE_ID, {
        type: "geojson",
        data: { type: "FeatureCollection", features: [] },
      });
      mapRef.current?.addLayer({
        id: MISSIONS_SOURCE_ID,
        type: "line",
        source: MISSIONS_SOURCE_ID,
        paint: {
          "line-color": ["get", "color"],
          "line-width": 3,
          "line-dasharray": ["case", ["==", ["get", "status"], "PENDING"], ["literal", [2, 1.5]], ["literal", [1, 0]]],
        },
      });

      mapRef.current?.addSource(PENDING_MISSION_SOURCE_ID, {
        type: "geojson",
        data: { type: "FeatureCollection", features: [] },
      });
      mapRef.current?.addLayer({
        id: PENDING_MISSION_SOURCE_ID,
        type: "line",
        source: PENDING_MISSION_SOURCE_ID,
        paint: { "line-color": "#38bdf8", "line-width": 2, "line-dasharray": [1, 1] },
      });

      mapLoadedRef.current = true;
      refreshMissions();
    });

    return () => {
      map.remove();
      mapRef.current = null;
      mapLoadedRef.current = false;
    };
  }, []);

  useEffect(() => {
    const interval = setInterval(refreshMissions, MISSIONS_POLL_INTERVAL_MS);
    return () => clearInterval(interval);
  }, []);

  // Draws the in-progress route (0, 1, or 2 points captured so far) as a
  // separate preview source/layer - kept distinct from the missions layer
  // above since this route doesn't exist as a Mission until confirmed.
  useEffect(() => {
    const source = mapRef.current?.getSource(PENDING_MISSION_SOURCE_ID);
    if (!source || !("setData" in source)) return;
    const features =
      pendingPoints.length === 2
        ? [{ type: "Feature" as const, properties: {}, geometry: { type: "LineString" as const, coordinates: pendingPoints } }]
        : [];
    (source as GeoJSONSource).setData({ type: "FeatureCollection", features });
  }, [pendingPoints]);

  function toggleDispatch() {
    setDispatching((current) => !current);
    setPendingPoints([]);
  }

  function confirmDispatch() {
    if (pendingPoints.length !== 2) return;
    setSending(true);
    createMission(pendingPoints, pendingPriority)
      .then(() => {
        setDispatching(false);
        setPendingPoints([]);
        setError(null);
        refreshMissions();
      })
      .catch((err) => {
        setError(err instanceof Error ? err.message : "Failed to dispatch mission");
      })
      .finally(() => setSending(false));
  }

  function discardDispatch() {
    setPendingPoints([]);
  }

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
      <MissionDispatchControl
        active={dispatching}
        pointsCaptured={pendingPoints.length}
        priority={pendingPriority}
        sending={sending}
        onToggle={toggleDispatch}
        onPriorityChange={setPendingPriority}
        onConfirm={confirmDispatch}
        onCancel={discardDispatch}
      />
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
        <div className="absolute top-16 left-4 rounded bg-red-950/90 px-3 py-2 text-sm text-red-200 shadow-lg">
          {error}
        </div>
      )}
    </div>
  );
}
