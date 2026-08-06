import type { Mission } from "../api/missions";

interface Props {
  mission: Pick<Mission, "id" | "priority" | "status" | "assignedDroneExternalId">;
  cancelling: boolean;
  onCancel: () => void;
  onClose: () => void;
}

/**
 * Shows up when a mission's route is clicked on the map (see TacticalMap's
 * click handler on the missions layer) - same presentational/stateless
 * split as MissionDispatchControl, with the map owning selection state.
 */
export default function MissionActionPanel({ mission, cancelling, onCancel, onClose }: Props) {
  const canCancel = mission.status === "PENDING" || mission.status === "ACTIVE";

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
    </div>
  );
}
