package sim.floorplan.ui;

import sim.floorplan.model.FloorplanProject;
import sim.floorplan.model.WalkMask;
import sim.floorplan.path.AStarRouter;
import sim.floorplan.sim.FloorplanBindings;
import sim.floorplan.sim.PathCache;
import sim.floorplan.sim.TravelTimeProvider;
import sim.model.Flight;
import sim.model.Passenger;
import sim.service.SimulationEngine;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalTime;
import java.util.*;
import java.util.List;

public class FloorplanSimulationPanel extends JPanel {

    private SimulationEngine engine;
    private FloorplanProject project;

    private FloorplanBindings bindings;
    private PathCache pathCache;

    private int slotSpacingPx = 10;
    private int pathStridePx = 4;

    // pan/zoom
    private double zoom = 1.0;
    private double panX = 0;
    private double panY = 0;
    private Point lastMouse = null;

    private boolean isPanning = false;
    private static final int PAN_MARGIN_PX = 40;

    // small smoothing so movement is visible between discrete engine steps
    private javax.swing.Timer animTimer;
    private static final int ANIM_FPS_MS = 33;           // ~30 fps
    private static final int ANIM_STEP_MS_DEFAULT = 900; // interpolate within one engine step
    private int animStepMs = ANIM_STEP_MS_DEFAULT;

    private int lastEngineStep = Integer.MIN_VALUE;
    private long stepStartMs = 0L;
    private double stepAlpha01 = 1.0;

    private final Map<Integer, Integer> nearestCheckpointByTicket = new HashMap<>();

    // ✅ walk speed (now also propagates into floorplan travel-time provider)
    private double walkSpeedMps = 1.34;

    private int paintCurStep = 0;
    private LocalTime paintStartTime = null;
    private int paintStartMinutesOfDay = 0;
    private int paintBoardingCloseOffsetMin = 20;
    private final Map<Flight, Integer> departureIntervalByFlight = new HashMap<>();
    private final Map<Flight, Integer> boardingCloseIntervalByFlight = new HashMap<>();

    public FloorplanSimulationPanel(FloorplanProject projectCopy, SimulationEngine engine) {
        this.project = projectCopy;
        this.engine = engine;

        rebuildBindings();

        setBackground(Color.WHITE);
        setFocusable(true);

        MouseAdapter ma = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (isPanStart(e)) {
                    isPanning = true;
                    lastMouse = e.getPoint();
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                }
            }

            @Override public void mouseReleased(MouseEvent e) {
                isPanning = false;
                lastMouse = null;
                setCursor(Cursor.getDefaultCursor());
            }

            @Override public void mouseDragged(MouseEvent e) {
                if (!isPanning || lastMouse == null) return;

                Point cur = e.getPoint();
                panX += (cur.x - lastMouse.x);
                panY += (cur.y - lastMouse.y);
                lastMouse = cur;

                clampPan();
                repaint();
            }

