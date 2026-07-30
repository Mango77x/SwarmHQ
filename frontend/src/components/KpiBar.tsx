import { useEffect, useState } from "react";
import { fetchKpis, type KpiSummary } from "../api/kpis";

// Aggregate stats don't need push-on-every-write freshness the way
// drone positions/alerts do. Polling every few seconds is plenty for a
// dashboard, and far simpler than broadcasting a full recompute on
// every mission/event/telemetry write.
const POLL_INTERVAL_MS = 5000;

function Tile({ label, value, alert }: { label: string; value: string; alert?: boolean }) {
  return (
    <div className="flex flex-col leading-tight">
      <span className={`text-sm font-semibold tabular-nums ${alert ? "text-red-400" : "text-slate-100"}`}>
        {value}
      </span>
      <span className="text-[10px] tracking-wide text-slate-500 uppercase">{label}</span>
    </div>
  );
}

export default function KpiBar() {
  const [kpis, setKpis] = useState<KpiSummary | null>(null);

  useEffect(() => {
    let cancelled = false;

    function refresh() {
      fetchKpis()
        .then((summary) => {
          if (!cancelled) setKpis(summary);
        })
        .catch(() => {
          // Non-critical strip - the map/drones/alerts still work without it.
        });
    }

    refresh();
    const interval = setInterval(refresh, POLL_INTERVAL_MS);
    return () => {
      cancelled = true;
      clearInterval(interval);
    };
  }, []);

  if (!kpis) return null;

  return (
    <div className="ml-auto flex items-center gap-5">
      <Tile label="Active missions" value={String(kpis.activeMissions)} />
      <Tile
        label="Success rate"
        value={kpis.missionSuccessRatePercent == null ? "N/A" : `${kpis.missionSuccessRatePercent.toFixed(0)}%`}
      />
      <Tile label="Alerts (1h)" value={String(kpis.recentAlertCount)} alert={kpis.recentAlertCount > 0} />
      <Tile
        label="Critical battery"
        value={String(kpis.criticalBatteryDroneCount)}
        alert={kpis.criticalBatteryDroneCount > 0}
      />
    </div>
  );
}
