import type { MissionPriority } from "../api/missions";

const PRIORITIES: MissionPriority[] = ["LOW", "MEDIUM", "HIGH"];

interface Props {
  active: boolean;
  pointsCaptured: number;
  priority: MissionPriority;
  sending: boolean;
  isOperator: boolean;
  onToggle: () => void;
  onPriorityChange: (priority: MissionPriority) => void;
  onConfirm: () => void;
  onCancel: () => void;
}

/**
 * The map-click capture itself lives in TacticalMap (it owns the MapLibre
 * instance) - this is just the toggle button plus the confirm/cancel panel
 * that appears once both route points are down, same
 * presentational/stateless split as AlertsPanel vs. the map that feeds it.
 */
export default function MissionDispatchControl({
  active,
  pointsCaptured,
  priority,
  sending,
  isOperator,
  onToggle,
  onPriorityChange,
  onConfirm,
  onCancel,
}: Props) {
  return (
    <div className="absolute top-4 left-4 flex flex-col items-start gap-2">
      <button
        type="button"
        onClick={onToggle}
        disabled={!isOperator}
        title={isOperator ? "Click two points on the map to dispatch a mission along that route" : "Requires the OPERATOR role"}
        className={`rounded-full border px-3 py-1 text-xs font-semibold tracking-wide uppercase transition-colors disabled:opacity-50 ${
          active
            ? "border-blue-500 bg-blue-500/10 text-blue-400"
            : "border-slate-700 bg-slate-950/80 text-slate-300"
        }`}
      >
        {active ? "Cancel dispatch" : "Dispatch mission"}
      </button>

      {active && pointsCaptured < 2 && (
        <div className="rounded bg-slate-950/80 px-3 py-1.5 text-xs text-slate-300 shadow-lg">
          Click the map: {pointsCaptured === 0 ? "start point" : "end point"}
        </div>
      )}

      {active && pointsCaptured === 2 && (
        <div className="flex items-center gap-2 rounded bg-slate-950/80 px-3 py-2 shadow-lg">
          <select
            value={priority}
            onChange={(e) => onPriorityChange(e.target.value as MissionPriority)}
            className="rounded border border-slate-700 bg-slate-900 px-2 py-1 text-xs text-slate-200"
          >
            {PRIORITIES.map((p) => (
              <option key={p} value={p}>
                {p}
              </option>
            ))}
          </select>
          <button
            type="button"
            onClick={onConfirm}
            disabled={sending}
            className="rounded bg-blue-600 px-2 py-1 text-xs font-semibold text-white disabled:opacity-50"
          >
            Dispatch
          </button>
          <button
            type="button"
            onClick={onCancel}
            disabled={sending}
            className="rounded border border-slate-700 px-2 py-1 text-xs text-slate-300 disabled:opacity-50"
          >
            Discard
          </button>
        </div>
      )}
    </div>
  );
}
