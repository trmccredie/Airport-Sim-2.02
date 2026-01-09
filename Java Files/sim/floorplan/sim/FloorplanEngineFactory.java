package sim.floorplan.sim;

import sim.floorplan.model.FloorplanProject;
import sim.model.ArrivalCurveConfig;
import sim.model.Flight;
import sim.service.SimulationEngine;
import sim.ui.CheckpointConfig;
import sim.ui.HoldRoomConfig;
import sim.ui.TicketCounterConfig;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class FloorplanEngineFactory {

    public static final double DEFAULT_TICKET_RATE_PER_HOUR = 60.0;      // 1 pax/min
    public static final double DEFAULT_CHECKPOINT_RATE_PER_HOUR = 180.0; // 3 pax/min
    public static final int DEFAULT_TRANSIT_DELAY_FALLBACK_MIN = 5;
    public static final int DEFAULT_HOLD_DELAY_FALLBACK_MIN = 5;

    private FloorplanEngineFactory() {}

    public static SimulationEngine buildFloorplanEngine(
            FloorplanProject project,
            double percentInPerson,
            List<Flight> flights,
            ArrivalCurveConfig curveCfg,
            int arrivalSpanMinutes,
            int intervalMinutes,
            double walkSpeedMps
    ) {
        FloorplanBindings b = new FloorplanBindings(project);

        int ticketN = Math.max(1, b.ticketCount());
        int cpN     = Math.max(1, b.checkpointCount());
        int holdN   = Math.max(1, b.holdroomCount());

        FloorplanTravelTimeProvider ttp = new FloorplanTravelTimeProvider(project, walkSpeedMps);

        List<TicketCounterConfig> counters = buildTicketCounters(ticketN, DEFAULT_TICKET_RATE_PER_HOUR);

        List<CheckpointConfig> checkpoints = new ArrayList<>();
        for (int i = 0; i < cpN; i++) {
            CheckpointConfig cfg = new CheckpointConfig(i + 1);
            cfg.setRatePerHour(DEFAULT_CHECKPOINT_RATE_PER_HOUR);
            checkpoints.add(cfg);
        }

        // Hold rooms: empty allowed flights => accepts ALL flights
        List<HoldRoomConfig> holdRooms = new ArrayList<>();
        for (int i = 0; i < holdN; i++) {
            HoldRoomConfig hr = new HoldRoomConfig(i + 1);

            // Seed walk time for hold-room assignment consistency
            int m = ttp.minutesCheckpointToHold(0, i);
            if (m <= 0) m = DEFAULT_HOLD_DELAY_FALLBACK_MIN;

            hr.setWalkTime(m, 0);
            holdRooms.add(hr);
        }

        SimulationEngine engine = new SimulationEngine(
                percentInPerson,
                counters,
                checkpoints,
                arrivalSpanMinutes,
                intervalMinutes,
                DEFAULT_TRANSIT_DELAY_FALLBACK_MIN,
                DEFAULT_HOLD_DELAY_FALLBACK_MIN,
                flights == null ? Collections.emptyList() : flights,
                holdRooms
        );

        if (curveCfg != null) {
            engine.setArrivalCurveConfig(curveCfg);
        }

        engine.setTravelTimeProvider(ttp);
        return engine;
    }

    private static List<TicketCounterConfig> buildTicketCounters(int n, double ratePerHour) {
        double perMin = Math.max(0.0, ratePerHour) / 60.0;

        List<TicketCounterConfig> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            TicketCounterConfig cfg = tryCreateTicketCounterConfig(i + 1, "Counter " + (i + 1));
            if (cfg == null) {
                throw new IllegalStateException("Could not construct TicketCounterConfig for counter " + (i + 1));
            }

            setRateBestEffort(cfg, perMin);
            out.add(cfg);
        }
        return out;
    }

    private static TicketCounterConfig tryCreateTicketCounterConfig(int id, String name) {
        try {
            Constructor<TicketCounterConfig> c = TicketCounterConfig.class.getConstructor(int.class);
            return c.newInstance(id);
        } catch (Exception ignored) {}

        try {
            Constructor<TicketCounterConfig> c = TicketCounterConfig.class.getConstructor(String.class);
            return c.newInstance(name);
        } catch (Exception ignored) {}

        try {
            Constructor<TicketCounterConfig> c = TicketCounterConfig.class.getConstructor();
            TicketCounterConfig cfg = c.newInstance();
            invokeBestEffort(cfg, "setName", new Class<?>[]{String.class}, new Object[]{name});
            invokeBestEffort(cfg, "setId", new Class<?>[]{int.class}, new Object[]{id});
            return cfg;
        } catch (Exception ignored) {}

        return null;
    }

    private static void setRateBestEffort(TicketCounterConfig cfg, double perMinute) {
        if (cfg == null) return;

        if (invokeBestEffort(cfg, "setRate", new Class<?>[]{double.class}, new Object[]{perMinute})) return;
        if (invokeBestEffort(cfg, "setRatePerMinute", new Class<?>[]{double.class}, new Object[]{perMinute})) return;
        if (invokeBestEffort(cfg, "setPassengersPerMinute", new Class<?>[]{double.class}, new Object[]{perMinute})) return;

        double perHour = perMinute * 60.0;
        invokeBestEffort(cfg, "setRatePerHour", new Class<?>[]{double.class}, new Object[]{perHour});
        invokeBestEffort(cfg, "setPassengersPerHour", new Class<?>[]{double.class}, new Object[]{perHour});
    }

    private static boolean invokeBestEffort(Object target, String name, Class<?>[] sig, Object[] args) {
        try {
            Method m = target.getClass().getMethod(name, sig);
            m.invoke(target, args);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
