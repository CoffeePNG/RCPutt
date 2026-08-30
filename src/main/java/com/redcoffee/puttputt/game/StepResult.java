package com.redcoffee.puttputt.game;

/** Outcome of a single physics tick for one ball. */
public enum StepResult {
    /** Still rolling. */
    MOVING,
    /** Came to rest this tick - the player may take the next stroke. */
    CAME_TO_REST,
    /** Entered a hazard: the ball was reset and a penalty applied. */
    HAZARD,
    /** Dropped in the cup. */
    SUNK
}
