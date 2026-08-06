import { useEffect, useState } from "react";
import { fetchMissionHistory } from "../api/missions";
import type { Mission, MissionRevision } from "../api/missions";

interface Props {
  mission: Pick<Mission, "id" | "priority" | "status" | "assignedDroneExternalId">;
  cancelling: boolean;
  onCancel: () => void;
  onClose: () => void;
  /** Externalids of currently-PATROLLING drones - only meaningful (and only
   * shown) while mission.status is PENDING, the only state manual
   * assignment applies to. */
  availableDrones: string[];
  assigning: boolean;
  onAssign: (droneExternalId: string) => void;
  isOperator: boolean;
}

/**
 * Shows up when a mission's route is clicked on the map (see TacticalMap's
 * click handler on the missions layer) - same presentational/stateless
 * split as MissionDispatchControl, with the map owning selection state.
 */
export default function MissionActionPanel({
  mission,
  cancelling,
  onCancel,
  onClose,
  availableDrones,
  assigning,
  onAssign,
  isOperator,
}: Props) {
  const canCancel = isOperator && (mission.status === "PENDING" || mission.status === "ACTIVE");
  const canManuallyAssign = isOperator && mission.status === "PENDING";
  const [pickedDrone, setPickedDrone] = useState("");
  const [showHistory, setShowHistory] = useState(false);
  const [history, setHistory] = useState<MissionRevision[] | null>(null);

  // Fetched lazily (only once the operator actually asks to see it, not on
  // every panel open) - the hardening layer's audit trail is a secondary,
  // occasionally-needed view, not something worth a request on every click.
  useEffect(() => {
    if (!showHistory) {
      return;
    }
    let cancelled = false;
    fetchMissionHistory(mission.id)
      .then((revisions) => {
        if (!cancelled) {
          setHistory(revisions);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setHistory([]);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [showHistory, mission.id]);

  return (
    <div className="absolute bottom-16 left-4 w-64 rounded bg-slate-950/80 shadow-lg">
      <div className="flex items-center justify-between border-b border-slate-800 px-3 py-1.5">
        <span className="text-xs font-semibold tracking-wide text-slate-400">MISSION #{mission.id}</span>
        <button type="button" onClick={onClose} className="text-slate-500 hover:text-slate-300" title="Close">
          ✕
        </button>
      </div>
      <div className="space-y-1 px-3 py-2 text-xs text-slate-300">
        <div>
          Priority: <span className="text-slate-100">{mission.priority}</span>
        </div>
        <div>
          Status: <span className="text-slate-100">{mission.status}</span>
        </div>
        {mission.assignedDroneExternalId && (
          <div>
            Drone: <span className="text-slate-100">{mission.assignedDroneExternalId}</span>
          </div>
        )}
      </div>
      {canManuallyAssign && (
        <div className="space-y-1.5 border-t border-slate-800 px-3 py-2">
          <select
            value={pickedDrone}
            onChange={(event) => setPickedDrone(event.target.value)}
            disabled={assigning || availableDrones.length === 0}
            className="w-full rounded bg-slate-900 px-2 py-1 text-xs text-slate-100 disabled:opacity-50"
          >
            <option value="" disabled>
              {availableDrones.length === 0 ? "No drone available" : "Choose a drone…"}
            </option>
            {availableDrones.map((externalId) => (
              <option key={externalId} value={externalId}>
                {externalId}
              </option>
            ))}
          </select>
          <button
            type="button"
            onClick={() => onAssign(pickedDrone)}
            disabled={assigning || !pickedDrone}
            title="Force-assigns this mission to the chosen drone, bypassing the automatic engine"
            className="w-full rounded bg-blue-600 px-2 py-1 text-xs font-semibold text-white disabled:opacity-50"
          >
            Assign
          </button>
        </div>
      )}
      {canCancel && (
        <div className="border-t border-slate-800 px-3 py-2">
          <button
            type="button"
            onClick={onCancel}
            disabled={cancelling}
            title={mission.status === "ACTIVE" ? "Recalls the assigned drone to base" : "Cancels the order"}
            className="w-full rounded bg-red-600 px-2 py-1 text-xs font-semibold text-white disabled:opacity-50"
          >
            {mission.status === "ACTIVE" ? "Cancel & recall drone" : "Cancel mission"}
          </button>
        </div>
      )}
      <div className="border-t border-slate-800 px-3 py-2">
        <button
          type="button"
          onClick={() => setShowHistory((current) => !current)}
          className="text-xs font-semibold text-slate-400 hover:text-slate-200"
        >
          {showHistory ? "▾" : "▸"} History
        </button>
        {showHistory && (
          <div className="mt-1.5 max-h-32 space-y-1 overflow-y-auto text-xs text-slate-400">
            {history === null && <div>Loading…</div>}
            {history !== null && history.length === 0 && <div>No revisions yet.</div>}
            {history?.map((revision) => (
              <div key={revision.revision} className="border-l-2 border-slate-700 pl-2">
                <div className="text-slate-300">
                  {revision.revisionType} · {revision.status ?? "—"}
                </div>
                <div>{new Date(revision.occurredAt).toLocaleString()}</div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
