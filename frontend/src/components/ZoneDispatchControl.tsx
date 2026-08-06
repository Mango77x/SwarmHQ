interface Props {
  active: boolean;
  pointsCaptured: number;
  name: string;
  sending: boolean;
  onToggle: () => void;
  onNameChange: (name: string) => void;
  onConfirm: () => void;
  onCancel: () => void;
}

const MIN_POINTS = 3;

/**
 * The map-click capture itself lives in TacticalMap (it owns the MapLibre
 * instance) - this is just the toggle button plus the name/confirm/cancel
 * panel, same presentational/stateless split as MissionDispatchControl.
 * Unlike a mission's fixed 2-point route, a zone's point count is open-
 * ended - the confirm panel shows once the minimum is met but clicking
 * keeps adding corners.
 */
export default function ZoneDispatchControl({
  active,
  pointsCaptured,
  name,
  sending,
  onToggle,
  onNameChange,
  onConfirm,
  onCancel,
}: Props) {
  return (
    <div className="absolute top-4 left-40 flex flex-col items-start gap-2">
      <button
        type="button"
        onClick={onToggle}
        title="Click points on the map to outline a new restricted zone"
        className={`rounded-full border px-3 py-1 text-xs font-semibold tracking-wide uppercase transition-colors ${
          active
            ? "border-orange-500 bg-orange-500/10 text-orange-400"
            : "border-slate-700 bg-slate-950/80 text-slate-300"
        }`}
      >
        {active ? "Cancel zone" : "Draw zone"}
      </button>

      {active && pointsCaptured < MIN_POINTS && (
        <div className="rounded bg-slate-950/80 px-3 py-1.5 text-xs text-slate-300 shadow-lg">
          Click the map to add corners ({pointsCaptured}/{MIN_POINTS} minimum)
        </div>
      )}

      {active && pointsCaptured >= MIN_POINTS && (
        <div className="flex items-center gap-2 rounded bg-slate-950/80 px-3 py-2 shadow-lg">
          <input
            type="text"
            value={name}
            onChange={(e) => onNameChange(e.target.value)}
            placeholder="Zone name"
            className="w-32 rounded border border-slate-700 bg-slate-900 px-2 py-1 text-xs text-slate-200"
          />
          <button
            type="button"
            onClick={onConfirm}
            disabled={sending || name.trim().length === 0}
            className="rounded bg-orange-600 px-2 py-1 text-xs font-semibold text-white disabled:opacity-50"
          >
            Declare
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
