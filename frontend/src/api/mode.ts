import { Client } from "@stomp/stompjs";
import SockJS from "sockjs-client";
import { apiFetch, getAccessToken } from "./http";

export type MissionAssignmentMode = "centralized" | "auction";

export interface ModeState {
  mode: MissionAssignmentMode;
}

const MODE_UPDATES_TOPIC = "/topic/mode";

export async function fetchMode(): Promise<ModeState> {
  const response = await apiFetch("/api/mode");
  if (!response.ok) {
    throw new Error(`GET /api/mode failed: ${response.status}`);
  }
  return response.json();
}

export async function updateMode(mode: MissionAssignmentMode): Promise<ModeState> {
  const response = await apiFetch("/api/mode", {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ mode }),
  });
  if (!response.ok) {
    throw new Error(`PUT /api/mode failed: ${response.status}`);
  }
  return response.json();
}

/**
 * Live counterpart to fetchMode() - same pattern as api/events.ts and
 * api/liveDrones.ts. Matters here specifically because the mode is
 * process-wide, not per-tab: if it's flipped from another browser tab (or
 * changes for any other reason), this tab's toggle should reflect that
 * instead of silently going stale.
 */
export function connectLiveMode(onModeChange: (state: ModeState) => void): () => void {
  const client = new Client({
    webSocketFactory: () => new SockJS("/ws"),
    connectHeaders: { Authorization: `Bearer ${getAccessToken() ?? ""}` },
    reconnectDelay: 3000,
    onConnect: () => {
      client.subscribe(MODE_UPDATES_TOPIC, (message) => {
        onModeChange(JSON.parse(message.body) as ModeState);
      });
    },
  });
  client.activate();

  return () => {
    client.deactivate();
  };
}
