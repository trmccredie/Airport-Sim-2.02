package sim.ui;

import sim.floorplan.model.FloorplanProject;
import sim.floorplan.ui.FloorplanSimulationPanel;
import sim.floorplan.sim.FloorplanTravelTimeProvider;   // ✅ best-effort update
import sim.model.Flight;
import sim.model.Passenger;
import sim.service.SimulationEngine;

import javax.swing.*;
import javax.swing.event.ChangeEvent;
import java.awt.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.Map;

public class SimulationFrame extends JFrame {
    private final JLabel            timeLabel;
    private final LocalTime         startTime;
    private final DateTimeFormatter TIME_FMT     = DateTimeFormatter.ofPattern("HH:mm");

    private final JButton           autoRunBtn;
    private final JButton           pausePlayBtn;
    private final JButton           summaryBtn;
    private final JSlider           speedSlider;

    private javax.swing.Timer       autoRunTimer;
    private       boolean           isPaused    = false;

    private final JButton           prevBtn;
    private final JSlider           timelineSlider;
    private final JLabel            intervalLabel;

    private boolean                 timelineProgrammaticUpdate = false;

    private final Map<Flight,Integer> closeSteps = new LinkedHashMap<>();
    private boolean simulationCompleted = false;

    // ✅ View tabs + floorplan tab
    private JTabbedPane viewTabs;
    private FloorplanSimulationPanel floorplanPanel;

    // ✅ Separate graphs window
    private GraphWindow graphsWindow;

    // ✅ store engine reference for best-effort walk speed update
    private final SimulationEngine engineRef;

    public SimulationFrame(double percentInPerson,
                           List<TicketCounterConfig> counterConfigs,
                           int numCheckpoints,
                           double checkpointRate,
                           int arrivalSpanMinutes,
                           int intervalMinutes,
                           int transitDelayMinutes,
                           int holdDelayMinutes,
                           List<Flight> flights) {
        this(buildEngineWithHoldRooms(
                percentInPerson,
                counterConfigs,
                numCheckpoints,
                checkpointRate,
                arrivalSpanMinutes,
                intervalMinutes,
                transitDelayMinutes,
                holdDelayMinutes,
                flights
        ));
    }

    public SimulationFrame(double percentInPerson,
                           List<TicketCounterConfig> counterConfigs,
                           List<CheckpointConfig> checkpointConfigs,
                           int arrivalSpanMinutes,
                           int intervalMinutes,
                           int transitDelayMinutes,
                           int holdDelayMinutes,
                           List<Flight> flights) {
        this(buildEngineWithHoldRooms(
                percentInPerson,
                counterConfigs,
                checkpointConfigs,
                arrivalSpanMinutes,
                intervalMinutes,
                transitDelayMinutes,
                holdDelayMinutes,
                flights
        ));
    }

    private static SimulationEngine buildEngineWithHoldRooms(double percentInPerson,
                                                             List<TicketCounterConfig> counterConfigs,
                                                             int numCheckpoints,
                                                             double checkpointRate,
                                                             int arrivalSpanMinutes,
                                                             int intervalMinutes,
                                                             int transitDelayMinutes,
                                                             int holdDelayMinutes,
                                                             List<Flight> flights) {
        List<HoldRoomConfig> holdRooms = buildDefaultHoldRoomConfigs(flights, holdDelayMinutes);

        return new SimulationEngine(
                percentInPerson,
                counterConfigs,
                numCheckpoints,
                checkpointRate,
                arrivalSpanMinutes,
                intervalMinutes,
                transitDelayMinutes,
                holdDelayMinutes,
                flights,
                holdRooms
        );
    }

