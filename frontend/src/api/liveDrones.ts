import { Client } from "@stomp/stompjs";
import SockJS from "sockjs-client";
import { getAccessToken } from "./http";
import type { Drone } from "./drones";

const DRONE_UPDATES_TOPIC = "/topic/drones";

/**
 * Subscribes to live drone pushes over STOMP/SockJS (backend:
 * WebSocketConfig + DroneService.applyTelemetry), the incremental
 * counterpart to fetchDrones()'s REST snapshot. Call the returned
 * function to disconnect.
 */
export function connectLiveDrones(
  onDrone: (drone: Drone) => void,
  onConnectionChange: (connected: boolean) => void,
): () => void {
  const client = new Client({
    webSocketFactory: () => new SockJS("/ws"),
    connectHeaders: { Authorization: `Bearer ${getAccessToken() ?? ""}` },
    reconnectDelay: 3000,
    onConnect: () => {
      onConnectionChange(true);
      client.subscribe(DRONE_UPDATES_TOPIC, (message) => {
        onDrone(JSON.parse(message.body) as Drone);
      });
    },
    onWebSocketClose: () => onConnectionChange(false),
  });
  client.activate();

  return () => {
    client.deactivate();
  };
}
