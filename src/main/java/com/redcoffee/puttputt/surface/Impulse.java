package com.redcoffee.puttputt.surface;

import com.redcoffee.puttputt.util.Vec3;

/**
 * A directional push applied while the ball rolls over an impulse surface.
 * The direction is normalised at construction so {@code strength} is the only knob that matters.
 */
public record Impulse(Vec3 direction, double strength) {

    public Impulse {
        direction = direction.normalize();
    }

    public Vec3 asVelocityDelta() {
        return direction.multiply(strength);
    }
}