    private static SimulationEngine buildEngineWithHoldRooms(double percentInPerson,
                                                             List<TicketCounterConfig> counterConfigs,
                                                             List<CheckpointConfig> checkpointConfigs,
                                                             int arrivalSpanMinutes,
                                                             int intervalMinutes,
                                                             int transitDelayMinutes,
                                                             int holdDelayMinutes,
                                                             List<Flight> flights) {
        List<HoldRoomConfig> holdRooms = buildDefaultHoldRoomConfigs(flights, holdDelayMinutes);

        return new SimulationEngine(
                percentInPerson,
                counterConfigs,
                checkpointConfigs,
                arrivalSpanMinutes,
                intervalMinutes,
                transitDelayMinutes,
                holdDelayMinutes,
                flights,
                holdRooms
        );
    }

    private static List<HoldRoomConfig> buildDefaultHoldRoomConfigs(List<Flight> flights, int holdDelayMinutes) {
        List<HoldRoomConfig> list = new ArrayList<>();
        int n = (flights == null) ? 0 : flights.size();

        for (int i = 0; i < n; i++) {
            Flight f = flights.get(i);

            HoldRoomConfig cfg = tryInstantiateHoldRoomConfig(i + 1, "Hold Room " + (i + 1));
            if (cfg != null) {
                bestEffortSetWalkTime(cfg, holdDelayMinutes, 0);
                bestEffortAssignSingleFlight(cfg, f);
            }
            list.add(cfg);
        }
        return list;
    }

    private static HoldRoomConfig tryInstantiateHoldRoomConfig(int id, String name) {
        try {
            Constructor<HoldRoomConfig> c = HoldRoomConfig.class.getConstructor(int.class);
            HoldRoomConfig cfg = c.newInstance(id);
            bestEffortInvoke(cfg, "setName", new Class<?>[]{String.class}, new Object[]{name});
            return cfg;
        } catch (Exception ignored) { }

        try {
            Constructor<HoldRoomConfig> c = HoldRoomConfig.class.getConstructor(String.class);
            return c.newInstance(name);
        } catch (Exception ignored) { }

        try {
            Constructor<HoldRoomConfig> c0 = HoldRoomConfig.class.getConstructor();
            HoldRoomConfig cfg = c0.newInstance();
            bestEffortInvoke(cfg, "setName", new Class<?>[]{String.class}, new Object[]{name});
            return cfg;
        } catch (Exception ignored) { }

        return null;
    }

    private static void bestEffortSetWalkTime(HoldRoomConfig cfg, int minutes, int seconds) {
        if (cfg == null) return;

        if (bestEffortInvoke(cfg, "setWalkTime", new Class<?>[]{int.class, int.class}, new Object[]{minutes, seconds})) return;
        if (bestEffortInvoke(cfg, "setWalkMinutesSeconds", new Class<?>[]{int.class, int.class}, new Object[]{minutes, seconds})) return;
        if (bestEffortInvoke(cfg, "setTravelTime", new Class<?>[]{int.class, int.class}, new Object[]{minutes, seconds})) return;

        int totalSeconds = Math.max(0, minutes) * 60 + Math.max(0, seconds);
        bestEffortInvoke(cfg, "setWalkSeconds", new Class<?>[]{int.class}, new Object[]{totalSeconds});
    }

    private static void bestEffortAssignSingleFlight(HoldRoomConfig cfg, Flight f) {
        if (cfg == null || f == null) return;

        if (bestEffortInvoke(cfg, "setAllowedFlights", new Class<?>[]{java.util.Collection.class},
                new Object[]{Collections.singletonList(f)})) return;

        if (bestEffortInvoke(cfg, "addAllowedFlight", new Class<?>[]{Flight.class},
                new Object[]{f})) return;

        bestEffortInvoke(cfg, "setAllowedFlightNumbers", new Class<?>[]{java.util.Collection.class},
                new Object[]{Collections.singletonList(f.getFlightNumber())});

        bestEffortInvoke(cfg, "addAllowedFlightNumber", new Class<?>[]{String.class},
                new Object[]{f.getFlightNumber()});
    }

