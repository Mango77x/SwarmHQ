import { Client } from "@stomp/stompjs";
import SockJS from "sockjs-client";
import { apiFetch } from "./http";

export type EventType =
  | "LOW_BATTERY"
  | "WAYPOINT_REACHED"
  | "SIGNAL_LOST"
  | "SIGNAL_RECOVERED"
  | "STATUS_CHANGE"
  | "ENTERED_RISK_ZONE"
  | "EXITED_RISK_ZONE";

export interface DroneEvent {
  droneExternalId: string;
  type: EventType;
  detail: string;
  occurredAt: string;
}

const EVENT_UPDATES_TOPIC = "/topic/events";

export async function fetchEvents(): Promise<DroneEvent[]> {
  const response = await apiFetch("/api/events");
  if (!response.ok) {
    throw new Error(`GET /api/events failed: ${response.status}`);
  }
  return response.json();
}

/** Live counterpart to fetchEvents() - see api/liveDrones.ts for the same pattern. */
export function connectLiveEvents(onEvent: (event: DroneEvent) => void): () => void {
  const client = new Client({
    webSocketFactory: () => new SockJS("/ws"),
    reconnectDelay: 3000,
    onConnect: () => {
      client.subscribe(EVENT_UPDATES_TOPIC, (message) => {
        onEvent(JSON.parse(message.body) as DroneEvent);
      });
    },
  });
  client.activate();

  return () => {
    client.deactivate();
  };
}
