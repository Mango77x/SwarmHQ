import { useEffect, useRef, useState } from "react";
import type { GeoJSONSource, MapMouseEvent } from "maplibre-gl";
import { Map as MapLibreMap, Marker, NavigationControl, setWorkerUrl } from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";
import { useIsOperator } from "../auth/useIsOperator";
import { fetchDrones, type Drone } from "../api/drones";
import { connectLiveDrones } from "../api/liveDrones";
import { assignMission, cancelMission, createMission, fetchMissions, type Mission, type MissionPriority } from "../api/missions";
import { createZone, fetchZones, type Zone } from "../api/zones";
import AlertsPanel from "./AlertsPanel";
import MissionActionPanel from "./MissionActionPanel";
import MissionDispatchControl from "./MissionDispatchControl";
import ZoneDispatchControl from "./ZoneDispatchControl";

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
const ZONE_DRAFT_SOURCE_ID = "zone-draft";
const ZONE_DRAFT_POINTS_SOURCE_ID = "zone-draft-points";
// Same cadence as KpiBar - this is overlay data that doesn't need
// push-on-every-write freshness the way drone positions do.
const MISSIONS_POLL_INTERVAL_MS = 5000;

const STATUS_COLOR: Record<Drone["status"], string> = {
  PATROLLING: "#22c55e",
  ON_MISSION: "#3b82f6",
  RETURNING: "#f59e0b",
  SIGNAL_LOST: "#ef4444",
};

// Only PENDING/ACTIVE render here - this is a live tactical map showing
// current orders. A full mission history would be a future mission-list
// panel's job.
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
        properties: { id: mission.id, status: mission.status, color: MISSION_STATUS_COLOR[mission.status] },
        geometry: { type: "LineString" as const, coordinates: mission.route },
      })),
  };
}

function zonesToGeoJson(zones: Zone[]) {
  return {
    type: "FeatureCollection" as const,
    features: zones.map((zone) => ({
      type: "Feature" as const,
      properties: { name: zone.name },
      geometry: { type: "Polygon" as const, coordinates: [zone.ring] },
    })),
  };
}

// The in-progress corners of a not-yet-declared zone: an open path below
// the 3-point minimum (just visual feedback for what's been clicked so
// far), a closed polygon preview once there's enough to actually form one.
function zoneDraftToGeoJson(points: [number, number][]) {
  if (points.length < 3) {
    return {
      type: "FeatureCollection" as const,
      features: points.length < 2 ? [] : [
        { type: "Feature" as const, properties: {}, geometry: { type: "LineString" as const, coordinates: points } },
      ],
    };
  }
  return {
    type: "FeatureCollection" as const,
    features: [
      { type: "Feature" as const, properties: {}, geometry: { type: "Polygon" as const, coordinates: [[...points, points[0]]] } },
    ],
  };
}