    private static boolean bestEffortInvoke(Object target, String methodName, Class<?>[] sig, Object[] args) {
        if (target == null) return false;
        try {
            Method m = target.getClass().getMethod(methodName, sig);
            m.invoke(target, args);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    // ==========================================================
    // ✅ Constructors
    // ==========================================================

    public SimulationFrame(SimulationEngine engine) {
        this(engine, null);
    }

    public SimulationFrame(SimulationEngine engine, FloorplanProject floorplanProjectCopy) {
        super(floorplanProjectCopy != null ? "Simulation View (Floorplan)" : "Simulation View");
        this.engineRef = engine;

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());

        LocalTime firstDep = engine.getFlights().stream()
                .map(Flight::getDepartureTime)
                .min(LocalTime::compareTo)
                .orElse(LocalTime.MIDNIGHT);
        startTime = firstDep.minusMinutes(engine.getArrivalSpan());

        // ==========================================================
        // Top header: legend + time
        // ==========================================================
        JPanel topPanel = new JPanel();
        topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.X_AXIS));

        JPanel legendPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        legendPanel.setBorder(BorderFactory.createTitledBorder("Legend"));
        for (Flight f : engine.getFlights()) {
            legendPanel.add(new JLabel(f.getShape().name() + " = " + f.getFlightNumber()));
        }
        topPanel.add(legendPanel);

        topPanel.add(Box.createHorizontalGlue());

        timeLabel = new JLabel(startTime.format(TIME_FMT));
        timeLabel.setFont(timeLabel.getFont().deriveFont(Font.BOLD, 16f));
        timeLabel.setBorder(BorderFactory.createTitledBorder("Current Time"));
        timeLabel.setHorizontalAlignment(SwingConstants.CENTER);

        JPanel timePanel = new JPanel(new BorderLayout());
        timePanel.setPreferredSize(new Dimension(180, 50));
        timePanel.setMaximumSize(new Dimension(180, 50));
        timePanel.add(timeLabel, BorderLayout.CENTER);

        topPanel.add(timePanel);
        topPanel.add(Box.createRigidArea(new Dimension(20, 0)));

        add(topPanel, BorderLayout.NORTH);

        // ==========================================================
        // Classic “Queues” view (scrollable)
        // ==========================================================
        JPanel split = new JPanel();
        split.setLayout(new BoxLayout(split, BoxLayout.X_AXIS));

        int cellW   = 60 / 3, boxSize = 60, gutter = 30, padding = 100;
        int queuedW = GridRenderer.COLS * cellW;
        int servedW = GridRenderer.COLS * cellW;
        int panelW  = queuedW + boxSize + servedW + padding;

        TicketLinesPanel ticketPanel = new TicketLinesPanel(engine, new ArrayList<>(), new ArrayList<>(), null);
        Dimension tPref = ticketPanel.getPreferredSize();
        ticketPanel.setPreferredSize(new Dimension(panelW, tPref.height));
        ticketPanel.setMinimumSize(ticketPanel.getPreferredSize());
        ticketPanel.setMaximumSize(ticketPanel.getPreferredSize());
        split.add(Box.createHorizontalStrut(gutter));
        split.add(ticketPanel);

        split.add(Box.createHorizontalStrut(gutter));
        CheckpointLinesPanel cpPanel = new CheckpointLinesPanel(engine, new ArrayList<>(), new ArrayList<>(), null);
        Dimension cPref = cpPanel.getPreferredSize();
        cpPanel.setPreferredSize(new Dimension(panelW, cPref.height));
        cpPanel.setMinimumSize(cpPanel.getPreferredSize());
        cpPanel.setMaximumSize(cpPanel.getPreferredSize());
        split.add(cpPanel);

        split.add(Box.createHorizontalStrut(gutter));
        HoldRoomsPanel holdPanel = new HoldRoomsPanel(engine, new ArrayList<>(), new ArrayList<>(), null);
        split.add(holdPanel);

