package com.redcoffee.puttputt.game;

import com.redcoffee.puttputt.surface.Surface;

/**
 * What one physics tick did to a ball.
 *
 * @param result         coarse outcome the round logic switches on
 * @param surface        the surface the ball was rolling over this tick (never null)
 * @param penaltyStrokes strokes to add to the scorecard (non-zero only for hazards)
 * @param struck         a ball this one knocked into this tick, or null
 */
public record StepOutcome(StepResult result, Surface surface, int penaltyStrokes, BallState struck) {

    public static StepOutcome of(StepResult result, Surface surface) {
        return new StepOutcome(result, surface, 0, null);
    }
}
