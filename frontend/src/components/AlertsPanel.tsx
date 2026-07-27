import { useEffect, useState } from "react";
import { connectLiveEvents, fetchEvents, type DroneEvent, type EventType } from "../api/events";

const MAX_VISIBLE = 8;

const TYPE_COLOR: Record<EventType, string> = {
  LOW_BATTERY: "#f59e0b",
  WAYPOINT_REACHED: "#94a3b8",
  SIGNAL_LOST: "#ef4444",
  SIGNAL_RECOVERED: "#22c55e",
  STATUS_CHANGE: "#3b82f6",
  ENTERED_RISK_ZONE: "#ef4444",
  EXITED_RISK_ZONE: "#22c55e",
};

function formatTime(occurredAt: string): string {
  return new Date(occurredAt).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });
}

export default function AlertsPanel() {
  const [events, setEvents] = useState<DroneEvent[]>([]);

  useEffect(() => {
    let cancelled = false;

    // fetchEvents() already comes back newest-first (EventService.listRecent);
    // connectLiveEvents() then prepends anything raised after this loaded.
    fetchEvents()
      .then((initial) => {
        if (!cancelled) setEvents(initial.slice(0, MAX_VISIBLE));
      })
      .catch(() => {
        // Non-critical panel - the map/drones still work without it, so
        // failing to load recent alerts doesn't need its own error banner.
      });

    const disconnect = connectLiveEvents((event) => {
      if (cancelled) return;
      setEvents((current) => [event, ...current].slice(0, MAX_VISIBLE));
    });

    return () => {
      cancelled = true;
      disconnect();
    };
  }, []);

  if (events.length === 0) return null;

  return (
    // top-24 rather than top-4: clears MapLibre's NavigationControl
    // (zoom/compass buttons), also anchored top-right.
    <div className="absolute top-24 right-4 w-64 rounded bg-slate-950/80 shadow-lg">
      <div className="border-b border-slate-800 px-3 py-1.5 text-xs font-semibold tracking-wide text-slate-400">
        RECENT ALERTS
      </div>
      <ul className="max-h-64 overflow-y-auto">
        {events.map((event, index) => (
          <li
            key={`${event.droneExternalId}-${event.occurredAt}-${index}`}
            className="flex items-start gap-2 px-3 py-1.5 text-xs text-slate-300"
          >
            <span
              className="mt-1 h-1.5 w-1.5 shrink-0 rounded-full"
              style={{ backgroundColor: TYPE_COLOR[event.type] ?? "#94a3b8" }}
            />
            <span className="min-w-0 flex-1">
              <span className="block truncate text-slate-100">{event.droneExternalId}</span>
              <span className="block truncate text-slate-400">{event.detail}</span>
            </span>
            <span className="shrink-0 text-slate-500">{formatTime(event.occurredAt)}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}
