import { useEffect, useState } from "react";
import { useIsOperator } from "../auth/useIsOperator";
import { connectLiveMode, fetchMode, updateMode, type MissionAssignmentMode } from "../api/mode";

export default function ModeToggle() {
  const [mode, setMode] = useState<MissionAssignmentMode | null>(null);
  const [pending, setPending] = useState(false);
  const isOperator = useIsOperator();

  useEffect(() => {
    let cancelled = false;
    fetchMode()
      .then((state) => {
        if (!cancelled) setMode(state.mode);
      })
      .catch(() => {
        // Toggle just stays hidden - nothing else on the page depends on it.
      });

    const disconnect = connectLiveMode((state) => setMode(state.mode));
    return () => {
      cancelled = true;
      disconnect();
    };
  }, []);

  if (mode === null) return null;

  const isAuction = mode === "auction";

  function toggle() {
    const next: MissionAssignmentMode = isAuction ? "centralized" : "auction";
    setPending(true);
    updateMode(next)
      .then((state) => setMode(state.mode))
      .catch(() => {
        // The STOMP broadcast (or a future poll) corrects this tab if the
        // request silently failed - no separate error state needed here.
      })
      .finally(() => setPending(false));
  }

  return (
    <button
      type="button"
      onClick={toggle}
      disabled={pending || !isOperator}
      title={isOperator ? "Switch between centralized assignment and swarm (auction) mode" : "Requires the OPERATOR role"}
      className={`flex items-center gap-2 rounded-full border px-3 py-1 text-xs font-semibold tracking-wide uppercase transition-colors disabled:opacity-50 ${
        isAuction
          ? "border-orange-500 bg-orange-500/10 text-orange-400"
          : "border-slate-700 bg-slate-800 text-slate-300"
      }`}
    >
      <span className={`h-1.5 w-1.5 rounded-full ${isAuction ? "bg-orange-400" : "bg-slate-500"}`} />
      {isAuction ? "Swarm mode" : "Centralized mode"}
    </button>
  );
}
