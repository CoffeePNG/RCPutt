package com.redcoffee.puttputt.config;

/**
 * Ball-to-ball collision settings (RC-SPEC-PUTTPUTT-001 v2 s4.5).
 *
 * <p>Collision is affordable here only because play is turn-based: at most one ball is under power
 * at a time, so a strike resolves against resting balls rather than an n-body simulation.
 *
 * @param enabled       master toggle
 * @param restitution   fraction of the normal velocity handed to the struck ball
 * @param radius        ball radius in blocks; contact happens at twice this
 * @param allowKnockIn  whether a ball knocked into the cup counts as sunk for its owner
 */
public record BallCollisionConfig(boolean enabled, double restitution, double radius, boolean allowKnockIn) {

    public static final BallCollisionConfig DEFAULTS = new BallCollisionConfig(true, 0.85, 0.18, true);

    public BallCollisionConfig {
        if (restitution < 0.0 || restitution > 1.0) {
            throw new IllegalArgumentException("ball-collision restitution must be in [0, 1], got " + restitution);
        }
        if (radius <= 0.0) {
            throw new IllegalArgumentException("ball-collision radius must be positive, got " + radius);
        }
    }

    public double contactDistance() {
        return radius * 2.0;
    }
}