        JScrollPane centerScroll = new JScrollPane(
                split,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_ALWAYS
        );
        centerScroll.getHorizontalScrollBar().setUnitIncrement(16);

        // ==========================================================
        // ✅ View tabs (Queues + Floorplan)
        // ==========================================================
        viewTabs = new JTabbedPane();
        viewTabs.addTab("Queues", centerScroll);

        if (floorplanProjectCopy != null && floorplanProjectCopy.getFloorplanImage() != null) {
            floorplanPanel = new FloorplanSimulationPanel(floorplanProjectCopy, engine);

            JPanel fpWrap = new JPanel(new BorderLayout());

            JToolBar fpBar = new JToolBar();
            fpBar.setFloatable(false);

            JButton resetViewBtn = new JButton("Reset View");
            resetViewBtn.addActionListener(e -> floorplanPanel.resetView());
            fpBar.add(resetViewBtn);

            // Optional best-effort walk speed live tweak (won’t break if provider doesn’t support it)
            fpBar.addSeparator();
            fpBar.add(new JLabel("Walk speed (m/s):"));

            JSpinner walkSpeedSpinner = new JSpinner(new SpinnerNumberModel(1.34, 0.20, 3.50, 0.05));
            walkSpeedSpinner.setMaximumSize(new Dimension(90, 28));
            fpBar.add(walkSpeedSpinner);

            JButton applyWalkBtn = new JButton("Apply");
            applyWalkBtn.addActionListener(e -> applyWalkSpeedBestEffort(walkSpeedSpinner));
            fpBar.add(applyWalkBtn);

            fpWrap.add(fpBar, BorderLayout.NORTH);
            fpWrap.add(floorplanPanel, BorderLayout.CENTER);

            viewTabs.addTab("Floorplan", fpWrap);
        }

        viewTabs.addChangeListener(e -> {
            if (floorplanPanel != null) {
                floorplanPanel.repaint();
                floorplanPanel.requestFocusInWindow();
            }
        });

        // ==========================================================
        // Bottom control panel (Timeline + speed only)
        // ==========================================================
        JPanel control = new JPanel();
        control.setLayout(new BoxLayout(control, BoxLayout.Y_AXIS));

        control.setPreferredSize(new Dimension(800, 170));
        control.setMinimumSize(new Dimension(0, 140));

