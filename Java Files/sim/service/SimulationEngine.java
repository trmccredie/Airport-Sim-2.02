// NOTE: This is your file with ONLY the required changes to make floorplan travel times
// depend on TravelTimeProvider minutes AND convert minutes->intervals when intervalMinutes != 1,
// but ONLY when travelTimeProvider != null (floorplan mode).
package sim.service;

import sim.model.ArrivalCurveConfig;
import sim.model.Flight;
import sim.model.Passenger;
import sim.service.arrivals.ArrivalCurveGenerator;
import sim.service.arrivals.EditedSplitGaussianArrivalGenerator;
import sim.ui.CheckpointConfig;
import sim.ui.GridRenderer;
import sim.ui.TicketCounterConfig;
import sim.ui.HoldRoomConfig;

import sim.floorplan.sim.TravelTimeProvider; // optional floorplan travel-time hook

import java.time.Duration;
import java.time.LocalTime;
import java.util.*;

public class SimulationEngine {
    private final List<Flight> flights;

    private final List<HoldRoomConfig> holdRoomConfigs;
    private final Map<Flight, Integer> chosenHoldRoomIndexByFlight = new HashMap<>();

    private final Map<Integer, Integer> heldUpsByInterval = new LinkedHashMap<>();
    private final Map<Integer, Integer> ticketQueuedByInterval = new LinkedHashMap<>();
    private final Map<Integer, Integer> checkpointQueuedByInterval = new LinkedHashMap<>();
    private final Map<Integer, Integer> holdRoomTotalByInterval = new LinkedHashMap<>();

    private ArrivalCurveConfig arrivalCurveConfig = ArrivalCurveConfig.legacyDefault();
    private final ArrivalGenerator legacyMinuteGenerator;
    private final ArrivalCurveGenerator editedMinuteGenerator = new EditedSplitGaussianArrivalGenerator();
    private final Map<Flight, int[]> minuteArrivalsMap = new HashMap<>();

    private final Map<Flight, Integer> holdRoomCellSize;

    private final int arrivalSpanMinutes;
    private final int intervalMinutes;
    private final int transitDelayMinutes;    // ticket→checkpoint delay (legacy fallback)
    private final int holdDelayMinutes;       // legacy fallback
    private final int totalIntervals;

    private int currentInterval;

    private final double percentInPerson;

    private final List<TicketCounterConfig> counterConfigs;
    private final List<CheckpointConfig> checkpointConfigs;
    private final int numCheckpoints;
    private final double defaultCheckpointRatePerHour;

    private final LocalTime globalStart;
    private final List<Flight> justClosedFlights = new ArrayList<>();
    private final Set<Passenger> ticketCompletedVisible = new HashSet<>();

    private final List<LinkedList<Passenger>> ticketLines;
    private final List<LinkedList<Passenger>> checkpointLines;
    private final List<LinkedList<Passenger>> completedTicketLines;
    private final List<LinkedList<Passenger>> completedCheckpointLines;

    private final List<Map<Flight, Integer>> historyArrivals = new ArrayList<>();
    private final List<Map<Flight, Integer>> historyEnqueuedTicket = new ArrayList<>();
    private final List<Map<Flight, Integer>> historyTicketed = new ArrayList<>();
    private final List<Integer> historyTicketLineSize = new ArrayList<>();
    private final List<Map<Flight, Integer>> historyArrivedToCheckpoint = new ArrayList<>();
    private final List<Integer> historyCPLineSize = new ArrayList<>();
    private final List<Map<Flight, Integer>> historyPassedCheckpoint = new ArrayList<>();
    private final List<List<List<Passenger>>> historyOnlineArrivals = new ArrayList<>();
    private final List<List<List<Passenger>>> historyFromTicketArrivals = new ArrayList<>();

    private final List<LinkedList<Passenger>> holdRoomLines;

    private final List<List<List<Passenger>>> historyServedTicket = new ArrayList<>();
    private final List<List<List<Passenger>>> historyQueuedTicket = new ArrayList<>();
    private final List<List<List<Passenger>>> historyServedCheckpoint = new ArrayList<>();
    private final List<List<List<Passenger>>> historyQueuedCheckpoint = new ArrayList<>();
    private final List<List<List<Passenger>>> historyHoldRooms = new ArrayList<>();

    private final Random rand = new Random();

    private double[] counterProgress;
    private double[] checkpointProgress;

    private final Map<Integer, List<Passenger>> pendingToCP;
    private final Map<Integer, List<Passenger>> pendingToHold;

    private final Map<Passenger, Integer> targetCheckpointLineByPassenger = new HashMap<>();

    private Passenger[] counterServing;
    private Passenger[] checkpointServing;

    // ✅ Optional floorplan travel time provider
    private TravelTimeProvider travelTimeProvider;

    // REWIND SUPPORT
    private final List<EngineSnapshot> stateSnapshots = new ArrayList<>();
    private int maxComputedInterval = 0;

    private static final class EngineSnapshot {
        final int currentInterval;

        final List<LinkedList<Passenger>> ticketLines;
        final List<LinkedList<Passenger>> completedTicketLines;
        final List<LinkedList<Passenger>> checkpointLines;
        final List<LinkedList<Passenger>> completedCheckpointLines;
        final List<LinkedList<Passenger>> holdRoomLines;

        final double[] counterProgress;
        final double[] checkpointProgress;

        final Map<Integer, List<Passenger>> pendingToCP;
        final Map<Integer, List<Passenger>> pendingToHold;

        final Map<Passenger, Integer> targetCheckpointLineByPassenger;

        final Passenger[] counterServing;
        final Passenger[] checkpointServing;

        final Set<Passenger> ticketCompletedVisible;
        final List<Flight> justClosedFlights;

        final LinkedHashMap<Integer, Integer> heldUpsByInterval;
        final LinkedHashMap<Integer, Integer> ticketQueuedByInterval;
        final LinkedHashMap<Integer, Integer> checkpointQueuedByInterval;
        final LinkedHashMap<Integer, Integer> holdRoomTotalByInterval;

