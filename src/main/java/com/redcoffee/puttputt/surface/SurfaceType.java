package com.redcoffee.puttputt.surface;

import java.util.Locale;

/** Behavioural class of a surface. Everything else is a modifier on top of it. */
public enum SurfaceType {
    /** Ordinary rollable ground. Only friction applies. */
    ROLL,
    /** Reflects the ball on the axis it was crossed on, scaled by restitution. */
    WALL,
    /** Costs strokes and resets the ball. */
    HAZARD,
    /** Adds a fixed impulse each tick the ball rolls over it (push block / booster pad). */
    IMPULSE,
    /**
     * A sustained flow - a river. Like {@link #IMPULSE} but usually weaker and spread over a wide
     * area, and normally paired with {@code preventRest} so the ball drifts on instead of settling
     * mid-stream.
     */
    CURRENT,
    /** The cup. Sinking is gated on speed, not just proximity. */
    HOLE;

    public static SurfaceType parse(String raw, SurfaceType fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "wall" -> WALL;
            case "hazard", "water", "oob" -> HAZARD;
            case "impulse", "booster", "push" -> IMPULSE;
            case "current", "river", "flow" -> CURRENT;
            case "hole", "cup" -> HOLE;
            default -> ROLL;
        };
    }
}
