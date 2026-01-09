package sim.floorplan.sim;

import sim.floorplan.model.FloorplanProject;

import java.awt.Point;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Routing-based TravelTimeProvider for floorplan simulation.
 *
 * Converts A* path length (pixels) into minutes using:
 *   seconds = (pixels * metersPerPixel) / walkSpeedMps
 *   minutes = ceil(seconds / 60)
 *
 * metersPerPixel:
 *  - If FloorplanProject later gains getMetersPerPixel() or a metersPerPixel field, we auto-read it.
 *  - Otherwise we use DEFAULT_METERS_PER_PIXEL as a placeholder.
 */
public class FloorplanTravelTimeProvider implements TravelTimeProvider {

    private static final double DEFAULT_METERS_PER_PIXEL = 0.05;

    private final FloorplanProject project;
    private final FloorplanBindings bindings;
    private final PathCache pathCache;

    private double walkSpeedMps;
    private double metersPerPixel = DEFAULT_METERS_PER_PIXEL;

    public FloorplanTravelTimeProvider(FloorplanProject project, double walkSpeedMps) {
        this(project, walkSpeedMps, 4, true);
    }

    public FloorplanTravelTimeProvider(FloorplanProject project, double walkSpeedMps, int stridePx, boolean allowDiagonal) {
        this.project = project;
        this.bindings = new FloorplanBindings(project);
        this.pathCache = new PathCache(bindings.getMask(), Math.max(1, stridePx), allowDiagonal);
        this.walkSpeedMps = Math.max(0.1, walkSpeedMps);

        // best-effort: pick up scale from project if it exists
        refreshMetersPerPixelFromProject();
    }

    public void setMetersPerPixel(double metersPerPixel) {
        this.metersPerPixel = (metersPerPixel > 0) ? metersPerPixel : DEFAULT_METERS_PER_PIXEL;
    }

    public double getMetersPerPixel() {
        refreshMetersPerPixelFromProject();
        return metersPerPixel;
    }

    public void setWalkSpeedMps(double walkSpeedMps) {
        this.walkSpeedMps = Math.max(0.1, walkSpeedMps);
    }

    public double getWalkSpeedMps() { return walkSpeedMps; }

    @Override
    public int minutesTicketToCheckpoint(int ticketCounterIdx, int checkpointIdx) {
        Point a = bindings.getTicketAnchor(ticketCounterIdx);
        Point b = bindings.getCheckpointAnchor(checkpointIdx);
        return minutesBetween(a, b);
    }

    @Override
    public int minutesCheckpointToHold(int checkpointIdx, int holdRoomIdx) {
        Point a = bindings.getCheckpointAnchor(checkpointIdx);
        Point b = bindings.getHoldroomAnchor(holdRoomIdx);
        return minutesBetween(a, b);
    }

    private int minutesBetween(Point a, Point b) {
        if (a == null || b == null) return 0;

        refreshMetersPerPixelFromProject();

        double px = pathLengthPixels(a, b);
        if (px <= 0) return 0;

        double mpp = Math.max(1e-9, metersPerPixel);
        double meters = px * mpp;
        double seconds = meters / Math.max(0.1, walkSpeedMps);

        int minutes = (int) Math.ceil(seconds / 60.0);
        return Math.max(1, minutes);
    }

    private double pathLengthPixels(Point a, Point b) {
        List<Point> path = pathCache.path(a, b);
        if (path != null && path.size() >= 2) {
            return PathCache.polylineLengthPixels(path);
        }
        return a.distance(b);
    }

    private void refreshMetersPerPixelFromProject() {
        if (project == null) return;

        // 1) getMetersPerPixel()
        try {
            Method m = project.getClass().getMethod("getMetersPerPixel");
            Object out = m.invoke(project);
            if (out instanceof Number) {
                double v = ((Number) out).doubleValue();
                if (v > 0) { metersPerPixel = v; return; }
            }
        } catch (Throwable ignored) {}

        // 2) field metersPerPixel
        try {
            Field f = project.getClass().getDeclaredField("metersPerPixel");
            f.setAccessible(true);
            Object out = f.get(project);
            if (out instanceof Number) {
                double v = ((Number) out).doubleValue();
                if (v > 0) { metersPerPixel = v; return; }
            }
        } catch (Throwable ignored) {}

        // fallback
        if (metersPerPixel <= 0) metersPerPixel = DEFAULT_METERS_PER_PIXEL;
    }
}
