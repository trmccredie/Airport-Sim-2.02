package sim.floorplan.sim;

/**
 * Optional hook used by SimulationEngine.
 * If present, the engine will use these travel times instead of its legacy fixed delays.
 *
 * Return value rules:
 *  - Return a positive integer minutes for a valid travel time
 *  - Return <= 0 to indicate "unknown" so the engine can fall back to legacy delays
 */
public interface TravelTimeProvider {
    int minutesTicketToCheckpoint(int ticketCounterIdx, int checkpointIdx);
    int minutesCheckpointToHold(int checkpointIdx, int holdRoomIdx);
}
