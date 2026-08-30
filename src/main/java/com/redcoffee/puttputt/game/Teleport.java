package com.redcoffee.puttputt.game;

import com.redcoffee.puttputt.util.Vec3;

/**
 * A teleport pad: roll onto the source block and come out at the destination.
 *
 * @param destination where the ball reappears, as a ball-plane position
 * @param keepVelocity true to shoot out the far side carrying its speed, false to arrive stopped
 */
public record Teleport(Vec3 destination, boolean keepVelocity) {
}
