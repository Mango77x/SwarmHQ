package com.swarmhq.mqtt;

/**
 * A drone's bid on an available mission, published to
 * {@code missions/{missionId}/bids}. Lower cost wins - see
 * {@code AuctionCoordinatorService} and
 * {@code DroneRepository.findBestForMission}'s
 * {@code distance / (battery/100)} formula, which this mirrors so a drone
 * bids on roughly the same criteria the centralized engine would've used
 * to pick it.
 */
public record MissionBidPayload(String droneId, double cost) {
}