        EngineSnapshot(
                int currentInterval,
                List<LinkedList<Passenger>> ticketLines,
                List<LinkedList<Passenger>> completedTicketLines,
                List<LinkedList<Passenger>> checkpointLines,
                List<LinkedList<Passenger>> completedCheckpointLines,
                List<LinkedList<Passenger>> holdRoomLines,
                double[] counterProgress,
                double[] checkpointProgress,
                Map<Integer, List<Passenger>> pendingToCP,
                Map<Integer, List<Passenger>> pendingToHold,
                Map<Passenger, Integer> targetCheckpointLineByPassenger,
                Passenger[] counterServing,
                Passenger[] checkpointServing,
                Set<Passenger> ticketCompletedVisible,
                List<Flight> justClosedFlights,
                LinkedHashMap<Integer, Integer> heldUpsByInterval,
                LinkedHashMap<Integer, Integer> ticketQueuedByInterval,
                LinkedHashMap<Integer, Integer> checkpointQueuedByInterval,
                LinkedHashMap<Integer, Integer> holdRoomTotalByInterval
        ) {
            this.currentInterval = currentInterval;
            this.ticketLines = ticketLines;
            this.completedTicketLines = completedTicketLines;
            this.checkpointLines = checkpointLines;
            this.completedCheckpointLines = completedCheckpointLines;
            this.holdRoomLines = holdRoomLines;

            this.counterProgress = counterProgress;
            this.checkpointProgress = checkpointProgress;

            this.pendingToCP = pendingToCP;
            this.pendingToHold = pendingToHold;

            this.targetCheckpointLineByPassenger = targetCheckpointLineByPassenger;

            this.counterServing = counterServing;
            this.checkpointServing = checkpointServing;

            this.ticketCompletedVisible = ticketCompletedVisible;
            this.justClosedFlights = justClosedFlights;

            this.heldUpsByInterval = heldUpsByInterval;
            this.ticketQueuedByInterval = ticketQueuedByInterval;
            this.checkpointQueuedByInterval = checkpointQueuedByInterval;
            this.holdRoomTotalByInterval = holdRoomTotalByInterval;
        }
    }

    // Constructors preserved
    public SimulationEngine(double percentInPerson,
                            List<TicketCounterConfig> counterConfigs,
                            int numCheckpoints,
                            double checkpointRatePerHour,
                            int arrivalSpanMinutes,
                            int intervalMinutes,
                            int transitDelayMinutes,
                            int holdDelayMinutes,
                            List<Flight> flights) {
        this(percentInPerson, counterConfigs,
                buildDefaultCheckpointConfigs(numCheckpoints, checkpointRatePerHour),
                arrivalSpanMinutes, intervalMinutes, transitDelayMinutes, holdDelayMinutes,
                flights, null);
    }

    public SimulationEngine(double percentInPerson,
                            List<TicketCounterConfig> counterConfigs,
                            int numCheckpoints,
                            double checkpointRatePerHour,
                            int arrivalSpanMinutes,
                            int intervalMinutes,
                            int transitDelayMinutes,
                            int holdDelayMinutes,
                            List<Flight> flights,
                            List<HoldRoomConfig> holdRoomConfigs) {
        this(percentInPerson, counterConfigs,
                buildDefaultCheckpointConfigs(numCheckpoints, checkpointRatePerHour),
                arrivalSpanMinutes, intervalMinutes, transitDelayMinutes, holdDelayMinutes,
                flights, holdRoomConfigs);
    }

    public SimulationEngine(double percentInPerson,
                            List<TicketCounterConfig> counterConfigs,
                            List<CheckpointConfig> checkpointConfigs,
                            int arrivalSpanMinutes,
                            int intervalMinutes,
                            int transitDelayMinutes,
                            int holdDelayMinutes,
                            List<Flight> flights,
                            List<HoldRoomConfig> holdRoomConfigs) {

        this.percentInPerson = percentInPerson;

        this.flights = (flights == null) ? new ArrayList<>() : flights;
        this.counterConfigs = (counterConfigs == null) ? new ArrayList<>() : counterConfigs;

        List<CheckpointConfig> cps = (checkpointConfigs == null) ? new ArrayList<>() : new ArrayList<>(checkpointConfigs);
        if (cps.isEmpty()) {
            CheckpointConfig fallback = new CheckpointConfig(1);
            fallback.setRatePerHour(0.0);
            cps.add(fallback);
        }
        this.checkpointConfigs = cps;
        this.numCheckpoints = this.checkpointConfigs.size();

        this.defaultCheckpointRatePerHour = (this.checkpointConfigs.isEmpty())
                ? 0.0
                : this.checkpointConfigs.get(0).getRatePerHour();

        this.arrivalSpanMinutes = arrivalSpanMinutes;
        this.intervalMinutes = intervalMinutes;
        this.transitDelayMinutes = transitDelayMinutes;
        this.holdDelayMinutes = holdDelayMinutes;

        if (holdRoomConfigs != null && !holdRoomConfigs.isEmpty()) {
            this.holdRoomConfigs = new ArrayList<>(holdRoomConfigs);
        } else {
            this.holdRoomConfigs = buildDefaultHoldRoomConfigs(this.flights, holdDelayMinutes);
        }
        if (this.holdRoomConfigs.isEmpty()) {
            HoldRoomConfig cfg = new HoldRoomConfig(1);
            cfg.setWalkTime(Math.max(0, holdDelayMinutes), 0);
            this.holdRoomConfigs.add(cfg);
        }

        LocalTime firstDep = this.flights.stream()
                .map(Flight::getDepartureTime)
                .min(LocalTime::compareTo)
                .orElse(LocalTime.MIDNIGHT);
        this.globalStart = firstDep.minusMinutes(arrivalSpanMinutes);

        long maxDeparture = this.flights.stream()
                .mapToLong(f -> Duration.between(globalStart, f.getDepartureTime()).toMinutes())
                .max().orElse(0);
        this.totalIntervals = (int) maxDeparture + 1;

        this.legacyMinuteGenerator = new ArrivalGenerator(arrivalSpanMinutes, 1);
        setArrivalCurveConfig(ArrivalCurveConfig.legacyDefault());

        computeChosenHoldRooms();

        holdRoomCellSize = new HashMap<>();
        for (Flight f : this.flights) {
            int total = (int) Math.round(f.getSeats() * f.getFillPercent());
            int bestCell = GridRenderer.MIN_CELL_SIZE;
            for (int rows = 1; rows <= Math.max(1, total); rows++) {
                int cols = (total + rows - 1) / rows;
                int cellByRows = GridRenderer.HOLD_BOX_SIZE / rows;
                int cellByCols = GridRenderer.HOLD_BOX_SIZE / cols;
                int cell = Math.min(cellByRows, cellByCols);
                bestCell = Math.max(bestCell, cell);
            }
            holdRoomCellSize.put(f, bestCell);
        }

        this.currentInterval = 0;

        ticketLines = new ArrayList<>();
        completedTicketLines = new ArrayList<>();
        for (int i = 0; i < this.counterConfigs.size(); i++) {
            ticketLines.add(new LinkedList<>());
            completedTicketLines.add(new LinkedList<>());
        }

        checkpointLines = new ArrayList<>();
        completedCheckpointLines = new ArrayList<>();
        for (int i = 0; i < this.numCheckpoints; i++) {
            checkpointLines.add(new LinkedList<>());
            completedCheckpointLines.add(new LinkedList<>());
        }

        holdRoomLines = new ArrayList<>();
        for (int i = 0; i < this.holdRoomConfigs.size(); i++) {
            holdRoomLines.add(new LinkedList<>());
        }

        counterProgress = new double[this.counterConfigs.size()];
        checkpointProgress = new double[this.numCheckpoints];

        pendingToCP = new HashMap<>();
        pendingToHold = new HashMap<>();

        counterServing = new Passenger[this.counterConfigs.size()];
        checkpointServing = new Passenger[this.numCheckpoints];

        captureSnapshot0();
    }