            @Override public void mouseWheelMoved(MouseWheelEvent e) {
                double old = zoom;
                double factor = (e.getWheelRotation() < 0) ? 1.12 : (1.0 / 1.12);
                zoom = Math.max(0.15, Math.min(6.0, zoom * factor));

                // zoom around cursor
                Point p = e.getPoint();
                double sx = (p.x - panX) / old;
                double sy = (p.y - panY) / old;
                panX = p.x - sx * zoom;
                panY = p.y - sy * zoom;

                clampPan();
                repaint();
            }
        };

        addMouseListener(ma);
        addMouseMotionListener(ma);
        addMouseWheelListener(ma);

        addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) {
                clampPan();
                repaint();
            }
            @Override public void componentShown(ComponentEvent e) {
                clampPan();
                repaint();
            }
        });
    }

    private static boolean isPanStart(MouseEvent e) {
        return SwingUtilities.isLeftMouseButton(e)
                || SwingUtilities.isMiddleMouseButton(e)
                || SwingUtilities.isRightMouseButton(e)
                || e.isShiftDown();
    }

    private void clampPan() {
        BufferedImage img = (project == null) ? null : project.getFloorplanImage();
        if (img == null) return;

        int viewW = getWidth();
        int viewH = getHeight();
        if (viewW <= 0 || viewH <= 0) return;

        double imgW = img.getWidth() * zoom;
        double imgH = img.getHeight() * zoom;

        double margin = PAN_MARGIN_PX;

        if (imgW <= viewW) {
            panX = (viewW - imgW) / 2.0;
        } else {
            double minX = viewW - imgW - margin;
            double maxX = margin;
            panX = clamp(panX, minX, maxX);
        }

        if (imgH <= viewH) {
            panY = (viewH - imgH) / 2.0;
        } else {
            double minY = viewH - imgH - margin;
            double maxY = margin;
            panY = clamp(panY, minY, maxY);
        }
    }

    private static double clamp(double v, double lo, double hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    @Override
    public void addNotify() {
        super.addNotify();
        ensureAnimTimer();
        clampPan();
    }

    @Override
    public void removeNotify() {
        if (animTimer != null) animTimer.stop();
        super.removeNotify();
    }

    private void ensureAnimTimer() {
        if (animTimer != null) return;

        animTimer = new javax.swing.Timer(ANIM_FPS_MS, e -> {
            if (!isShowing()) return;
            if (engine == null) return;

            int step = safeInt(() -> engine.getCurrentInterval(), 0);

            long now = System.currentTimeMillis();
            if (step != lastEngineStep) {
                lastEngineStep = step;
                stepStartMs = now;
                stepAlpha01 = 0.0;
            } else {
                long dt = Math.max(0L, now - stepStartMs);
                stepAlpha01 = Math.min(1.0, dt / (double) Math.max(50, animStepMs));
            }
            repaint();
        });
        animTimer.setCoalesce(true);
        animTimer.start();
    }

    public void setEngine(SimulationEngine engine) {
        this.engine = engine;
        lastEngineStep = Integer.MIN_VALUE;
        stepAlpha01 = 1.0;
        repaint();
    }

    public void setProject(FloorplanProject projectCopy) {
        this.project = projectCopy;
        rebuildBindings();
        clampPan();
        repaint();
    }

    public void setSlotSpacingPx(int px) {
        this.slotSpacingPx = Math.max(4, px);
        repaint();
    }

    public void setPathStridePx(int px) {
        this.pathStridePx = Math.max(1, px);
        rebuildBindings();
        repaint();
    }

    public void resetView() {
        zoom = 1.0;
        panX = 0;
        panY = 0;
        clampPan();
        repaint();
    }

    /**
     * Called by SimulationFrame via reflection (best-effort).
     * Now affects BOTH:
     *  - within-interval walk animation
     *  - floorplan TravelTimeProvider (engine scheduling) when present
     */
    public void setWalkSpeedMps(double mps) {
        if (Double.isNaN(mps) || Double.isInfinite(mps)) return;
        walkSpeedMps = Math.max(0.20, Math.min(3.50, mps));

        // Visual interpolation tuning
        double t = (walkSpeedMps - 0.20) / (3.50 - 0.20);
        t = Math.max(0.0, Math.min(1.0, t));
        animStepMs = (int) Math.round(1200 + (250 - 1200) * t);
        animStepMs = Math.max(150, Math.min(1600, animStepMs));

        // ✅ Propagate into floorplan travel-time provider if engine uses one
        if (engine != null) {
            try {
                TravelTimeProvider prov = engine.getTravelTimeProvider();
                if (prov != null) {
                    try {
                        Method sm = prov.getClass().getMethod("setWalkSpeedMps", double.class);
                        sm.invoke(prov, walkSpeedMps);
                    } catch (Throwable ignored) { }
                }
            } catch (Throwable ignored) { }
        }
    }

    private void rebuildBindings() {
        nearestCheckpointByTicket.clear();

        if (project == null) {
            bindings = null;
            pathCache = null;
            return;
        }
        bindings = new FloorplanBindings(project);
        pathCache = new PathCache(bindings.getMask(), pathStridePx, true);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();

        try {
            BufferedImage img = (project == null) ? null : project.getFloorplanImage();
            if (img == null) {
                g2.setColor(Color.DARK_GRAY);
                g2.drawString("No floorplan image loaded.", 20, 20);
                return;
            }

            AffineTransform at = new AffineTransform();
            at.translate(panX, panY);
            at.scale(zoom, zoom);
            g2.setTransform(at);

            g2.drawImage(img, 0, 0, null);
            drawZones(g2);

            if (engine == null || bindings == null || pathCache == null) return;

            int curStep = safeInt(() -> engine.getCurrentInterval(), 0);
            double timeNow = curStep + stepAlpha01;

            paintCurStep = curStep;
            rebuildFlightTimeCaches();

            Map<Integer, List<Passenger>> pendingToTicket = readPendingMap(engine,
                    new String[]{"getPendingToTicket", "getPendingToTC", "getPendingToCounter"},
                    new String[]{"pendingToTicket", "pendingToTC", "pendingToCounter"});

            Map<Integer, List<Passenger>> pendingToCP = readPendingMap(engine,
                    new String[]{"getPendingToCP", "getPendingToCheckpoint"},
                    new String[]{"pendingToCP", "pendingToCheckpoint"});

            Map<Integer, List<Passenger>> pendingToHold = readPendingMap(engine,
                    new String[]{"getPendingToHold", "getPendingToHoldroom"},
                    new String[]{"pendingToHold", "pendingToHoldroom"});

            Set<Passenger> inTransitToTicket = collectAll(pendingToTicket);
            Set<Passenger> inTransitToCP     = collectAll(pendingToCP);
            Set<Passenger> inTransitToHold   = collectAll(pendingToHold);

            Map<Passenger, Integer> ticketDoneOf     = indexOf(engine.getCompletedTicketLines());
            Map<Passenger, Integer> checkpointDoneOf = indexOf(engine.getCompletedCheckpointLines());

            // 0) walking: spawn -> ticket counter (if pending exists)
            if (!pendingToTicket.isEmpty()) {
                Point spawn = getSpawnAnchorFallback();
                if (spawn != null) {
                    for (Map.Entry<Integer, List<Passenger>> e : pendingToTicket.entrySet()) {
                        int arriveStep = e.getKey();
                        for (Passenger p : safeList(e.getValue())) {
                            if (p == null) continue;
                            if (!shouldRenderPassengerNow(p)) continue;

                            int startStep = passengerArrivalStep(p, curStep);
                            double t01 = frac01(timeNow, startStep, arriveStep);

                            int ticketIdx = passengerIndex(p,
                                    new String[]{"getAssignedTicketCounterIndex", "getTicketCounterIndex", "getTargetTicketCounterIndex", "getChosenTicketCounterIndex"},
                                    new String[]{"assignedTicketCounterIndex", "ticketCounterIndex", "targetTicketCounterIndex", "chosenTicketCounterIndex"},
                                    -1);

                            if (ticketIdx < 0) {
                                ticketIdx = findInLines(engine.getTicketLines(), p, -1);
                            }
                            if (ticketIdx < 0) ticketIdx = 0;

                            Point b = bindings.getTicketAnchor(ticketIdx);
                            Point pos = walkPoint(spawn, b, t01);
                            drawPassenger(g2, p, pos, 7);
                        }
                    }
                }
            }

            // 1) walking: ticket -> checkpoint (pending)
            for (Map.Entry<Integer, List<Passenger>> e : pendingToCP.entrySet()) {
                int arriveStep = e.getKey();
                for (Passenger p : safeList(e.getValue())) {
                    if (p == null) continue;
                    if (!shouldRenderPassengerNow(p)) continue;

                    int startStep = passengerTicketDoneStep(p, curStep);
                    double t01 = frac01(timeNow, startStep, arriveStep);

                    int ticketIdx = passengerIndex(p,
                            new String[]{"getAssignedTicketCounterIndex", "getTicketCounterIndex", "getTargetTicketCounterIndex", "getChosenTicketCounterIndex"},
                            new String[]{"assignedTicketCounterIndex", "ticketCounterIndex", "targetTicketCounterIndex", "chosenTicketCounterIndex"},
                            -1);
                    if (ticketIdx < 0) ticketIdx = ticketDoneOf.getOrDefault(p, 0);

                    Point a = bindings.getTicketAnchor(ticketIdx);

                    int cpIdx = passengerIndex(p,
                            new String[]{"getAssignedCheckpointIndex", "getCheckpointIndex", "getTargetCheckpointIndex", "getChosenCheckpointIndex"},
                            new String[]{"assignedCheckpointIndex", "checkpointIndex", "targetCheckpointIndex", "chosenCheckpointIndex"},
                            -1);

                    Point b = (cpIdx >= 0) ? bindings.getCheckpointAnchor(cpIdx) : getNearestCheckpointForTicket(ticketIdx, a);

                    Point pos = walkPoint(a, b, t01);
                    drawPassenger(g2, p, pos, 7);
                }
            }

            // 2) walking: checkpoint -> holdroom (pending)
            for (Map.Entry<Integer, List<Passenger>> e : pendingToHold.entrySet()) {
                int arriveStep = e.getKey();
                for (Passenger p : safeList(e.getValue())) {
                    if (p == null) continue;
                    if (!shouldRenderPassengerNow(p)) continue;

                    int startStep = passengerCheckpointDoneStep(p, curStep);
                    double t01 = frac01(timeNow, startStep, arriveStep);

                    int cpIdx = passengerIndex(p,
                            new String[]{"getAssignedCheckpointIndex", "getCheckpointIndex", "getTargetCheckpointIndex", "getChosenCheckpointIndex"},
                            new String[]{"assignedCheckpointIndex", "checkpointIndex", "targetCheckpointIndex", "chosenCheckpointIndex"},
                            -1);

                    if (cpIdx < 0) cpIdx = checkpointDoneOf.getOrDefault(p, 0);

                    Point a = bindings.getCheckpointAnchor(cpIdx);

                    int holdIdx = passengerIndex(p,
                            new String[]{"getAssignedHoldRoomIndex", "getAssignedHoldroomIndex", "getHoldRoomIndex", "getHoldroomIndex", "getTargetHoldRoomIndex"},
                            new String[]{"assignedHoldRoomIndex", "assignedHoldroomIndex", "holdRoomIndex", "holdroomIndex", "targetHoldRoomIndex"},
                            -1);
                    if (holdIdx < 0) holdIdx = 0;

                    Point b = bindings.getHoldroomAnchor(holdIdx);

                    Point pos = walkPoint(a, b, t01);
                    drawPassenger(g2, p, pos, 7);
                }
            }

            // 3) ticket queues (skip those walking)
            List<LinkedList<Passenger>> ticketLines = engine.getTicketLines();
            for (int c = 0; c < ticketLines.size(); c++) {
                List<Point> slots = bindings.getTicketQueueSlots(c, slotSpacingPx);
                Point anchor = bindings.getTicketAnchor(c);

                Set<Passenger> skip = new HashSet<>();
                skip.addAll(inTransitToTicket);
                skip.addAll(inTransitToCP);

                drawQueue(g2, ticketLines.get(c), slots, anchor, skip, 7);
            }

            // 4) completed ticket staging
            for (int c = 0; c < engine.getCompletedTicketLines().size(); c++) {
                Point anchor = bindings.getTicketAnchor(c);
                List<Passenger> visible = engine.getVisibleCompletedTicketLine(c);
                drawStagingAtAnchor(g2, visible, anchor, inTransitToCP, 7);
            }

            // 5) checkpoint queues
            List<LinkedList<Passenger>> cpLines = engine.getCheckpointLines();
            for (int c = 0; c < cpLines.size(); c++) {
                List<Point> slots = bindings.getCheckpointQueueSlots(c, slotSpacingPx);
                Point anchor = bindings.getCheckpointAnchor(c);
                drawQueue(g2, cpLines.get(c), slots, anchor, inTransitToHold, 7);
            }

            // 6) completed checkpoint staging
            List<LinkedList<Passenger>> doneCP = engine.getCompletedCheckpointLines();
            for (int c = 0; c < doneCP.size(); c++) {
                Point anchor = bindings.getCheckpointAnchor(c);
                drawStagingAtAnchor(g2, doneCP.get(c), anchor, inTransitToHold, 7);
            }

            // 7) hold rooms
            List<LinkedList<Passenger>> holdLines = engine.getHoldRoomLines();
            for (int h = 0; h < holdLines.size(); h++) {
                List<Point> slots = bindings.getHoldroomAreaSlots(h, slotSpacingPx);
                Point anchor = bindings.getHoldroomAnchor(h);
                drawQueue(g2, holdLines.get(h), slots, anchor, Collections.emptySet(), 8);
            }

        } finally {
            g2.dispose();
        }
    }

    // ----------------- Routing helpers -----------------

    private Point snapToWalkable(Point p) {
        if (p == null || bindings == null) return p;
        WalkMask m = bindings.getMask();
        if (m == null) return p;
        Point s = AStarRouter.snapToNearestWalkable(m, p, Math.max(1, pathStridePx), 240);
        return (s != null) ? s : p;
    }

    private Point getNearestCheckpointForTicket(int ticketIdx, Point fromAnchor) {
        if (bindings == null) return null;
        int n = safeInt(() -> bindings.checkpointCount(), 0);
        if (n <= 0) return null;

        Integer cachedIdx = nearestCheckpointByTicket.get(ticketIdx);
        if (cachedIdx != null) {
            Point c = bindings.getCheckpointAnchor(cachedIdx);
            if (c != null) return c;
        }

        Point from = (fromAnchor != null) ? fromAnchor : bindings.getTicketAnchor(ticketIdx);
        if (from == null) from = bindings.getCheckpointAnchor(0);

        int bestI = 0;
        double bestScore = Double.POSITIVE_INFINITY;

        for (int i = 0; i < n; i++) {
            Point c = bindings.getCheckpointAnchor(i);
            if (c == null) continue;

            List<Point> path = pathCache.path(from, c);
            double score = (path != null && path.size() >= 2) ? PathCache.polylineLengthPixels(path) : from.distance(c);

            if (score < bestScore) {
                bestScore = score;
                bestI = i;
            }
        }

        nearestCheckpointByTicket.put(ticketIdx, bestI);
        return bindings.getCheckpointAnchor(bestI);
    }

    private Point walkPoint(Point a, Point b, double t01) {
        if (a == null && b == null) return null;
        if (a == null) return b;
        if (b == null) return a;

        double t = Math.max(0.0, Math.min(1.0, t01));

        // ✅ snap endpoints to walkable so drawing doesn't cut through walls if anchors are inside blocked pixels
        Point aa = snapToWalkable(a);
        Point bb = snapToWalkable(b);

        List<Point> path = pathCache.path(aa, bb);
        if (path != null && path.size() >= 2) {
            return pathCache.pointAlong(path, t);
        }

        int x = (int) Math.round(aa.x + (bb.x - aa.x) * t);
        int y = (int) Math.round(aa.y + (bb.y - aa.y) * t);
        return new Point(x, y);
    }

    private static double frac01(double now, int start, int end) {
        if (end <= start) return 1.0;
        double t = (now - start) / (double) (end - start);
        if (t < 0) return 0.0;
        if (t > 1) return 1.0;
        return t;
    }

    // ----------------- Zone rendering -----------------

    private void drawZones(Graphics2D g2) {
        if (project == null) return;

        java.util.List<sim.floorplan.model.Zone> zones = project.getZones();
        if (zones == null) return;

        g2.setStroke(new BasicStroke(2f));

        for (sim.floorplan.model.Zone z : zones) {
            if (z == null || z.getType() == null) continue;

            if (z.getType().hasArea() && z.getArea() != null && z.getArea().npoints >= 3) {
                g2.setColor(new Color(0, 0, 0, 70));
                g2.drawPolygon(z.getArea());
            }
            if (z.getType().hasAnchor() && z.getAnchor() != null) {
                Point a = z.getAnchor();
                g2.setColor(new Color(0, 0, 0, 150));
                g2.fillOval(a.x - 4, a.y - 4, 8, 8);
            }
        }
    }

    private Point getSpawnAnchorFallback() {
        try {
            Method m = bindings.getClass().getMethod("getSpawnAnchor");
            Object out = m.invoke(bindings);
            if (out instanceof Point) return (Point) out;
        } catch (Throwable ignored) {}

        if (project != null && project.getZones() != null) {
            for (sim.floorplan.model.Zone z : project.getZones()) {
                if (z == null || z.getType() == null) continue;
                try {
                    if ("SPAWN".equals(z.getType().name()) && z.getAnchor() != null) {
                        return z.getAnchor();
                    }
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    // ----------------- Pending map reflection helpers -----------------

    @SuppressWarnings("unchecked")
    private static Map<Integer, List<Passenger>> readPendingMap(SimulationEngine engine, String[] getterNames, String[] fieldNames) {
        if (engine == null) return Collections.emptyMap();

        if (getterNames != null) {
            for (String getterName : getterNames) {
                try {
                    Method m = engine.getClass().getMethod(getterName);
                    Object out = m.invoke(engine);
                    if (out instanceof Map) return (Map<Integer, List<Passenger>>) out;
                } catch (Throwable ignored) {}
            }
        }

        if (fieldNames != null) {
            for (String fieldName : fieldNames) {
                try {
                    Field f = engine.getClass().getDeclaredField(fieldName);
                    f.setAccessible(true);
                    Object out = f.get(engine);
                    if (out instanceof Map) return (Map<Integer, List<Passenger>>) out;
                } catch (Throwable ignored) {}
            }
        }

        return Collections.emptyMap();
    }

    // ----------------- Queue + passenger drawing -----------------

    private void drawQueue(Graphics2D g2,
                           List<Passenger> passengers,
                           List<Point> slots,
                           Point fallbackAnchor,
                           Set<Passenger> skip,
                           int size) {
        if (passengers == null) return;

        int i = 0;
        for (Passenger p : passengers) {
            if (p == null) continue;
            if (!shouldRenderPassengerNow(p)) continue;
            if (skip != null && skip.contains(p)) continue;

            Point pos = null;
            if (slots != null && i < slots.size()) pos = slots.get(i);
            if (pos == null) pos = fallbackAnchor;

            drawPassenger(g2, p, pos, size);
            i++;
        }
    }

    private void drawStagingAtAnchor(Graphics2D g2,
                                     List<Passenger> passengers,
                                     Point anchor,
                                     Set<Passenger> skip,
                                     int size) {
        if (passengers == null || anchor == null) return;

        int k = 0;
        for (Passenger p : passengers) {
            if (p == null) continue;
            if (!shouldRenderPassengerNow(p)) continue;
            if (skip != null && skip.contains(p)) continue;

            double ang = (k * 0.75);
            int r = 10 + (k / 10) * 6;
            Point pos = new Point(
                    anchor.x + (int) Math.round(Math.cos(ang) * r),
                    anchor.y + (int) Math.round(Math.sin(ang) * r)
            );
            drawPassenger(g2, p, pos, size);
            k++;
        }
    }

    private void drawPassenger(Graphics2D g2, Passenger p, Point pos, int size) {
        if (p == null || pos == null) return;

        Flight f = safeFlight(p);
        Flight.ShapeType shape = (f == null) ? Flight.ShapeType.CIRCLE : f.getShape();

        boolean inPerson = isInPerson(p);
        boolean missed = isMissed(p);

        Color fill = inPerson ? new Color(30, 120, 255, 210) : new Color(30, 30, 30, 200);
        if (missed) fill = new Color(200, 0, 0, 220);

        g2.setColor(fill);
        drawShapeSafe(g2, shape, pos.x, pos.y, size);

        g2.setColor(new Color(255, 255, 255, 200));
        g2.setStroke(new BasicStroke(1.2f));
        drawShapeOutlineSafe(g2, shape, pos.x, pos.y, size);
    }

    // ----------------- Missed visibility policy -----------------

    private void rebuildFlightTimeCaches() {
        departureIntervalByFlight.clear();
        boardingCloseIntervalByFlight.clear();

        if (engine == null) return;

        List<Flight> flights;
        try {
            flights = engine.getFlights();
        } catch (Throwable t) {
            flights = null;
        }
        if (flights == null || flights.isEmpty()) return;

        int arrivalSpanMin = engineInt(engine, 0, "getArrivalSpan", "getArrivalSpanMinutes", "arrivalSpanMinutes");
        LocalTime firstDep = flights.stream()
                .filter(Objects::nonNull)
                .map(Flight::getDepartureTime)
                .filter(Objects::nonNull)
                .min(LocalTime::compareTo)
                .orElse(LocalTime.MIDNIGHT);

        paintStartTime = firstDep.minusMinutes(Math.max(0, arrivalSpanMin));
        paintStartMinutesOfDay = timeToMinutesOfDay(paintStartTime);

        paintBoardingCloseOffsetMin = engineInt(engine, 20,
                "getBoardingCloseMinutes", "getBoardingCloseOffsetMinutes", "boardingCloseMinutes");

        for (Flight f : flights) {
            if (f == null || f.getDepartureTime() == null) continue;
            int depInterval = minutesFromStartTo(paintStartMinutesOfDay, f.getDepartureTime());
            departureIntervalByFlight.put(f, depInterval);

            int closeInterval = Math.max(0, depInterval - Math.max(0, paintBoardingCloseOffsetMin));
            boardingCloseIntervalByFlight.put(f, closeInterval);
        }
    }

    private boolean shouldRenderPassengerNow(Passenger p) {
        if (p == null) return false;

        if (!isMissed(p)) return true;

        Flight f = safeFlight(p);
        if (f == null) return true;

        Integer dep = departureIntervalByFlight.get(f);
        Integer close = boardingCloseIntervalByFlight.get(f);
        if (dep == null || close == null) return true;

        int holdEntry = passengerHoldEntryStep(p, -1);
        boolean reachedHold = holdEntry >= 0;

        int vanishAt = reachedHold ? dep : close;
        return paintCurStep < vanishAt;
    }

    private static int timeToMinutesOfDay(LocalTime t) {
        if (t == null) return 0;
        return t.getHour() * 60 + t.getMinute();
    }

    private static int minutesFromStartTo(int startMinutesOfDay, LocalTime target) {
        int tgt = timeToMinutesOfDay(target);
        int d = tgt - startMinutesOfDay;
        if (d < 0) d += 24 * 60;
        return d;
    }

    // ----------------- Passenger reflection helpers -----------------

    private static Flight safeFlight(Passenger p) {
        if (p == null) return null;
        try {
            Method m = p.getClass().getMethod("getFlight");
            Object out = m.invoke(p);
            if (out instanceof Flight) return (Flight) out;
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean isInPerson(Passenger p) {
        if (p == null) return false;

        try {
            Method m = p.getClass().getMethod("isInPerson");
            Object out = m.invoke(p);
            if (out instanceof Boolean) return (Boolean) out;
        } catch (Throwable ignored) {}

        try {
            Method m = p.getClass().getMethod("getInPerson");
            Object out = m.invoke(p);
            if (out instanceof Boolean) return (Boolean) out;
        } catch (Throwable ignored) {}

        Boolean f = tryFieldBool(p, "inPerson", "isInPerson");
        return f != null && f;
    }

    private static boolean isMissed(Passenger p) {
        if (p == null) return false;

        try {
            Method m = p.getClass().getMethod("isMissed");
            Object out = m.invoke(p);
            if (out instanceof Boolean) return (Boolean) out;
        } catch (Throwable ignored) {}

        try {
            Method m = p.getClass().getMethod("getMissed");
            Object out = m.invoke(p);
            if (out instanceof Boolean) return (Boolean) out;
        } catch (Throwable ignored) {}

        Boolean f = tryFieldBool(p, "missed", "isMissed");
        return f != null && f;
    }

    private static int passengerArrivalStep(Passenger p, int fallback) {
        Integer v = tryInvokeInt(p, "getArrivalMinute", "getArrivalInterval", "getArrivalStep", "getSpawnMinute", "getSpawnInterval");
        if (v != null && v >= 0) return v;
        Integer f = tryFieldInt(p, "arrivalMinute", "arrivalInterval", "arrivalStep", "spawnMinute", "spawnInterval");
        if (f != null && f >= 0) return f;
        return Math.max(0, fallback - 1);
    }

    private static int passengerTicketDoneStep(Passenger p, int fallback) {
        Integer v = tryInvokeInt(p, "getTicketCompletionMinute", "getTicketCompleteMinute", "getTicketDoneMinute", "getTicketCompletionInterval");
        if (v != null && v >= 0) return v;
        Integer f = tryFieldInt(p, "ticketCompletionMinute", "ticketCompleteMinute", "ticketDoneMinute", "ticketCompletionInterval");
        if (f != null && f >= 0) return f;
        return Math.max(0, fallback - 1);
    }

    private static int passengerCheckpointDoneStep(Passenger p, int fallback) {
        Integer v = tryInvokeInt(p, "getCheckpointCompletionMinute", "getCheckpointCompleteMinute", "getCheckpointDoneMinute", "getCheckpointCompletionInterval");
        if (v != null && v >= 0) return v;
        Integer f = tryFieldInt(p, "checkpointCompletionMinute", "checkpointCompleteMinute", "checkpointDoneMinute", "checkpointCompletionInterval");
        if (f != null && f >= 0) return f;
        return Math.max(0, fallback - 1);
    }

    private static int passengerHoldEntryStep(Passenger p, int fallback) {
        Integer v = tryInvokeInt(p, "getHoldRoomEntryMinute", "getHoldroomEntryMinute", "getHoldEntryMinute");
        if (v != null && v >= 0) return v;
        Integer f = tryFieldInt(p, "holdRoomEntryMinute", "holdroomEntryMinute", "holdEntryMinute");
        if (f != null && f >= 0) return f;
        return fallback;
    }

    private static int passengerIndex(Passenger p, String[] methodNames, String[] fieldNames, int fallback) {
        if (p == null) return fallback;

        if (methodNames != null) {
            for (String n : methodNames) {
                Integer v = tryInvokeInt(p, n);
                if (v != null && v >= 0) return v;
            }
        }
        if (fieldNames != null) {
            for (String f : fieldNames) {
                Integer v = tryFieldInt(p, f);
                if (v != null && v >= 0) return v;
            }
        }
        return fallback;
    }

    private static Integer tryInvokeInt(Object target, String... methodNames) {
        if (target == null || methodNames == null) return null;
        for (String name : methodNames) {
            try {
                Method m = target.getClass().getMethod(name);
                Class<?> rt = m.getReturnType();
                if (rt == int.class || rt == Integer.class) {
                    Object out = m.invoke(target);
                    return (out == null) ? null : ((Number) out).intValue();
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Integer tryFieldInt(Object target, String... fieldNames) {
        if (target == null || fieldNames == null) return null;
        for (String fn : fieldNames) {
            try {
                Field f = target.getClass().getDeclaredField(fn);
                f.setAccessible(true);
                Object out = f.get(target);
                if (out instanceof Number) return ((Number) out).intValue();
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Boolean tryFieldBool(Object target, String... fieldNames) {
        if (target == null || fieldNames == null) return null;
        for (String fn : fieldNames) {
            try {
                Field f = target.getClass().getDeclaredField(fn);
                f.setAccessible(true);
                Object out = f.get(target);
                if (out instanceof Boolean) return (Boolean) out;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static int engineInt(SimulationEngine eng, int fallback, String... methodOrFieldNames) {
        if (eng == null || methodOrFieldNames == null) return fallback;

        for (String name : methodOrFieldNames) {
            if (name == null || name.trim().isEmpty()) continue;

            try {
                Method m = eng.getClass().getMethod(name);
                Object out = m.invoke(eng);
                if (out instanceof Number) return ((Number) out).intValue();
            } catch (Throwable ignored) {}

            try {
                Field f = eng.getClass().getDeclaredField(name);
                f.setAccessible(true);
                Object out = f.get(eng);
                if (out instanceof Number) return ((Number) out).intValue();
            } catch (Throwable ignored) {}
        }

        return fallback;
    }

    // ----------------- Shapes -----------------

    private void drawShapeSafe(Graphics2D g2, Flight.ShapeType s, int cx, int cy, int r) {
        String name = (s == null) ? "CIRCLE" : s.name();
        switch (name) {
            case "TRIANGLE": {
                Polygon p = new Polygon(
                        new int[]{cx, cx - r, cx + r},
                        new int[]{cy - r, cy + r, cy + r}, 3);
                g2.fillPolygon(p);
                break;
            }
            case "SQUARE":
                g2.fillRect(cx - r, cy - r, 2 * r, 2 * r);
                break;
            case "PENTAGON":
                g2.fillPolygon(regularPolygon(cx, cy, r, 5));
                break;
            case "HEXAGON":
                g2.fillPolygon(regularPolygon(cx, cy, r, 6));
                break;
            case "OCTAGON":
                g2.fillPolygon(regularPolygon(cx, cy, r, 8));
                break;
            default:
                g2.fillOval(cx - r, cy - r, 2 * r, 2 * r);
        }
    }

    private void drawShapeOutlineSafe(Graphics2D g2, Flight.ShapeType s, int cx, int cy, int r) {
        String name = (s == null) ? "CIRCLE" : s.name();
        switch (name) {
            case "TRIANGLE": {
                Polygon p = new Polygon(
                        new int[]{cx, cx - r, cx + r},
                        new int[]{cy - r, cy + r, cy + r}, 3);
                g2.drawPolygon(p);
                break;
            }
            case "SQUARE":
                g2.drawRect(cx - r, cy - r, 2 * r, 2 * r);
                break;
            case "PENTAGON":
                g2.drawPolygon(regularPolygon(cx, cy, r, 5));
                break;
            case "HEXAGON":
                g2.drawPolygon(regularPolygon(cx, cy, r, 6));
                break;
            case "OCTAGON":
                g2.drawPolygon(regularPolygon(cx, cy, r, 8));
                break;
            default:
                g2.drawOval(cx - r, cy - r, 2 * r, 2 * r);
        }
    }

    private static Polygon regularPolygon(int cx, int cy, int r, int n) {
        int[] xs = new int[n];
        int[] ys = new int[n];
        for (int i = 0; i < n; i++) {
            double a = -Math.PI / 2 + i * (2 * Math.PI / n);
            xs[i] = cx + (int) Math.round(Math.cos(a) * r);
            ys[i] = cy + (int) Math.round(Math.sin(a) * r);
        }
        return new Polygon(xs, ys, n);
    }

    // ----------------- Utilities -----------------

    private static <T> List<T> safeList(List<T> list) {
        return (list == null) ? Collections.emptyList() : list;
    }

    private static Set<Passenger> collectAll(Map<Integer, List<Passenger>> m) {
        Set<Passenger> s = new HashSet<>();
        if (m == null) return s;
        for (List<Passenger> list : m.values()) {
            if (list == null) continue;
            for (Passenger p : list) if (p != null) s.add(p);
        }
        return s;
    }

    private static Map<Passenger, Integer> indexOf(List<LinkedList<Passenger>> lines) {
        Map<Passenger, Integer> map = new HashMap<>();
        if (lines == null) return map;
        for (int i = 0; i < lines.size(); i++) {
            for (Passenger p : lines.get(i)) {
                if (p != null && !map.containsKey(p)) map.put(p, i);
            }
        }
        return map;
    }

    private static int findInLines(List<LinkedList<Passenger>> lines, Passenger target, int fallback) {
        if (lines == null || target == null) return fallback;
        for (int i = 0; i < lines.size(); i++) {
            LinkedList<Passenger> line = lines.get(i);
            if (line == null) continue;
            for (Passenger p : line) {
                if (p == target) return i;
            }
        }
        return fallback;
    }

    private interface IntSupplierWithThrow { int get() throws Exception; }
    private static int safeInt(IntSupplierWithThrow s, int fallback) {
        try { return s.get(); } catch (Throwable t) { return fallback; }
    }
}
