package sim.ui;

import sim.model.Flight;
import sim.model.Passenger;
import sim.service.SimulationEngine;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.lang.reflect.Method;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class HoldRoomsPanel extends JPanel {
    private static final int HOLD_BOX_SIZE = GridRenderer.HOLD_BOX_SIZE;
    private static final int HOLD_GAP      = GridRenderer.HOLD_GAP;

    private final SimulationEngine engine;
    private final Flight           filterFlight;
    private final List<Rectangle>  clickableAreas;
    private final List<Passenger>  clickablePassengers;

    public HoldRoomsPanel(SimulationEngine engine,
                          List<Rectangle> clickableAreas,
                          List<Passenger> clickablePassengers,
                          Flight filterFlight) {
        this.engine              = engine;
        this.filterFlight        = filterFlight;
        this.clickableAreas      = clickableAreas;
        this.clickablePassengers = clickablePassengers;

        // Preferred size that matches the renderer’s "wrap into columns" behavior.
        // We pick a stable default wrap height of 3 rooms per column; scrollpane can adjust as needed.
        int count = resolveHoldRoomCount(engine);
        int maxRowsPreferred = 3;
        int cols = (count + maxRowsPreferred - 1) / maxRowsPreferred;

        int labelH = 16;
        int rowHeight = HOLD_BOX_SIZE + labelH + 14;

        int width  = HOLD_GAP + cols * (HOLD_BOX_SIZE + 20) + HOLD_GAP;
        int height = HOLD_GAP + Math.min(count, maxRowsPreferred) * rowHeight + HOLD_GAP;

        setPreferredSize(new Dimension(Math.max(200, width), Math.max(200, height)));

        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                for (int i = 0; i < clickableAreas.size(); i++) {
                    if (clickableAreas.get(i).contains(e.getPoint())) {
                        Passenger p = clickablePassengers.get(i);
                        showPassengerDetails(p);
                        return;
                    }
                }
            }
        });
    }

    public HoldRoomsPanel(SimulationEngine engine, Flight filterFlight) {
        this(engine, new ArrayList<>(), new ArrayList<>(), filterFlight);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        GridRenderer.renderHoldRooms(
                this, g, engine,
                clickableAreas, clickablePassengers,
                filterFlight
        );
    }

    /**
     * Use engine.getHoldRoomConfigs().size() if possible,
     * otherwise fall back to flights.size().
     */
    private static int resolveHoldRoomCount(SimulationEngine engine) {
        if (engine == null) return 0;

        try {
            Method m = engine.getClass().getMethod("getHoldRoomConfigs");
            Object configs = m.invoke(engine);
            if (configs instanceof List) {
                return ((List<?>) configs).size();
            }
        } catch (Exception ignored) { }

        try {
            return engine.getFlights() == null ? 0 : engine.getFlights().size();
        } catch (Exception e) {
            return 0;
        }
    }

    private void showPassengerDetails(Passenger p) {
        // compute sim start (per-flight)
        LocalTime simStart = p.getFlight()
                .getDepartureTime()
                .minusMinutes(engine.getArrivalSpan());
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm");

        StringBuilder msg = new StringBuilder();
        msg.append("Flight: ").append(p.getFlight().getFlightNumber());
        msg.append("\nArrived at: ")
                .append(simStart.plusMinutes(p.getArrivalMinute()).format(fmt));
        msg.append("\nPurchase Type: ")
                .append(p.isInPerson() ? "In Person" : "Online");

        if (p.isInPerson() && p.getTicketCompletionMinute() >= 0) {
            msg.append("\nTicketed at: ")
                    .append(simStart.plusMinutes(p.getTicketCompletionMinute()).format(fmt));
        }
        if (p.getCheckpointEntryMinute() >= 0) {
            msg.append("\nCheckpoint Entry: ")
                    .append(simStart.plusMinutes(p.getCheckpointEntryMinute()).format(fmt));
        }
        if (p.getCheckpointCompletionMinute() >= 0) {
            msg.append("\nCheckpoint Completion: ")
                    .append(simStart.plusMinutes(p.getCheckpointCompletionMinute()).format(fmt));
        }

        if (p.getHoldRoomEntryMinute() >= 0) {
            msg.append("\nHold-room Entry: ")
                    .append(simStart.plusMinutes(p.getHoldRoomEntryMinute()).format(fmt));
        }
        if (p.getHoldRoomSequence() >= 0) {
            msg.append("\nHold-room Seq #: ")
                    .append(p.getHoldRoomSequence());
        }

        // Assigned physical room (reflection-safe)
        int assignedIdx = resolveAssignedHoldRoomIndex(p);
        if (assignedIdx >= 0) {
            msg.append("\nAssigned Hold Room Index: ").append(assignedIdx);
            try {
                List<HoldRoomConfig> cfgs = engine.getHoldRoomConfigs();
                if (assignedIdx < cfgs.size()) {
                    HoldRoomConfig cfg = cfgs.get(assignedIdx);
                    msg.append("  (ID ").append(cfg.getId()).append(", walk ")
                            .append(cfg.getWalkMinutes()).append(":")
                            .append(cfg.getWalkSecondsPart() < 10 ? "0" : "")
                            .append(cfg.getWalkSecondsPart()).append(")");
                }
            } catch (Exception ignored) { }
        }

        msg.append("\nMissed: ").append(p.isMissed());

        JOptionPane.showMessageDialog(
                this,
                msg.toString(),
                "Passenger Details",
                JOptionPane.INFORMATION_MESSAGE
        );
    }

    private static int resolveAssignedHoldRoomIndex(Passenger p) {
        if (p == null) return -1;
        try {
            Method m = p.getClass().getMethod("getAssignedHoldRoomIndex");
            Object v = m.invoke(p);
            if (v instanceof Integer) return (Integer) v;
        } catch (Exception ignored) { }
        return -1;
    }
}