    // ✅ Optional floorplan travel time hook
    public void setTravelTimeProvider(TravelTimeProvider p) {
        this.travelTimeProvider = p;
    }
    public TravelTimeProvider getTravelTimeProvider() { return travelTimeProvider; }

    // ✅ Convert provider minutes -> engine intervals (ceiling), but ONLY for floorplan provider usage
    private int providerMinutesToIntervalsCeil(int minutes) {
        int m = Math.max(0, minutes);
        int im = Math.max(1, intervalMinutes);
        if (m == 0) return 0;
        return (m + im - 1) / im;
    }

    // Arrival curve API
    public void setArrivalCurveConfig(ArrivalCurveConfig cfg) {
        ArrivalCurveConfig copy = copyCfg(cfg);
        copy.setBoardingCloseMinutesBeforeDeparture(ArrivalCurveConfig.DEFAULT_BOARDING_CLOSE);
        copy.validateAndClamp();
        this.arrivalCurveConfig = copy;
        rebuildMinuteArrivalsMap();
    }

    public ArrivalCurveConfig getArrivalCurveConfigCopy() { return copyCfg(this.arrivalCurveConfig); }

    private static ArrivalCurveConfig copyCfg(ArrivalCurveConfig src) {
        if (src == null) return ArrivalCurveConfig.legacyDefault();

        ArrivalCurveConfig c = ArrivalCurveConfig.legacyDefault();
        c.setLegacyMode(src.isLegacyMode());
        c.setPeakMinutesBeforeDeparture(src.getPeakMinutesBeforeDeparture());
        c.setLeftSigmaMinutes(src.getLeftSigmaMinutes());
        c.setRightSigmaMinutes(src.getRightSigmaMinutes());
        c.setLateClampEnabled(src.isLateClampEnabled());
        c.setLateClampMinutesBeforeDeparture(src.getLateClampMinutesBeforeDeparture());
        c.setWindowStartMinutesBeforeDeparture(src.getWindowStartMinutesBeforeDeparture());
        c.setBoardingCloseMinutesBeforeDeparture(src.getBoardingCloseMinutesBeforeDeparture());
        c.validateAndClamp();
        return c;
    }

    private void rebuildMinuteArrivalsMap() {
        minuteArrivalsMap.clear();
        for (Flight f : flights) {
            int totalPassengers = (int) Math.round(f.getSeats() * f.getFillPercent());
            int[] perMin;
            if (arrivalCurveConfig == null || arrivalCurveConfig.isLegacyMode()) {
                perMin = legacyMinuteGenerator.generateArrivals(f);
            } else {
                perMin = editedMinuteGenerator.buildArrivalsPerMinute(
                        f,
                        totalPassengers,
                        arrivalCurveConfig,
                        arrivalSpanMinutes
                );
            }
            minuteArrivalsMap.put(f, (perMin == null) ? new int[0] : perMin);
        }
    }

    private static List<CheckpointConfig> buildDefaultCheckpointConfigs(int numCheckpoints, double checkpointRatePerHour) {
        int n = Math.max(0, numCheckpoints);
        double rateHr = Math.max(0.0, checkpointRatePerHour);

        List<CheckpointConfig> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            CheckpointConfig cfg = new CheckpointConfig(i + 1);
            cfg.setRatePerHour(rateHr);
            list.add(cfg);
        }

