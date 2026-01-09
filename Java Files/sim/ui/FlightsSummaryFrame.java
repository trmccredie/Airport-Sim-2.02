package sim.ui;

import sim.model.Flight;
import sim.model.Passenger;
import sim.service.SimulationEngine;

import javax.swing.*;
import java.awt.*;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class FlightsSummaryFrame extends JFrame {
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    public FlightsSummaryFrame(SimulationEngine engine) {
        super("All Flights Summary");
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(10, 10));

        List<Flight> flights = engine.getFlights();

        // Recompute global simulation start time (same logic as other UIs)
        LocalTime firstDep = flights.stream()
                .map(Flight::getDepartureTime)
                .min(LocalTime::compareTo)
                .orElse(LocalTime.MIDNIGHT);
        LocalTime globalStart = firstDep.minusMinutes(engine.getArrivalSpan());

        int cols = Math.min(4, flights.size()); // up to 4 per row
        JPanel grid = new JPanel(new GridLayout(0, cols, 10, 10));

        int maxHistoryStep = getMaxHistoryStep(engine);

        for (Flight f : flights) {
            LocalTime closeTime = f.getDepartureTime().minusMinutes(20);

            // closeStep is the minute index used by the UI clock label
            int closeStep = (int) Duration.between(globalStart, closeTime).toMinutes();

            // IMPORTANT FIX:
            // history index for "state at closeTime" is closeStep - 1 (because history[0] == time 1)
            int closeHistoryIndex = closeStep - 1;

            int step = clamp(closeHistoryIndex, 0, maxHistoryStep);

            String madeText = "";
            try {
                int total = (int) Math.round(f.getSeats() * f.getFillPercent());
                int made = 0;

                // Count across ALL physical rooms at that history step
                if (engine.getHistoryHoldRooms() != null
                        && step < engine.getHistoryHoldRooms().size()
                        && step >= 0) {

                    List<List<Passenger>> holdAtStep = engine.getHistoryHoldRooms().get(step);
                    for (List<Passenger> room : holdAtStep) {
                        if (room == null) continue;
                        for (Passenger p : room) {
                            if (p != null && p.getFlight() == f) made++;
                        }
                    }
                } else {
                    // fallback: current state, across all physical rooms
                    for (List<Passenger> room : engine.getHoldRoomLines()) {
                        if (room == null) continue;
                        for (Passenger p : room) {
                            if (p != null && p.getFlight() == f) made++;
                        }
                    }
                }

                madeText = String.format("  (%d/%d)", made, total);
            } catch (Exception ignored) { }

            String label = f.getFlightNumber() + " @ " + closeTime.format(TIME_FMT) + madeText;
            JButton btn = new JButton(label);

            String tip = "Close time minute index: " + closeStep
                    + " | history index used: " + step;
            if (step != closeHistoryIndex) {
                tip += " (clamped from " + closeHistoryIndex + ")";
            }
            btn.setToolTipText(tip);

            // Step is a HISTORY INDEX (not a clock minute index)
            btn.addActionListener(e -> new FlightSnapshotFrame(engine, f, step).setVisible(true));
            grid.add(btn);
        }

        JScrollPane scroll = new JScrollPane(
                grid,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        );
        add(scroll, BorderLayout.CENTER);

        pack();
        setExtendedState(getExtendedState() | JFrame.MAXIMIZED_BOTH);
        setVisible(true);
    }

    private int getMaxHistoryStep(SimulationEngine engine) {
        try {
            int a = engine.getHistoryQueuedTicket() != null ? engine.getHistoryQueuedTicket().size() : 0;
            int b = engine.getHistoryQueuedCheckpoint() != null ? engine.getHistoryQueuedCheckpoint().size() : 0;
            int c = engine.getHistoryHoldRooms() != null ? engine.getHistoryHoldRooms().size() : 0;

            int min = Math.min(a, Math.min(b, c));
            return Math.max(0, min - 1);
        } catch (Exception ex) {
            return 0;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