// One dot per corner already placed - the line/polygon preview above gives
// no feedback at all for a single click (it only draws once 2+ points
// exist), which read as unresponsive. A dot appears the instant a corner
// lands, whether or not it's part of a line yet.
function zoneDraftPointsToGeoJson(points: [number, number][]) {
  return {
    type: "FeatureCollection" as const,
    features: points.map((point) => ({
      type: "Feature" as const,
      properties: {},
      geometry: { type: "Point" as const, coordinates: point },
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
  const isOperator = useIsOperator();
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

  // Mission selection (click a route on the map to manage it): the latest
  // fetched list lives in a ref so the map's click handler - registered
  // once, like the dispatch one above - can look a clicked feature's id up
  // without closing over stale state.
  const missionsRef = useRef<Mission[]>([]);
  const [selectedMission, setSelectedMission] = useState<Mission | null>(null);
  const selectedMissionIdRef = useRef<number | null>(null);
  const [cancelling, setCancelling] = useState(false);
  const [availableDrones, setAvailableDrones] = useState<string[]>([]);
  const [assigning, setAssigning] = useState(false);
  selectedMissionIdRef.current = selectedMission?.id ?? null;

  // Zone drawing (map-click capture, open-ended point count instead of a
  // mission's fixed 2): same ref-mirroring pattern as mission dispatch, so
  // the map's click handler reads current values without closing over
  // stale state.
  const [zoneDrawing, setZoneDrawing] = useState(false);
  const [zonePoints, setZonePoints] = useState<[number, number][]>([]);
  const [zoneName, setZoneName] = useState("");
  const [zoneSending, setZoneSending] = useState(false);
  const zoneDrawingRef = useRef(zoneDrawing);
  const zonePointsRef = useRef(zonePoints);
  zoneDrawingRef.current = zoneDrawing;
  zonePointsRef.current = zonePoints;

  function refreshZones() {
    if (!mapRef.current || !mapLoadedRef.current) return;
    fetchZones()
      .then((zones) => {
        const source = mapRef.current?.getSource(RISK_ZONES_SOURCE_ID);
        if (source && "setData" in source) {
          (source as GeoJSONSource).setData(zonesToGeoJson(zones));
        }
      })
      .catch(() => {
        // Non-critical overlay - the map/drones still work without it.
      });
  }

  function refreshMissions() {
    if (!mapRef.current || !mapLoadedRef.current) return;
    fetchMissions()
      .then((missions) => {
        missionsRef.current = missions;
        const source = mapRef.current?.getSource(MISSIONS_SOURCE_ID);
        if (source && "setData" in source) {
          (source as GeoJSONSource).setData(missionsToGeoJson(missions));
        }
        // Keeps an open panel in sync with the poll instead of showing a
        // stale status - clears it if the mission dropped out of the
        // PENDING/ACTIVE set entirely (e.g. a cancel was just confirmed).
        if (selectedMissionIdRef.current != null) {
          setSelectedMission(missions.find((m) => m.id === selectedMissionIdRef.current) ?? null);
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
      const point: [number, number] = [event.lngLat.lng, event.lngLat.lat];
      if (dispatchingRef.current) {
        if (pendingPointsRef.current.length < 2) setPendingPoints((current) => [...current, point]);
        return;
      }
      if (zoneDrawingRef.current) {
        setZonePoints((current) => [...current, point]);
      }
    });

    map.on("load", () => {
      mapRef.current?.addSource(RISK_ZONES_SOURCE_ID, {
        type: "geojson",
        data: { type: "FeatureCollection", features: [] },
      });
      mapRef.current?.addLayer({
        id: `${RISK_ZONES_SOURCE_ID}-fill`,
        type: "fill",
        source: RISK_ZONES_SOURCE_ID,
        paint: { "fill-color": "#ef4444", "fill-opacity": 0.15 },
      });
      mapRef.current?.addLayer({
        id: `${RISK_ZONES_SOURCE_ID}-outline`,
        type: "line",
        source: RISK_ZONES_SOURCE_ID,
        paint: { "line-color": "#ef4444", "line-width": 1.5, "line-opacity": 0.8 },
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
      // Selecting a mission (to cancel/recall it) rather than starting a
      // new dispatch - ignored while actively placing a dispatch route or
      // drawing a zone so the click-driven flows can't fight over the
      // same click.
      mapRef.current?.on("click", MISSIONS_SOURCE_ID, (event) => {
        if (dispatchingRef.current || zoneDrawingRef.current) return;
        const id = event.features?.[0]?.properties?.id;
        if (typeof id !== "number") return;
        selectMission(missionsRef.current.find((m) => m.id === id) ?? null);
      });
      mapRef.current?.on("mouseenter", MISSIONS_SOURCE_ID, () => {
        if (mapRef.current) mapRef.current.getCanvas().style.cursor = "pointer";
      });
      mapRef.current?.on("mouseleave", MISSIONS_SOURCE_ID, () => {
        if (mapRef.current) mapRef.current.getCanvas().style.cursor = "";
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

      mapRef.current?.addSource(ZONE_DRAFT_SOURCE_ID, {
        type: "geojson",
        data: { type: "FeatureCollection", features: [] },
      });
      mapRef.current?.addLayer({
        id: `${ZONE_DRAFT_SOURCE_ID}-fill`,
        type: "fill",
        source: ZONE_DRAFT_SOURCE_ID,
        paint: { "fill-color": "#f97316", "fill-opacity": 0.2 },
      });
      mapRef.current?.addLayer({
        id: `${ZONE_DRAFT_SOURCE_ID}-outline`,
        type: "line",
        source: ZONE_DRAFT_SOURCE_ID,
        paint: { "line-color": "#f97316", "line-width": 2, "line-dasharray": [1, 1] },
      });

      mapRef.current?.addSource(ZONE_DRAFT_POINTS_SOURCE_ID, {
        type: "geojson",
        data: { type: "FeatureCollection", features: [] },
      });
      mapRef.current?.addLayer({
        id: ZONE_DRAFT_POINTS_SOURCE_ID,
        type: "circle",
        source: ZONE_DRAFT_POINTS_SOURCE_ID,
        paint: { "circle-radius": 4, "circle-color": "#f97316", "circle-stroke-width": 1.5, "circle-stroke-color": "#fff7ed" },
      });

      mapLoadedRef.current = true;
      refreshMissions();
      refreshZones();
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

  // Same idea as the pending-mission preview above, for a not-yet-declared
  // zone's corners - plus a dot per corner (zoneDraftPointsToGeoJson) so a
  // single click gives immediate feedback instead of nothing happening
  // until a second one lands.
  useEffect(() => {
    const lineSource = mapRef.current?.getSource(ZONE_DRAFT_SOURCE_ID);
    if (lineSource && "setData" in lineSource) {
      (lineSource as GeoJSONSource).setData(zoneDraftToGeoJson(zonePoints));
    }
    const pointsSource = mapRef.current?.getSource(ZONE_DRAFT_POINTS_SOURCE_ID);
    if (pointsSource && "setData" in pointsSource) {
      (pointsSource as GeoJSONSource).setData(zoneDraftPointsToGeoJson(zonePoints));
    }
  }, [zonePoints]);

  // A plain click can easily register a pixel or two of movement (mouse
  // jitter, a trackpad tap), which MapLibre then treats as a drag-pan
  // instead of a click - the map nudges instead of placing the point, and
  // a double-click while placing two corners close together zooms instead
  // of adding both. Both compete with click-to-place while either capture
  // mode is on, so they're suspended for exactly that window and restored
  // the moment neither is (toggle off, confirm, or discard all flow
  // through these two booleans going false).
  useEffect(() => {
    if (!mapRef.current) return;
    if (dispatching || zoneDrawing) {
      mapRef.current.dragPan.disable();
      mapRef.current.doubleClickZoom.disable();
    } else {
      mapRef.current.dragPan.enable();
      mapRef.current.doubleClickZoom.enable();
    }
  }, [dispatching, zoneDrawing]);

  function toggleDispatch() {
    setDispatching((current) => !current);
    setPendingPoints([]);
    // Mutually exclusive with zone drawing - both capture raw map clicks,
    // and the click handler only ever checks dispatchingRef first, so
    // leaving zone-drawing on here would silently eat every click as a
    // dispatch point while ZoneDispatchControl sat stuck showing 0 corners.
    setZoneDrawing(false);
    setZonePoints([]);
    setZoneName("");
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

  function toggleZoneDrawing() {
    setZoneDrawing((current) => !current);
    setZonePoints([]);
    setZoneName("");
    // Reciprocal of toggleDispatch's guard above.
    setDispatching(false);
    setPendingPoints([]);
  }

  function confirmZone() {
    if (zonePoints.length < 3) return;
    setZoneSending(true);
    createZone(zoneName.trim(), zonePoints)
      .then(() => {
        setZoneDrawing(false);
        setZonePoints([]);
        setZoneName("");
        setError(null);
        refreshZones();
      })
      .catch((err) => {
        setError(err instanceof Error ? err.message : "Failed to declare zone");
      })
      .finally(() => setZoneSending(false));
  }

  function discardZone() {
    setZonePoints([]);
    setZoneName("");
  }

  // Manual assignment only applies to a PENDING mission, and the drone
  // list needs to be current at the moment the operator is picking one -
  // fetched fresh right when the panel opens on one, rather than kept
  // polled continuously alongside it.
  useEffect(() => {
    if (selectedMission?.status !== "PENDING") {
      setAvailableDrones([]);
      return;
    }
    let cancelled = false;
    fetchDrones()
      .then((drones) => {
        if (!cancelled) {
          setAvailableDrones(drones.filter((d) => d.status === "PATROLLING").map((d) => d.externalId));
        }
      })
      .catch(() => {
        // Non-critical - the panel just shows "no drone available".
      });
    return () => {
      cancelled = true;
    };
  }, [selectedMission?.id, selectedMission?.status]);

  // Selects a different mission (or clears the selection) - distinct from
  // refreshMissions' own setSelectedMission call, which re-syncs the
  // *same* mission's fresh data on each poll tick and must never reset
  // these, or it'd clobber a legitimately in-flight cancel/assign's own
  // spinner state every 5s.
  function selectMission(mission: Mission | null) {
    setSelectedMission(mission);
    setCancelling(false);
    setAssigning(false);
  }

  function assignSelectedMission(droneExternalId: string) {
    if (!selectedMission) return;
    const missionId = selectedMission.id;
    setAssigning(true);
    assignMission(missionId, droneExternalId)
      .then((updated) => {
        setError(null);
        refreshMissions();
        // Only apply the result if the operator is still looking at this
        // same mission - otherwise this stale response would silently
        // overwrite whatever they've since selected instead, or reopen a
        // panel they explicitly closed.
        if (selectedMissionIdRef.current === missionId) {
          setSelectedMission(updated);
        }
      })
      .catch((err) => {
        if (selectedMissionIdRef.current === missionId) {
          setError(err instanceof Error ? err.message : "Failed to assign mission");
        }
      })
      .finally(() => {
        if (selectedMissionIdRef.current === missionId) setAssigning(false);
      });
  }

  function cancelSelectedMission() {
    if (!selectedMission) return;
    const missionId = selectedMission.id;
    setCancelling(true);
    cancelMission(missionId)
      .then((updated) => {
        setError(null);
        refreshMissions();
        if (selectedMissionIdRef.current === missionId) {
          setSelectedMission(updated);
        }
      })
      .catch((err) => {
        if (selectedMissionIdRef.current === missionId) {
          setError(err instanceof Error ? err.message : "Failed to cancel mission");
        }
      })
      .finally(() => {
        if (selectedMissionIdRef.current === missionId) setCancelling(false);
      });
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
        isOperator={isOperator}
        onToggle={toggleDispatch}
        onPriorityChange={setPendingPriority}
        onConfirm={confirmDispatch}
        onCancel={discardDispatch}
      />
      <ZoneDispatchControl
        active={zoneDrawing}
        pointsCaptured={zonePoints.length}
        name={zoneName}
        sending={zoneSending}
        isOperator={isOperator}
        onToggle={toggleZoneDrawing}
        onNameChange={setZoneName}
        onConfirm={confirmZone}
        onCancel={discardZone}
      />
      {selectedMission && (
        <MissionActionPanel
          mission={selectedMission}
          cancelling={cancelling}
          onCancel={cancelSelectedMission}
          onClose={() => selectMission(null)}
          availableDrones={availableDrones}
          assigning={assigning}
          onAssign={assignSelectedMission}
          isOperator={isOperator}
        />
      )}
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
