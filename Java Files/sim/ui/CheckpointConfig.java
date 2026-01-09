package sim.ui;

import java.io.Serializable;
import java.util.Objects;

/**
 * UI/Engine configuration for a single checkpoint.
 * User inputs passengers/hour (industry standard).
 * Engine consumes passengers/minute via getRatePerMinute().
 */
public class CheckpointConfig implements Serializable {
    private static final long serialVersionUID = 1L;

    private int id;

    // passengers per hour (industry standard input)
    private double ratePerHour = 120.0; // default 2/min

    public CheckpointConfig(int id) {
        this.id = id;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public double getRatePerHour() { return ratePerHour; }

    public void setRatePerHour(double ratePerHour) {
        this.ratePerHour = Math.max(0.0, ratePerHour);
    }

    /** Engine consumption helper */
    public double getRatePerMinute() {
        return ratePerHour / 60.0;
    }

    @Override
    public String toString() {
        return "CheckpointConfig{id=" + id + ", ratePerHour=" + ratePerHour + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CheckpointConfig)) return false;
        CheckpointConfig that = (CheckpointConfig) o;
        return id == that.id;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
