package com.redcoffee.puttputt.config;

/**
 * Tunable physics constants (RC-SPEC-PUTTPUTT-001 s3.3).
 *
 * <p>{@code maxVelocity} is the tunneling guard and must stay below one block per tick: a ball
 * moving further than a block between samples can step straight over a 1-block wall without ever
 * testing it. Capping speed is cheaper than sub-stepping the sweep and is invisible at putting
 * speeds, so the constructor refuses anything at or above 1.0.
 */
public record PhysicsConfig(
        double maxVelocity,
        double restEpsilon,
        double maxSinkSpeed,
        double sinkRadius,
        double maxPuttPower) {

    public static final PhysicsConfig DEFAULTS = new PhysicsConfig(0.9, 0.02, 0.25, 0.35, 0.9);

    public PhysicsConfig {
        if (!(maxVelocity > 0.0) || maxVelocity >= 1.0) {
            throw new IllegalArgumentException(
                    "maxVelocity must be in (0, 1) blocks/tick to keep the tunneling guard intact, got " + maxVelocity);
        }
        if (restEpsilon <= 0.0) {
            throw new IllegalArgumentException("restEpsilon must be positive, got " + restEpsilon);
        }
        if (sinkRadius <= 0.0) {
            throw new IllegalArgumentException("sinkRadius must be positive, got " + sinkRadius);
        }
        if (maxSinkSpeed <= 0.0) {
            throw new IllegalArgumentException("maxSinkSpeed must be positive, got " + maxSinkSpeed);
        }
        if (maxPuttPower <= 0.0) {
            throw new IllegalArgumentException("maxPuttPower must be positive, got " + maxPuttPower);
        }
        maxPuttPower = Math.min(maxPuttPower, maxVelocity);
    }
}
