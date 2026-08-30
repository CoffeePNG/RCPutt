package com.redcoffee.puttputt.surface;

/**
 * A named bundle of physics modifiers. Sand traps, ice and push blocks are all
 * the same system with different numbers - which is the whole point of the registry.
 */
public record Surface(
        String id,
        SurfaceType type,
        double friction,
        double restitution,
        int penalty,
        ResetMode reset,
        Impulse impulse,
        boolean preventRest) {

    /** Used when a block has no registry entry at all - behaves like plain green. */
    public static Surface fallback(String id, double friction) {
        return new Surface(id, SurfaceType.ROLL, friction, 0.0, 0, ResetMode.LAST_REST, null, false);
    }

    public boolean isWall() {
        return type == SurfaceType.WALL;
    }

    public boolean isHazard() {
        return type == SurfaceType.HAZARD;
    }

    public boolean isHole() {
        return type == SurfaceType.HOLE;
    }

    public boolean hasImpulse() {
        return impulse != null && impulse.strength() != 0.0;
    }

    public boolean isCurrent() {
        return type == SurfaceType.CURRENT;
    }

    /**
     * True while the ball must keep drifting rather than settle. Friction and the rest check are
     * both skipped here, which is what stops a ball parking in the middle of a river.
     */
    public boolean preventsRest() {
        return preventRest;
    }
}
