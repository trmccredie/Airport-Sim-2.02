package sim.floorplan.sim;

import sim.floorplan.model.WalkMask;
import sim.floorplan.path.AStarRouter;

import java.awt.Point;
import java.util.*;

/** Small cache so repainting doesn't re-run A* constantly. */
public class PathCache {

    private static final class Key {
        final int ax, ay, bx, by, stride;
        Key(int ax, int ay, int bx, int by, int stride) {
            this.ax = ax; this.ay = ay; this.bx = bx; this.by = by; this.stride = stride;
        }
        @Override public boolean equals(Object o) {
            if (!(o instanceof Key)) return false;
            Key k = (Key) o;
            return ax==k.ax && ay==k.ay && bx==k.bx && by==k.by && stride==k.stride;
        }
        @Override public int hashCode() {
            return Objects.hash(ax, ay, bx, by, stride);
        }
    }

    private static final class Metrics {
        final double[] cum; // cumulative distance at each vertex
        final double total;
        Metrics(double[] cum, double total) {
            this.cum = cum;
            this.total = total;
        }
    }

    private final WalkMask mask;
    private final int stridePx;
    private final boolean allowDiagonal;

    private final Map<Key, List<Point>> cache = new HashMap<>();
    private final Map<List<Point>, Metrics> metricsByPath = new IdentityHashMap<>();

    // ✅ invalidate cached routes when mask changes
    private int lastMaskVersion;

    public PathCache(WalkMask mask, int stridePx, boolean allowDiagonal) {
        this.mask = mask;
        this.stridePx = Math.max(1, stridePx);
        this.allowDiagonal = allowDiagonal;
        this.lastMaskVersion = (mask == null) ? 0 : mask.getVersion();
    }

    public void clear() {
        cache.clear();
        metricsByPath.clear();
    }

    private void ensureFresh() {
        if (mask == null) return;
        int v = mask.getVersion();
        if (v != lastMaskVersion) {
            clear();
            lastMaskVersion = v;
        }
    }

    public List<Point> path(Point a, Point b) {
        if (mask == null || a == null || b == null) return null;

        ensureFresh();

        // ✅ canonicalize endpoints to nearest walkable so caches are stable (and routing doesn't start inside walls)
        Point aa = AStarRouter.snapToNearestWalkable(mask, a, stridePx, 240);
        Point bb = AStarRouter.snapToNearestWalkable(mask, b, stridePx, 240);
        if (aa == null || bb == null) return null;

        Key k = new Key(aa.x, aa.y, bb.x, bb.y, stridePx);
        List<Point> got = cache.get(k);
        if (got != null) return got;

        List<Point> p = AStarRouter.findPath(mask, aa, bb, stridePx, 2_000_000, allowDiagonal);
        cache.put(k, p);
        return p;
    }

    /**
     * Returns point at fraction t01 along the path measured by Euclidean distance.
     * (Much smoother than picking an index.)
     */
    public Point pointAlong(List<Point> path, double t01) {
        if (path == null || path.isEmpty()) return null;
        if (path.size() == 1) return path.get(0);

        ensureFresh();

        double t = Math.max(0.0, Math.min(1.0, t01));

        Metrics m = metricsByPath.get(path);
        if (m == null) {
            m = computeMetrics(path);
            metricsByPath.put(path, m);
        }

        if (m.total <= 0.0001) return path.get(0);

        double target = t * m.total;

        int i = 1;
        while (i < m.cum.length && m.cum[i] < target) i++;

        if (i <= 0) return path.get(0);
        if (i >= path.size()) return path.get(path.size() - 1);

        Point p0 = path.get(i - 1);
        Point p1 = path.get(i);

        double d0 = m.cum[i - 1];
        double d1 = m.cum[i];
        double seg = Math.max(0.0001, d1 - d0);
        double u = (target - d0) / seg;
        u = Math.max(0.0, Math.min(1.0, u));

        int x = (int) Math.round(p0.x + (p1.x - p0.x) * u);
        int y = (int) Math.round(p0.y + (p1.y - p0.y) * u);
        return new Point(x, y);
    }

    private static Metrics computeMetrics(List<Point> path) {
        int n = path.size();
        double[] cum = new double[n];
        cum[0] = 0.0;

        double total = 0.0;
        Point prev = path.get(0);

        for (int i = 1; i < n; i++) {
            Point cur = path.get(i);
            double d = 0.0;
            if (prev != null && cur != null) d = prev.distance(cur);
            total += d;
            cum[i] = total;
            prev = cur;
        }
        return new Metrics(cum, total);
    }

    /** Utility: polyline length in pixels (Euclidean). */
    public static double polylineLengthPixels(List<Point> path) {
        if (path == null || path.size() < 2) return 0.0;
        double sum = 0.0;
        Point prev = path.get(0);
        for (int i = 1; i < path.size(); i++) {
            Point cur = path.get(i);
            if (prev != null && cur != null) sum += prev.distance(cur);
            prev = cur;
        }
        return sum;
    }
}
