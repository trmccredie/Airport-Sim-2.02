package sim.ui;

import sim.model.Flight;

import java.io.Serializable;
import java.util.*;

/**
 * Configuration for a single ticket counter:
 * - id: sequential counter number
 * - rate: passengers served per minute
 * - allowedFlights: which flights this counter accepts (empty = all)
 */
public class TicketCounterConfig implements Serializable {
    private static final long serialVersionUID = 1L;

    private int id;
    private double rate;
    private Set<Flight> allowedFlights;

    /** Full constructor: supply id, rate, and explicit set (empty = all) */
    public TicketCounterConfig(int id, double rate, Set<Flight> allowedFlights) {
        this.id = id;
        this.rate = rate;
        // LinkedHashSet => stable iteration in UI + toString
        this.allowedFlights = (allowedFlights == null) ? new LinkedHashSet<>() : new LinkedHashSet<>(allowedFlights);
    }

    /** Default: rate=1.0, accepts all flights (empty set) */
    public TicketCounterConfig(int id) {
        this(id, 1.0, new LinkedHashSet<>());
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public double getRate() { return rate; }
    public void setRate(double rate) { this.rate = rate; }

    public Set<Flight> getAllowedFlights() { return allowedFlights; }
    public void setAllowedFlights(Set<Flight> allowedFlights) {
        this.allowedFlights = (allowedFlights == null) ? new LinkedHashSet<>() : new LinkedHashSet<>(allowedFlights);
    }

    /** true if no restrictions (empty = all flights) */
    public boolean isAllFlights() {
        return allowedFlights == null || allowedFlights.isEmpty();
    }

    /**
     * true if this counter will accept passengers for flight f
     * (robust against flight object identity differences by also comparing flight numbers)
     */
    public boolean accepts(Flight f) {
        if (f == null) return false;
        if (isAllFlights()) return true;

        if (allowedFlights.contains(f)) return true;

        String num = (f.getFlightNumber() == null) ? null : f.getFlightNumber().trim();
        if (num == null || num.isEmpty()) return false;

        for (Flight af : allowedFlights) {
            if (af == null || af.getFlightNumber() == null) continue;
            if (num.equals(af.getFlightNumber().trim())) return true;
        }
        return false;
    }

    /** Convenience: flight numbers for UI/debug */
    public List<String> getAllowedFlightNumbersSorted() {
        if (isAllFlights()) return Collections.emptyList();
        List<String> nums = new ArrayList<>();
        for (Flight f : allowedFlights) {
            if (f == null || f.getFlightNumber() == null) continue;
            String s = f.getFlightNumber().trim();
            if (!s.isEmpty()) nums.add(s);
        }
        nums.sort(String::compareToIgnoreCase);
        return nums;
    }

    @Override
    public String toString() {
        if (isAllFlights()) return "All flights";
        List<String> nums = getAllowedFlightNumbersSorted();
        return String.join(", ", nums);
    }
}