        JSplitPane mainSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, viewTabs, control);
        mainSplit.setResizeWeight(0.88);
        mainSplit.setContinuousLayout(true);
        mainSplit.setOneTouchExpandable(true);
        add(mainSplit, BorderLayout.CENTER);

        SwingUtilities.invokeLater(() -> mainSplit.setDividerLocation(0.86));

        // ==========================================================
        // Buttons row
        // ==========================================================
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));

        prevBtn = new JButton("Prev Interval");
        btnPanel.add(prevBtn);

        JButton nextBtn = new JButton("Next Interval");
        btnPanel.add(nextBtn);

        autoRunBtn   = new JButton("AutoRun");
        pausePlayBtn = new JButton("Pause");
        summaryBtn   = new JButton("Summary");

        summaryBtn.setEnabled(false);
        pausePlayBtn.setVisible(false);

        btnPanel.add(autoRunBtn);
        btnPanel.add(pausePlayBtn);

        JButton graphsBtn = new JButton("Graphs...");
        graphsBtn.addActionListener(e -> {
            if (graphsWindow == null || !graphsWindow.isDisplayable()) {
                graphsWindow = new GraphWindow(engine);
            }
            graphsWindow.setViewedInterval(engine.getCurrentInterval());
            graphsWindow.updateFromEngine();
            graphsWindow.setVisible(true);
            graphsWindow.toFront();
            graphsWindow.requestFocus();
        });
        btnPanel.add(graphsBtn);

        btnPanel.add(summaryBtn);
        control.add(btnPanel);

        // ==========================================================
        // Timeline
        // ==========================================================
        JPanel timelinePanel = new JPanel(new BorderLayout(8, 6));
        timelinePanel.setBorder(
                BorderFactory.createTitledBorder("Timeline (rewind / review computed intervals)")
        );

        intervalLabel = new JLabel();
        intervalLabel.setPreferredSize(new Dimension(260, 20));
        intervalLabel.setHorizontalAlignment(SwingConstants.LEFT);

        timelineSlider = new JSlider(0, Math.max(0, engine.getMaxComputedInterval()), 0);
        timelineSlider.setPaintTicks(true);
        timelineSlider.setPaintLabels(true);
        timelineSlider.setMajorTickSpacing(10);
        timelineSlider.setMinorTickSpacing(1);

        rebuildTimelineLabels(timelineSlider);

        timelinePanel.add(intervalLabel, BorderLayout.NORTH);
        timelinePanel.add(timelineSlider, BorderLayout.CENTER);

        control.add(timelinePanel);

        // ==========================================================
        // Auto-run speed slider
        // ==========================================================
        JPanel sliderPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        sliderPanel.setBorder(BorderFactory.createTitledBorder("AutoRun Speed (ms per interval)"));
        speedSlider = new JSlider(100, 2000, 1000);
        speedSlider.setMajorTickSpacing(500);
        speedSlider.setMinorTickSpacing(100);
        speedSlider.setPaintTicks(true);
        speedSlider.setPaintLabels(true);
        Hashtable<Integer,JLabel> labels = new Hashtable<>();
        labels.put(100,  new JLabel("0.1s"));
        labels.put(500,  new JLabel("0.5s"));
        labels.put(1000, new JLabel("1s"));
        labels.put(1500, new JLabel("1.5s"));
        labels.put(2000, new JLabel("2s"));
        speedSlider.setLabelTable(labels);
        sliderPanel.add(speedSlider);
        control.add(sliderPanel);

        // ==========================================================
        // Summary window
        // ==========================================================
        summaryBtn.addActionListener(e -> new FlightsSummaryFrame(engine).setVisible(true));

        // ==========================================================
        // UI refresh helper
        // ==========================================================
        Runnable refreshUI = () -> {
            LocalTime now = startTime.plusMinutes(engine.getCurrentInterval());
            timeLabel.setText(now.format(TIME_FMT));

            split.repaint();
            if (floorplanPanel != null) floorplanPanel.repaint();

            int maxComputed = engine.getMaxComputedInterval();

            timelineProgrammaticUpdate = true;
            try {
                if (timelineSlider.getMaximum() != maxComputed) {
                    timelineSlider.setMaximum(maxComputed);
                    int major = computeMajorTickSpacing(maxComputed);
                    timelineSlider.setMajorTickSpacing(major);
                    timelineSlider.setMinorTickSpacing(1);
                    rebuildTimelineLabels(timelineSlider);
                }

                int ci = engine.getCurrentInterval();
                if (ci <= timelineSlider.getMaximum()) timelineSlider.setValue(ci);
                else timelineSlider.setValue(timelineSlider.getMaximum());
            } finally {
                timelineProgrammaticUpdate = false;
            }

            intervalLabel.setText("Interval: " + engine.getCurrentInterval()
                    + " / " + engine.getTotalIntervals());

            prevBtn.setEnabled(engine.canRewind());

            boolean canAdvance = engine.getCurrentInterval() < engine.getTotalIntervals();
            nextBtn.setEnabled(canAdvance);

            if (autoRunTimer == null || !autoRunTimer.isRunning()) {
                autoRunBtn.setEnabled(canAdvance);
            }

            if (simulationCompleted) {
                summaryBtn.setEnabled(true);
            }

            if (graphsWindow != null && graphsWindow.isDisplayable()) {
                graphsWindow.setViewedInterval(engine.getCurrentInterval());
                graphsWindow.updateFromEngine();
            }
        };

        java.util.function.Consumer<List<Flight>> handleClosures = (closed) -> {
            if (closed == null || closed.isEmpty()) return;

            int step = engine.getCurrentInterval() - 1;

            List<Flight> newlyClosed = new ArrayList<>();
            for (Flight f : closed) {
                if (!closeSteps.containsKey(f)) {
                    closeSteps.put(f, step);
                    newlyClosed.add(f);
                }
            }

            if (newlyClosed.isEmpty()) return;

            if (autoRunTimer != null && autoRunTimer.isRunning()) {
                autoRunTimer.stop();
                pausePlayBtn.setText("Play");
                isPaused = true;
            }

            for (Flight f : newlyClosed) {
                int total = (int)Math.round(f.getSeats() * f.getFillPercent());

                int made = 0;
                for (java.util.LinkedList<Passenger> room : engine.getHoldRoomLines()) {
                    for (Passenger p : room) {
                        if (p != null && p.getFlight() == f) made++;
                    }
                }

                JOptionPane.showMessageDialog(
                        SimulationFrame.this,
                        String.format("%s: %d of %d made their flight.",
                                f.getFlightNumber(), made, total),
                        "Flight Closed",
                        JOptionPane.INFORMATION_MESSAGE
                );
            }
        };

        autoRunTimer = new javax.swing.Timer(speedSlider.getValue(), ev -> {
            javax.swing.Timer t = (javax.swing.Timer)ev.getSource();
            if (engine.getCurrentInterval() < engine.getTotalIntervals()) {
                engine.computeNextInterval();
                refreshUI.run();

                List<Flight> closed = engine.getFlightsJustClosed();
                handleClosures.accept(closed);

                if (engine.getCurrentInterval() >= engine.getTotalIntervals()) {
                    simulationCompleted = true;
                    t.stop();
                    autoRunBtn.setEnabled(false);
                    pausePlayBtn.setEnabled(false);
                    summaryBtn.setEnabled(true);
                }
            }
        });

        speedSlider.addChangeListener((ChangeEvent e) -> {
            if (autoRunTimer != null) autoRunTimer.setDelay(speedSlider.getValue());
        });

        prevBtn.addActionListener(ev -> {
            if (autoRunTimer != null && autoRunTimer.isRunning()) {
                autoRunTimer.stop();
                pausePlayBtn.setText("Play");
                isPaused = true;
            }
            engine.rewindOneInterval();
            refreshUI.run();
        });

        nextBtn.addActionListener(ev -> {
            engine.computeNextInterval();
            refreshUI.run();

            List<Flight> closed = engine.getFlightsJustClosed();
            handleClosures.accept(closed);

            if (engine.getCurrentInterval() >= engine.getTotalIntervals()) {
                simulationCompleted = true;
                nextBtn.setEnabled(false);
                autoRunBtn.setEnabled(false);
                summaryBtn.setEnabled(true);
            }
        });

        timelineSlider.addChangeListener((ChangeEvent e) -> {
            if (timelineProgrammaticUpdate) return;

            if (timelineSlider.getValueIsAdjusting()) {
                intervalLabel.setText("Interval: " + timelineSlider.getValue()
                        + " / " + engine.getTotalIntervals());

                int v = timelineSlider.getValue();

                if (graphsWindow != null && graphsWindow.isDisplayable()) {
                    graphsWindow.setViewedInterval(v);
                    graphsWindow.updateFromEngine();
                }

                if (floorplanPanel != null) floorplanPanel.repaint();
                return;
            }

            int target = timelineSlider.getValue();

            if (autoRunTimer != null && autoRunTimer.isRunning()) {
                autoRunTimer.stop();
                pausePlayBtn.setText("Play");
                isPaused = true;
            }

            engine.goToInterval(target);
            refreshUI.run();
        });

        autoRunBtn.addActionListener(e -> {
            autoRunBtn.setEnabled(false);
            pausePlayBtn.setVisible(true);

            pausePlayBtn.setText("Pause");
            isPaused = false;

            if (autoRunTimer != null) autoRunTimer.start();
        });

        pausePlayBtn.addActionListener(e -> {
            if (autoRunTimer == null) return;

            if (isPaused) {
                autoRunTimer.start();
                pausePlayBtn.setText("Pause");
            } else {
                autoRunTimer.stop();
                pausePlayBtn.setText("Play");
            }
            isPaused = !isPaused;
            refreshUI.run();
        });

        refreshUI.run();

        setSize(1000, 900);
        setLocationRelativeTo(null);
    }

    /**
     * ✅ Best-effort walk speed update:
     * 1) If FloorplanSimulationPanel supports setWalkSpeedMps(double), call it.
     * 2) Try to reach into engine travel provider; if it is FloorplanTravelTimeProvider, update it too.
     * This will not throw if unavailable.
     */
    private void applyWalkSpeedBestEffort(JSpinner walkSpeedSpinner) {
        if (floorplanPanel == null) return;

        double v;
        try {
            v = ((Number) walkSpeedSpinner.getValue()).doubleValue();
        } catch (Exception ex) {
            return;
        }

        // Update panel (if supported)
        try {
            Method m = floorplanPanel.getClass().getMethod("setWalkSpeedMps", double.class);
            m.invoke(floorplanPanel, v);
        } catch (Exception ignored) { }

        // Update provider (if reachable)
        Object provider = null;

        // 1) Try getter
        try {
            Method gm = engineRef.getClass().getMethod("getTravelTimeProvider");
            provider = gm.invoke(engineRef);
        } catch (Exception ignored) { }

        // 2) Try declared field fallback
        if (provider == null) {
            provider = tryGetField(engineRef, "travelTimeProvider");
        }

        if (provider instanceof FloorplanTravelTimeProvider) {
            try {
                ((FloorplanTravelTimeProvider) provider).setWalkSpeedMps(v);
            } catch (Exception ignored) { }
        } else if (provider != null) {
            // reflection fallback: setWalkSpeedMps(double)
            try {
                Method sm = provider.getClass().getMethod("setWalkSpeedMps", double.class);
                sm.invoke(provider, v);
            } catch (Exception ignored) { }
        }

        floorplanPanel.repaint();
    }

    private static Object tryGetField(Object target, String fieldName) {
        if (target == null) return null;
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f.get(target);
            } catch (Exception ignored) { }
            c = c.getSuperclass();
        }
        return null;
    }

    private static int computeMajorTickSpacing(int maxIntervals) {
        if (maxIntervals >= 1000) return 500;
        if (maxIntervals >= 500)  return 100;
        if (maxIntervals >= 150)  return 50;
        if (maxIntervals >= 100)  return 20;

        if (maxIntervals >= 50) return 10;
        if (maxIntervals >= 20) return 5;
        return 1;
    }

    private static void rebuildTimelineLabels(JSlider slider) {
        int max = slider.getMaximum();
        int major = slider.getMajorTickSpacing();
        if (major <= 0) major = 1;

        Hashtable<Integer, JLabel> table = new Hashtable<>();
        table.put(0, new JLabel("0"));

        for (int v = major; v < max; v += major) {
            table.put(v, new JLabel(String.valueOf(v)));
        }

        if (max != 0) {
            int lastMajor = (max / major) * major;
            if (lastMajor == max) {
                table.put(max, new JLabel(String.valueOf(max)));
            } else {
                if (lastMajor > 0 && (max - lastMajor) < (major / 2)) {
                    table.remove(lastMajor);
                }
                table.put(max, new JLabel(String.valueOf(max)));
            }
        }

        slider.setLabelTable(table);
        slider.setPaintLabels(true);
        slider.setPaintTicks(true);
        slider.repaint();
    }
}
