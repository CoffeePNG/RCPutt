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

    /** Nothing underneath the ball. The engine falls through this looking for real ground. */
    public static final Surface EMPTY =
            new Surface("empty", SurfaceType.EMPTY, 1.0, 0.0, 0, ResetMode.LAST_REST, null, false);

    /** Nothing underneath within reach either: the ball is gone. Costs a stroke, like any hazard. */
    public static Surface voidFall(int penalty) {
        return new Surface("void", SurfaceType.HAZARD, 1.0, 0.0, Math.max(0, penalty),
                ResetMode.LAST_REST, null, false);
    }

    public boolean isEmpty() {
        return type == SurfaceType.EMPTY;
    }

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