        if (list.isEmpty()) {
            CheckpointConfig cfg = new CheckpointConfig(1);
            cfg.setRatePerHour(0.0);
            list.add(cfg);
        }
        return list;
    }

    private double perIntervalFromPerMinute(double perMinute) {
        return Math.max(0.0, perMinute) * Math.max(1, intervalMinutes);
    }

    private double perIntervalFromPerHour(double perHour) {
        return (Math.max(0.0, perHour) / 60.0) * Math.max(1, intervalMinutes);
    }

    private double getTicketCounterRatePerInterval(int counterIdx) {
        if (counterIdx < 0 || counterIdx >= counterConfigs.size()) return 0.0;
        return perIntervalFromPerMinute(counterConfigs.get(counterIdx).getRate());
    }

    private double getCheckpointRatePerInterval(int checkpointIdx) {
        if (checkpointIdx < 0 || checkpointIdx >= checkpointConfigs.size()) return 0.0;
        return perIntervalFromPerHour(checkpointConfigs.get(checkpointIdx).getRatePerHour());
    }

    private static List<HoldRoomConfig> buildDefaultHoldRoomConfigs(List<Flight> flights, int holdDelayMinutes) {
        List<HoldRoomConfig> list = new ArrayList<>();
        if (flights == null) return list;

        for (int i = 0; i < flights.size(); i++) {
            Flight f = flights.get(i);
            HoldRoomConfig cfg = new HoldRoomConfig(i + 1);
            cfg.setWalkTime(Math.max(0, holdDelayMinutes), 0);
            if (f != null) cfg.setAllowedFlights(Collections.singletonList(f));
            list.add(cfg);
        }
        return list;
    }

    private void computeChosenHoldRooms() {
        chosenHoldRoomIndexByFlight.clear();

        int roomCount = holdRoomConfigs.size();
        if (roomCount <= 0) return;

        for (Flight f : flights) {
            List<Integer> candidates = new ArrayList<>();
            int bestSeconds = Integer.MAX_VALUE;

            for (int r = 0; r < roomCount; r++) {
                HoldRoomConfig cfg = holdRoomConfigs.get(r);
                if (cfg == null) continue;
                if (!cfg.accepts(f)) continue;

                int ws = safeWalkSeconds(cfg);
                if (ws < bestSeconds) {
                    bestSeconds = ws;
                    candidates.clear();
                    candidates.add(r);
                } else if (ws == bestSeconds) {
                    candidates.add(r);
                }
            }

            int chosen;
            if (!candidates.isEmpty()) {
                chosen = candidates.get(rand.nextInt(candidates.size()));
            } else {
                int acceptAll = -1;
                for (int r = 0; r < roomCount; r++) {
                    HoldRoomConfig cfg = holdRoomConfigs.get(r);
                    if (cfg != null && cfg.getAllowedFlightNumbers().isEmpty()) {
                        acceptAll = r;
                        break;
                    }
                }
                chosen = (acceptAll >= 0) ? acceptAll : 0;
            }

            chosenHoldRoomIndexByFlight.put(f, clamp(chosen, 0, roomCount - 1));
        }
    }

    private int safeWalkSeconds(HoldRoomConfig cfg) {
        if (cfg == null) return Math.max(0, holdDelayMinutes) * 60;
        return Math.max(0, cfg.getWalkSecondsFromCheckpoint());
    }

    private int getBoardingCloseIdx(Flight f) {
        return (int) Duration.between(
                globalStart,
                f.getDepartureTime().minusMinutes(ArrivalCurveConfig.DEFAULT_BOARDING_CLOSE)
        ).toMinutes();
    }

    private int getDepartureIdx(Flight f) {
        return (int) Duration.between(
                globalStart,
                f.getDepartureTime()
        ).toMinutes();
    }

    private int ceilMinutesFromSeconds(int seconds) {
        int s = Math.max(0, seconds);
        return (s / 60) + ((s % 60) > 0 ? 1 : 0);
    }

    // Snapshots
    private void captureSnapshot0() {
        stateSnapshots.clear();

        heldUpsByInterval.clear();
        ticketQueuedByInterval.clear();
        checkpointQueuedByInterval.clear();
        holdRoomTotalByInterval.clear();

        justClosedFlights.clear();
        ticketCompletedVisible.clear();
        targetCheckpointLineByPassenger.clear();

        Arrays.fill(counterServing, null);
        Arrays.fill(checkpointServing, null);

        recordQueueTotalsForCurrentInterval();

        EngineSnapshot s0 = makeSnapshot();
        stateSnapshots.add(s0);
        maxComputedInterval = 0;
    }

    private EngineSnapshot makeSnapshot() {
        return new EngineSnapshot(
                currentInterval,
                deepCopyLinkedLists(ticketLines),
                deepCopyLinkedLists(completedTicketLines),
                deepCopyLinkedLists(checkpointLines),
                deepCopyLinkedLists(completedCheckpointLines),
                deepCopyLinkedLists(holdRoomLines),
                Arrays.copyOf(counterProgress, counterProgress.length),
                Arrays.copyOf(checkpointProgress, checkpointProgress.length),
                deepCopyPendingMap(pendingToCP),
                deepCopyPendingMap(pendingToHold),
                new HashMap<>(targetCheckpointLineByPassenger),
                Arrays.copyOf(counterServing, counterServing.length),
                Arrays.copyOf(checkpointServing, checkpointServing.length),
                new HashSet<>(ticketCompletedVisible),
                new ArrayList<>(justClosedFlights),
                new LinkedHashMap<>(heldUpsByInterval),
                new LinkedHashMap<>(ticketQueuedByInterval),
                new LinkedHashMap<>(checkpointQueuedByInterval),
                new LinkedHashMap<>(holdRoomTotalByInterval)
        );
    }

    private void appendSnapshotAfterInterval() {
        EngineSnapshot snap = makeSnapshot();

        if (currentInterval < stateSnapshots.size()) {
            stateSnapshots.set(currentInterval, snap);
        } else {
            stateSnapshots.add(snap);
        }
        maxComputedInterval = Math.max(maxComputedInterval, currentInterval);
    }

    private void restoreSnapshot(int targetInterval) {
        int t = clamp(targetInterval, 0, maxComputedInterval);
        EngineSnapshot s = stateSnapshots.get(t);

        this.currentInterval = s.currentInterval;

        restoreLinkedListsInPlace(ticketLines, s.ticketLines);
        restoreLinkedListsInPlace(completedTicketLines, s.completedTicketLines);
        restoreLinkedListsInPlace(checkpointLines, s.checkpointLines);
        restoreLinkedListsInPlace(completedCheckpointLines, s.completedCheckpointLines);
        restoreLinkedListsInPlace(holdRoomLines, s.holdRoomLines);

        if (this.counterProgress == null || this.counterProgress.length != s.counterProgress.length) {
            this.counterProgress = Arrays.copyOf(s.counterProgress, s.counterProgress.length);
        } else {
            System.arraycopy(s.counterProgress, 0, this.counterProgress, 0, s.counterProgress.length);
        }

        if (this.checkpointProgress == null || this.checkpointProgress.length != s.checkpointProgress.length) {
            this.checkpointProgress = Arrays.copyOf(s.checkpointProgress, s.checkpointProgress.length);
        } else {
            System.arraycopy(s.checkpointProgress, 0, this.checkpointProgress, 0, s.checkpointProgress.length);
        }

        this.pendingToCP.clear();
        this.pendingToCP.putAll(deepCopyPendingMap(s.pendingToCP));

        this.pendingToHold.clear();
        this.pendingToHold.putAll(deepCopyPendingMap(s.pendingToHold));

        this.targetCheckpointLineByPassenger.clear();
        this.targetCheckpointLineByPassenger.putAll(new HashMap<>(s.targetCheckpointLineByPassenger));

        if (this.counterServing == null || this.counterServing.length != s.counterServing.length) {
            this.counterServing = Arrays.copyOf(s.counterServing, s.counterServing.length);
        } else {
            System.arraycopy(s.counterServing, 0, this.counterServing, 0, s.counterServing.length);
        }

        if (this.checkpointServing == null || this.checkpointServing.length != s.checkpointServing.length) {
            this.checkpointServing = Arrays.copyOf(s.checkpointServing, s.checkpointServing.length);
        } else {
            System.arraycopy(s.checkpointServing, 0, this.checkpointServing, 0, s.checkpointServing.length);
        }

        this.ticketCompletedVisible.clear();
        this.ticketCompletedVisible.addAll(s.ticketCompletedVisible);

        this.justClosedFlights.clear();
        this.justClosedFlights.addAll(s.justClosedFlights);

        this.heldUpsByInterval.clear();
        this.heldUpsByInterval.putAll(s.heldUpsByInterval);

        this.ticketQueuedByInterval.clear();
        this.ticketQueuedByInterval.putAll(s.ticketQueuedByInterval);

        this.checkpointQueuedByInterval.clear();
        this.checkpointQueuedByInterval.putAll(s.checkpointQueuedByInterval);

        this.holdRoomTotalByInterval.clear();
        this.holdRoomTotalByInterval.putAll(s.holdRoomTotalByInterval);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // Rewind API
    public boolean canRewind() { return currentInterval > 0; }
    public boolean canFastForward() { return currentInterval < maxComputedInterval; }
    public int getMaxComputedInterval() { return maxComputedInterval; }

    public void goToInterval(int targetInterval) { restoreSnapshot(targetInterval); }
    public void rewindOneInterval() { if (canRewind()) restoreSnapshot(currentInterval - 1); }

    public void fastForwardOneInterval() {
        if (canFastForward()) {
            restoreSnapshot(currentInterval + 1);
        } else {
            computeNextInterval();
        }
    }

    public void computeNextInterval() {
        if (currentInterval >= totalIntervals) return;

        if ((currentInterval + 1) <= maxComputedInterval) {
            restoreSnapshot(currentInterval + 1);
            return;
        }

        simulateInterval();
    }

    public void runAllIntervals() {
        currentInterval = 0;

        clearHistory();

        heldUpsByInterval.clear();
        ticketQueuedByInterval.clear();
        checkpointQueuedByInterval.clear();
        holdRoomTotalByInterval.clear();

        justClosedFlights.clear();
        ticketCompletedVisible.clear();
        targetCheckpointLineByPassenger.clear();

        ticketLines.forEach(LinkedList::clear);
        completedTicketLines.forEach(LinkedList::clear);
        checkpointLines.forEach(LinkedList::clear);
        completedCheckpointLines.forEach(LinkedList::clear);
        holdRoomLines.forEach(LinkedList::clear);
        Arrays.fill(counterProgress, 0);
        Arrays.fill(checkpointProgress, 0);
        pendingToCP.clear();
        pendingToHold.clear();
        Arrays.fill(counterServing, null);
        Arrays.fill(checkpointServing, null);

        captureSnapshot0();

        while (currentInterval < totalIntervals) {
            simulateInterval();
        }
    }

    // Boarding close mark missed
    private void handleBoardingCloseMarkMissed(Flight f) {
        justClosedFlights.add(f);

        int chosenRoom = chosenHoldRoomIndexByFlight.getOrDefault(f, 0);
        chosenRoom = clamp(chosenRoom, 0, holdRoomLines.size() - 1);

        Set<Passenger> inChosen = new HashSet<>();
        for (Passenger p : holdRoomLines.get(chosenRoom)) {
            if (p != null && p.getFlight() == f) inChosen.add(p);
        }

        markMissedNotInChosen(ticketLines, f, inChosen);
        markMissedNotInChosen(completedTicketLines, f, inChosen);
        markMissedNotInChosen(checkpointLines, f, inChosen);
        markMissedNotInChosen(completedCheckpointLines, f, inChosen);

        purgeFromPendingMap(pendingToCP, f, inChosen);
        purgeFromPendingMap(pendingToHold, f, inChosen);

        for (int i = 0; i < counterServing.length; i++) {
            Passenger p = counterServing[i];
            if (p != null && p.getFlight() == f && !inChosen.contains(p)) p.setMissed(true);
        }
        for (int i = 0; i < checkpointServing.length; i++) {
            Passenger p = checkpointServing[i];
            if (p != null && p.getFlight() == f && !inChosen.contains(p)) p.setMissed(true);
        }
    }

    private void markMissedNotInChosen(List<LinkedList<Passenger>> lists, Flight f, Set<Passenger> inChosen) {
        for (LinkedList<Passenger> line : lists) {
            for (Passenger p : line) {
                if (p != null && p.getFlight() == f && !inChosen.contains(p)) p.setMissed(true);
            }
        }
    }

    private void purgeFromPendingMap(Map<Integer, List<Passenger>> pending, Flight f, Set<Passenger> inChosen) {
        Iterator<Map.Entry<Integer, List<Passenger>>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, List<Passenger>> e = it.next();
            List<Passenger> list = e.getValue();
            if (list == null) continue;

            list.removeIf(p -> {
                if (p != null && p.getFlight() == f && !inChosen.contains(p)) {
                    p.setMissed(true);
                    targetCheckpointLineByPassenger.remove(p);
                    return true;
                }
                return false;
            });

            if (list.isEmpty()) it.remove();
        }
    }

    // close clear (non-hold)
    private void clearFlightFromNonHoldAreas(Flight f) {
        for (LinkedList<Passenger> line : ticketLines) line.removeIf(p -> p != null && p.getFlight() == f);
        for (LinkedList<Passenger> line : completedTicketLines) line.removeIf(p -> p != null && p.getFlight() == f);
        for (LinkedList<Passenger> line : checkpointLines) line.removeIf(p -> p != null && p.getFlight() == f);
        for (LinkedList<Passenger> line : completedCheckpointLines) line.removeIf(p -> p != null && p.getFlight() == f);

        purgeAllFromPendingMap(pendingToCP, f);
        purgeAllFromPendingMap(pendingToHold, f);

        for (int i = 0; i < counterServing.length; i++) {
            Passenger p = counterServing[i];
            if (p != null && p.getFlight() == f) counterServing[i] = null;
        }
        for (int i = 0; i < checkpointServing.length; i++) {
            Passenger p = checkpointServing[i];
            if (p != null && p.getFlight() == f) checkpointServing[i] = null;
        }

        ticketCompletedVisible.removeIf(p -> p != null && p.getFlight() == f);
        targetCheckpointLineByPassenger.keySet().removeIf(p -> p != null && p.getFlight() == f);
    }

    private void purgeAllFromPendingMap(Map<Integer, List<Passenger>> pending, Flight f) {
        Iterator<Map.Entry<Integer, List<Passenger>>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, List<Passenger>> e = it.next();
            List<Passenger> list = e.getValue();
            if (list == null) continue;

            list.removeIf(p -> {
                if (p != null && p.getFlight() == f) {
                    targetCheckpointLineByPassenger.remove(p);
                    return true;
                }
                return false;
            });

            if (list.isEmpty()) it.remove();
        }
    }

    // departure clear (hold rooms)
    private void clearFlightFromHoldRooms(Flight f) {
        for (LinkedList<Passenger> room : holdRoomLines) {
            room.removeIf(p -> p != null && p.getFlight() == f);
        }
    }

    // queue helpers
    private Passenger takeFirstNotMissed(LinkedList<Passenger> q) {
        if (q == null || q.isEmpty()) return null;
        Iterator<Passenger> it = q.iterator();
        while (it.hasNext()) {
            Passenger p = it.next();
            if (p != null && !p.isMissed()) {
                it.remove();
                return p;
            }
        }
        return null;
    }

    private void removeFromCompletedCheckpointLines(Passenger p) {
        if (p == null) return;
        for (LinkedList<Passenger> line : completedCheckpointLines) {
            Iterator<Passenger> it = line.iterator();
            while (it.hasNext()) {
                if (it.next() == p) {
                    it.remove();
                    return;
                }
            }
        }
    }

    private int pickBestCheckpointLine() {
        int bestC = 0;
        for (int j = 1; j < numCheckpoints; j++) {
            if (checkpointLines.get(j).size() < checkpointLines.get(bestC).size()) bestC = j;
        }
        return bestC;
    }

    private static void inc(Map<Flight, Integer> map, Flight f, int delta) {
        if (map == null || f == null || delta == 0) return;
        map.put(f, map.getOrDefault(f, 0) + delta);
    }

    private static Map<Flight, Integer> mapCopy(Map<Flight, Integer> m) {
        return (m == null) ? new LinkedHashMap<>() : new LinkedHashMap<>(m);
    }

    // MAIN SIMULATION STEP
    public void simulateInterval() {
        justClosedFlights.clear();
        Arrays.fill(counterServing, null);
        Arrays.fill(checkpointServing, null);

        int minute = currentInterval; // kept as "minute index" for compatibility
        List<Flight> flightsDepartingThisMinute = new ArrayList<>();

        Map<Flight, Integer> arrivalsThisMinute = new LinkedHashMap<>();
        Map<Flight, Integer> enqueuedTicketThisMinute = new LinkedHashMap<>();
        Map<Flight, Integer> ticketedThisMinute = new LinkedHashMap<>();
        Map<Flight, Integer> arrivedToCheckpointThisMinute = new LinkedHashMap<>();
        Map<Flight, Integer> passedCheckpointThisMinute = new LinkedHashMap<>();

        List<List<Passenger>> onlineArrivalsThisMinute = new ArrayList<>();
        List<List<Passenger>> fromTicketArrivalsThisMinute = new ArrayList<>();
        for (int i = 0; i < numCheckpoints; i++) {
            onlineArrivalsThisMinute.add(new ArrayList<>());
            fromTicketArrivalsThisMinute.add(new ArrayList<>());
        }

        // 1) arrivals + detect boarding-close (mark missed only)
        for (Flight f : flights) {
            if (minute == getDepartureIdx(f)) flightsDepartingThisMinute.add(f);

            int[] perMin = minuteArrivalsMap.get(f);
            long offset = Duration.between(globalStart,
                            f.getDepartureTime().minusMinutes(arrivalSpanMinutes))
                    .toMinutes();
            int idx = minute - (int) offset;

            if (perMin != null && idx >= 0 && idx < perMin.length) {
                int totalHere = perMin[idx];
                if (totalHere < 0) totalHere = 0;

                inc(arrivalsThisMinute, f, totalHere);

                int inPerson = (int) Math.round(totalHere * percentInPerson);
                int online = totalHere - inPerson;

                if (counterConfigs.isEmpty()) {
                    online += inPerson;
                    inPerson = 0;
                }

                inc(enqueuedTicketThisMinute, f, inPerson);
                inc(arrivedToCheckpointThisMinute, f, online);

                List<Integer> allowed = new ArrayList<>();
                for (int j = 0; j < counterConfigs.size(); j++) {
                    if (counterConfigs.get(j).accepts(f)) allowed.add(j);
                }
                if (allowed.isEmpty() && !counterConfigs.isEmpty()) {
                    for (int j = 0; j < counterConfigs.size(); j++) allowed.add(j);
                }

                for (int i = 0; i < inPerson; i++) {
                    Passenger p = new Passenger(f, minute, true);
                    int best = allowed.get(0);
                    for (int ci : allowed) {
                        if (ticketLines.get(ci).size() < ticketLines.get(best).size()) best = ci;
                    }
                    ticketLines.get(best).add(p);
                }

                for (int i = 0; i < online; i++) {
                    Passenger p = new Passenger(f, minute, false);
                    p.setCheckpointEntryMinute(minute);

                    int bestC = pickBestCheckpointLine();
                    checkpointLines.get(bestC).add(p);
                    onlineArrivalsThisMinute.get(bestC).add(p);
                }
            }

            int closeIdx = getBoardingCloseIdx(f);
            if (minute == closeIdx) handleBoardingCloseMarkMissed(f);
        }

        // 2) ticket-counter service
        for (int c = 0; c < counterConfigs.size(); c++) {
            double ratePerInterval = getTicketCounterRatePerInterval(c);
            counterProgress[c] += ratePerInterval;

            int toComplete = (int) Math.floor(counterProgress[c]);
            counterProgress[c] -= toComplete;

            for (int k = 0; k < toComplete; k++) {
                Passenger next = takeFirstNotMissed(ticketLines.get(c));
                if (next == null) break;

                counterServing[c] = next;

                next.setTicketCompletionMinute(minute);
                completedTicketLines.get(c).add(next);
                ticketCompletedVisible.add(next);

                inc(ticketedThisMinute, next.getFlight(), 1);

                if (!next.isMissed()) {
                    int targetCp = pickBestCheckpointLine();
                    targetCheckpointLineByPassenger.put(next, targetCp);

                    int delayIntervals;
                    if (travelTimeProvider != null) {
                        int delayMinutes = travelTimeProvider.minutesTicketToCheckpoint(c, targetCp);
                        delayIntervals = providerMinutesToIntervalsCeil(delayMinutes);
                        if (delayIntervals <= 0) delayIntervals = providerMinutesToIntervalsCeil(transitDelayMinutes);
                        delayIntervals = Math.max(1, delayIntervals);
                    } else {
                        // ✅ legacy behavior unchanged
                        delayIntervals = Math.max(1, transitDelayMinutes);
                    }

                    pendingToCP.computeIfAbsent(minute + delayIntervals, x -> new ArrayList<>()).add(next);
                }
            }
        }

        // 3) move from ticket → checkpoint
        List<Passenger> toMove = pendingToCP.remove(minute);
        if (toMove != null) {
            for (Passenger p : toMove) {
                if (p == null || p.isMissed()) continue;

                ticketCompletedVisible.remove(p);
                p.setCheckpointEntryMinute(minute);

                Integer target = targetCheckpointLineByPassenger.remove(p);
                int cpLine = (target == null) ? pickBestCheckpointLine() : clamp(target, 0, numCheckpoints - 1);

                checkpointLines.get(cpLine).add(p);

                inc(arrivedToCheckpointThisMinute, p.getFlight(), 1);
                fromTicketArrivalsThisMinute.get(cpLine).add(p);
            }
        }

        // 4) checkpoint service
        for (int c = 0; c < numCheckpoints; c++) {
            double ratePerInterval = getCheckpointRatePerInterval(c);
            checkpointProgress[c] += ratePerInterval;

            int toComplete = (int) Math.floor(checkpointProgress[c]);
            checkpointProgress[c] -= toComplete;

            for (int k = 0; k < toComplete; k++) {
                Passenger next = takeFirstNotMissed(checkpointLines.get(c));
                if (next == null) break;

                checkpointServing[c] = next;

                next.setCheckpointCompletionMinute(minute);
                completedCheckpointLines.get(c).add(next);

                inc(passedCheckpointThisMinute, next.getFlight(), 1);

                if (!next.isMissed()) {
                    Flight f = next.getFlight();
                    int targetRoom = chosenHoldRoomIndexByFlight.getOrDefault(f, 0);
                    targetRoom = clamp(targetRoom, 0, holdRoomConfigs.size() - 1);

                    next.setAssignedHoldRoomIndex(targetRoom);

                    int delayIntervals;
                    if (travelTimeProvider != null) {
                        int delayMinutes = travelTimeProvider.minutesCheckpointToHold(c, targetRoom);
                        delayIntervals = providerMinutesToIntervalsCeil(delayMinutes);
                        if (delayIntervals <= 0) delayIntervals = providerMinutesToIntervalsCeil(holdDelayMinutes);
                        delayIntervals = Math.max(1, delayIntervals);
                    } else {
                        int walkSeconds = safeWalkSeconds(holdRoomConfigs.get(targetRoom));
                        int delayMinutes = ceilMinutesFromSeconds(walkSeconds);
                        delayIntervals = Math.max(1, delayMinutes);
                    }

                    int arriveMinute = minute + delayIntervals;
                    pendingToHold.computeIfAbsent(arriveMinute, x -> new ArrayList<>()).add(next);
                }
            }
        }

        // 5) move from checkpoint → hold-room
        List<Passenger> toHold = pendingToHold.remove(minute);
        if (toHold != null) {
            for (Passenger p : toHold) {
                if (p == null || p.isMissed()) continue;

                Flight f = p.getFlight();
                int closeIdx = getBoardingCloseIdx(f);

                if (minute < closeIdx) {
                    int roomIdx = p.getAssignedHoldRoomIndex();
                    if (roomIdx < 0) {
                        roomIdx = chosenHoldRoomIndexByFlight.getOrDefault(f, 0);
                        p.setAssignedHoldRoomIndex(roomIdx);
                    }
                    roomIdx = clamp(roomIdx, 0, holdRoomLines.size() - 1);

                    removeFromCompletedCheckpointLines(p);

                    p.setHoldRoomEntryMinute(minute);
                    int seq = holdRoomLines.get(roomIdx).size() + 1;
                    p.setHoldRoomSequence(seq);
                    holdRoomLines.get(roomIdx).add(p);
                } else {
                    p.setMissed(true);
                }
            }
        }

        // departure: clear hold rooms
        if (!flightsDepartingThisMinute.isEmpty()) {
            for (Flight f : flightsDepartingThisMinute) clearFlightFromHoldRooms(f);
        }

        // record histories
        historyServedTicket.add(deepCopyPassengerLists(completedTicketLines));
        historyQueuedTicket.add(deepCopyPassengerLists(ticketLines));
        historyServedCheckpoint.add(deepCopyPassengerLists(completedCheckpointLines));
        historyQueuedCheckpoint.add(deepCopyPassengerLists(checkpointLines));
        historyHoldRooms.add(deepCopyPassengerLists(holdRoomLines));

        historyArrivals.add(mapCopy(arrivalsThisMinute));
        historyEnqueuedTicket.add(mapCopy(enqueuedTicketThisMinute));
        historyTicketed.add(mapCopy(ticketedThisMinute));
        historyArrivedToCheckpoint.add(mapCopy(arrivedToCheckpointThisMinute));
        historyPassedCheckpoint.add(mapCopy(passedCheckpointThisMinute));

        int ticketWaitingNow = ticketLines.stream().mapToInt(List::size).sum();
        int checkpointWaitingNow = checkpointLines.stream().mapToInt(List::size).sum();
        historyTicketLineSize.add(ticketWaitingNow);
        historyCPLineSize.add(checkpointWaitingNow);

        historyOnlineArrivals.add(deepCopyListOfLists(onlineArrivalsThisMinute));
        historyFromTicketArrivals.add(deepCopyListOfLists(fromTicketArrivalsThisMinute));

        if (!justClosedFlights.isEmpty()) {
            for (Flight f : justClosedFlights) clearFlightFromNonHoldAreas(f);
        }

        removeMissedPassengers();

        currentInterval++;

        int stillInTicketQueue = ticketLines.stream().mapToInt(List::size).sum();
        int stillInCheckpointQueue = checkpointLines.stream().mapToInt(List::size).sum();
        heldUpsByInterval.put(currentInterval, stillInTicketQueue + stillInCheckpointQueue);

        recordQueueTotalsForCurrentInterval();
        appendSnapshotAfterInterval();
    }

    private static List<List<Passenger>> deepCopyListOfLists(List<List<Passenger>> src) {
        List<List<Passenger>> out = new ArrayList<>();
        if (src == null) return out;
        for (List<Passenger> l : src) out.add(new ArrayList<>(l));
        return out;
    }

    public void removeMissedPassengers() {
        ticketLines.forEach(line -> line.removeIf(Passenger::isMissed));
        completedTicketLines.forEach(line -> line.removeIf(Passenger::isMissed));
        checkpointLines.forEach(line -> line.removeIf(Passenger::isMissed));
        completedCheckpointLines.forEach(line -> line.removeIf(Passenger::isMissed));
        holdRoomLines.forEach(line -> line.removeIf(Passenger::isMissed));
        targetCheckpointLineByPassenger.keySet().removeIf(p -> p == null || p.isMissed());
    }

    private List<List<Passenger>> deepCopyPassengerLists(List<LinkedList<Passenger>> original) {
        List<List<Passenger>> copy = new ArrayList<>();
        for (LinkedList<Passenger> line : original) copy.add(new ArrayList<>(line));
        return copy;
    }

    private void clearHistory() {
        historyArrivals.clear();
        historyEnqueuedTicket.clear();
        historyTicketed.clear();
        historyTicketLineSize.clear();
        historyArrivedToCheckpoint.clear();
        historyCPLineSize.clear();
        historyPassedCheckpoint.clear();

        historyServedTicket.clear();
        historyQueuedTicket.clear();
        historyOnlineArrivals.clear();
        historyFromTicketArrivals.clear();
        historyServedCheckpoint.clear();
        historyQueuedCheckpoint.clear();
        historyHoldRooms.clear();

        Arrays.fill(counterProgress, 0);
        Arrays.fill(checkpointProgress, 0);
        Arrays.fill(counterServing, null);
        Arrays.fill(checkpointServing, null);

        pendingToCP.clear();
        pendingToHold.clear();
        targetCheckpointLineByPassenger.clear();
        ticketCompletedVisible.clear();
        holdRoomLines.forEach(LinkedList::clear);
        justClosedFlights.clear();
    }

    private static List<LinkedList<Passenger>> deepCopyLinkedLists(List<LinkedList<Passenger>> original) {
        List<LinkedList<Passenger>> copy = new ArrayList<>(original.size());
        for (LinkedList<Passenger> line : original) copy.add(new LinkedList<>(line));
        return copy;
    }

    private static void restoreLinkedListsInPlace(List<LinkedList<Passenger>> target,
                                                 List<LinkedList<Passenger>> source) {
        if (target.size() != source.size()) {
            target.clear();
            for (LinkedList<Passenger> src : source) target.add(new LinkedList<>(src));
            return;
        }
        for (int i = 0; i < target.size(); i++) {
            LinkedList<Passenger> t = target.get(i);
            t.clear();
            t.addAll(source.get(i));
        }
    }

    private static Map<Integer, List<Passenger>> deepCopyPendingMap(Map<Integer, List<Passenger>> original) {
        Map<Integer, List<Passenger>> copy = new HashMap<>();
        for (Map.Entry<Integer, List<Passenger>> e : original.entrySet()) {
            copy.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        return copy;
    }

    // RESTORED METHODS (compat)
    public List<Flight> getFlightsJustClosed() { return new ArrayList<>(justClosedFlights); }
    public Map<Flight, int[]> getMinuteArrivalsMap() { return Collections.unmodifiableMap(minuteArrivalsMap); }

    public int getTotalArrivalsAtInterval(int intervalIndex) {
        if (intervalIndex <= 0) return 0;
        return getTotalArrivalsAtMinute(intervalIndex - 1);
    }

    public int getTotalArrivalsAtMinute(int minuteSinceGlobalStart) {
        int sum = 0;
        for (Flight f : flights) {
            int[] perMin = minuteArrivalsMap.get(f);
            if (perMin == null) continue;

            long offset = Duration.between(
                    globalStart,
                    f.getDepartureTime().minusMinutes(arrivalSpanMinutes)
            ).toMinutes();

            int idx = minuteSinceGlobalStart - (int) offset;
            if (idx >= 0 && idx < perMin.length) sum += perMin[idx];
        }
        return sum;
    }

    // HISTORY GETTERS (compat)
    public List<Map<Flight, Integer>> getHistoryArrivals() { return historyArrivals; }
    public List<Map<Flight, Integer>> getHistoryEnqueuedTicket() { return historyEnqueuedTicket; }
    public List<Map<Flight, Integer>> getHistoryTicketed() { return historyTicketed; }
    public List<Integer> getHistoryTicketLineSize() { return historyTicketLineSize; }
    public List<Map<Flight, Integer>> getHistoryArrivedToCheckpoint() { return historyArrivedToCheckpoint; }
    public List<Integer> getHistoryCPLineSize() { return historyCPLineSize; }
    public List<Map<Flight, Integer>> getHistoryPassedCheckpoint() { return historyPassedCheckpoint; }

    public List<List<List<Passenger>>> getHistoryServedTicket() { return historyServedTicket; }
    public List<List<List<Passenger>>> getHistoryQueuedTicket() { return historyQueuedTicket; }
    public List<List<List<Passenger>>> getHistoryOnlineArrivals() { return historyOnlineArrivals; }
    public List<List<List<Passenger>>> getHistoryFromTicketArrivals() { return historyFromTicketArrivals; }
    public List<List<List<Passenger>>> getHistoryServedCheckpoint() { return historyServedCheckpoint; }
    public List<List<List<Passenger>>> getHistoryQueuedCheckpoint() { return historyQueuedCheckpoint; }
    public List<List<List<Passenger>>> getHistoryHoldRooms() { return historyHoldRooms; }

    // PUBLIC GETTERS
    public List<Flight> getFlights() { return flights; }
    public double getPercentInPerson() { return percentInPerson; }

    public int getArrivalSpan() { return arrivalSpanMinutes; }
    public int getInterval() { return intervalMinutes; }
    public int getTotalIntervals() { return totalIntervals; }
    public int getCurrentInterval() { return currentInterval; }

    public int getNumTicketCounters() { return counterConfigs == null ? 0 : counterConfigs.size(); }
    public int getNumCheckpoints() { return numCheckpoints; }
    public double getDefaultCheckpointRatePerHour() { return defaultCheckpointRatePerHour; }

    public List<LinkedList<Passenger>> getTicketLines() { return ticketLines; }
    public List<LinkedList<Passenger>> getCheckpointLines() { return checkpointLines; }
    public List<LinkedList<Passenger>> getCompletedTicketLines() { return completedTicketLines; }
    public List<LinkedList<Passenger>> getCompletedCheckpointLines() { return completedCheckpointLines; }
    public List<LinkedList<Passenger>> getHoldRoomLines() { return holdRoomLines; }

    public Passenger[] getCounterServing() { return counterServing; }
    public Passenger[] getCheckpointServing() { return checkpointServing; }

    public int getTransitDelayMinutes() { return transitDelayMinutes; }
    public int getHoldDelayMinutes() { return holdDelayMinutes; }

    public List<HoldRoomConfig> getHoldRoomConfigs() { return Collections.unmodifiableList(holdRoomConfigs); }
    public List<TicketCounterConfig> getCounterConfigs() { return Collections.unmodifiableList(counterConfigs); }
    public List<CheckpointConfig> getCheckpointConfigs() { return Collections.unmodifiableList(checkpointConfigs); }

    public LocalTime getGlobalStartTime() { return globalStart; }

    public int getHoldRoomCellSize(Flight f) {
        return holdRoomCellSize.getOrDefault(f, GridRenderer.MIN_CELL_SIZE);
    }

    public List<Passenger> getVisibleCompletedTicketLine(int idx) {
        List<Passenger> visible = new ArrayList<>();
        if (idx < 0 || idx >= completedTicketLines.size()) return visible;

        for (Passenger p : completedTicketLines.get(idx)) {
            if (ticketCompletedVisible.contains(p)) visible.add(p);
        }
        return visible;
    }

    public Map<Integer, Integer> getHoldUpsByInterval() { return new LinkedHashMap<>(heldUpsByInterval); }

    public Map<Integer, List<Passenger>> getPendingToCP() { return Collections.unmodifiableMap(pendingToCP); }
    public Map<Integer, List<Passenger>> getPendingToHold() { return Collections.unmodifiableMap(pendingToHold); }

    public Map<Passenger, Integer> getTargetCheckpointLineByPassenger() {
        return Collections.unmodifiableMap(targetCheckpointLineByPassenger);
    }

    public Integer getTargetCheckpointLineFor(Passenger p) {
        if (p == null) return null;
        return targetCheckpointLineByPassenger.get(p);
    }

    public int getChosenHoldRoomIndexForFlight(Flight f) {
        if (f == null) return 0;
        return chosenHoldRoomIndexByFlight.getOrDefault(f, 0);
    }

    // QUEUE TOTALS METRICS
    public int getTicketQueuedAtInterval(int intervalIndex) {
        Integer v = ticketQueuedByInterval.get(intervalIndex);
        return v == null ? 0 : v;
    }
    public int getCheckpointQueuedAtInterval(int intervalIndex) {
        Integer v = checkpointQueuedByInterval.get(intervalIndex);
        return v == null ? 0 : v;
    }
    public int getHoldRoomTotalAtInterval(int intervalIndex) {
        Integer v = holdRoomTotalByInterval.get(intervalIndex);
        return v == null ? 0 : v;
    }

    public Map<Integer, Integer> getTicketQueuedByInterval() { return new LinkedHashMap<>(ticketQueuedByInterval); }
    public Map<Integer, Integer> getCheckpointQueuedByInterval() { return new LinkedHashMap<>(checkpointQueuedByInterval); }
    public Map<Integer, Integer> getHoldRoomTotalByInterval() { return new LinkedHashMap<>(holdRoomTotalByInterval); }

    private void recordQueueTotalsForCurrentInterval() {
        int ticketWaiting = ticketLines.stream().mapToInt(List::size).sum();
        int checkpointWaiting = checkpointLines.stream().mapToInt(List::size).sum();
        int holdTotal = holdRoomLines.stream().mapToInt(List::size).sum();

        ticketQueuedByInterval.put(currentInterval, ticketWaiting);
        checkpointQueuedByInterval.put(currentInterval, checkpointWaiting);
        holdRoomTotalByInterval.put(currentInterval, holdTotal);
    }
}
