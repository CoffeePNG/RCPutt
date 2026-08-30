package com.redcoffee.puttputt.config;

import java.util.Locale;

/**
 * Boss-bar power meter settings (RC-SPEC-PUTTPUTT-001 v2 s5).
 *
 * <p>A shovel has no vanilla draw animation, so the plugin owns the charge curve outright - which
 * is the point: it is fully tunable, and the bar is shown to the whole party so everyone watches
 * the putt being set up.
 *
 * @param mode        how the bar behaves while charging
 * @param minVelocity launch velocity at 0% power
 * @param maxVelocity launch velocity at 100% power
 * @param sweepTicks  ticks for one 0 -> 100 pass of the bar
 */
public record PowerMeterConfig(Mode mode, double minVelocity, double maxVelocity, int sweepTicks) {

    /** How the meter moves while the player holds the putter. */
    public enum Mode {
        /** Sweeps 0 -> 100 -> 0 on a loop; release locks in whatever it reads. Timing skill. */
        OSCILLATE,
        /** Rises to full and stays there; release fires at the current value. Simpler. */
        FILL;

        public static Mode parse(String raw, Mode fallback) {
            if (raw == null || raw.isBlank()) {
                return fallback;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "fill", "charge" -> FILL;
                case "oscillate", "sweep", "bounce" -> OSCILLATE;
                default -> fallback;
            };
        }
    }

    public static final PowerMeterConfig DEFAULTS = new PowerMeterConfig(Mode.OSCILLATE, 0.08, 0.9, 30);

    public PowerMeterConfig {
        if (sweepTicks <= 0) {
            throw new IllegalArgumentException("power-meter sweep ticks must be positive, got " + sweepTicks);
        }
        if (minVelocity < 0.0 || maxVelocity <= minVelocity) {
            throw new IllegalArgumentException(
                    "power-meter velocities must satisfy 0 <= min < max, got " + minVelocity + ".." + maxVelocity);
        }
    }

    /** Maps a 0..1 meter reading onto a launch speed. */
    public double velocityFor(double power) {
        double clamped = Math.clamp(power, 0.0, 1.0);
        return minVelocity + (maxVelocity - minVelocity) * clamped;
    }

    /**
     * The meter reading after {@code heldTicks} of charging. FILL saturates at 1; OSCILLATE runs a
     * triangle wave so the bar sweeps up and back down.
     */
    public double powerAt(int heldTicks) {
        double phase = (double) heldTicks / sweepTicks;
        if (mode == Mode.FILL) {
            return Math.min(1.0, phase);
        }
        double cycle = phase % 2.0;
        return cycle <= 1.0 ? cycle : 2.0 - cycle;
    }
}
