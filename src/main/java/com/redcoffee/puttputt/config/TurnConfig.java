package com.redcoffee.puttputt.config;

/**
 * Turn engine settings (RC-SPEC-PUTTPUTT-001 v2 s3).
 *
 * @param mode                    how each hole after the first is ordered
 * @param shotClockSeconds        time a player has to putt before forfeiting the turn
 * @param timeoutPenalty          strokes added when the clock expires
 * @param maxConsecutiveTimeouts  forfeits in a row before the player is auto-finished on the hole
 * @param maxStrokesPerHole       hard cap so one player cannot stall a hole forever; 0 removes it
 */
public record TurnConfig(
        TurnOrderMode mode,
        int shotClockSeconds,
        int timeoutPenalty,
        int maxConsecutiveTimeouts,
        int maxStrokesPerHole) {

    public static final TurnConfig DEFAULTS = new TurnConfig(TurnOrderMode.ASCENDING, 30, 1, 3, 10);

    /** True when a hole runs until the ball is sunk, however many strokes that takes. */
    public boolean strokesUncapped() {
        return maxStrokesPerHole == 0;
    }

    public TurnConfig {
        if (shotClockSeconds <= 0) {
            throw new IllegalArgumentException("shot-clock seconds must be positive, got " + shotClockSeconds);
        }
        if (maxStrokesPerHole < 0) {
            throw new IllegalArgumentException("max-strokes-per-hole cannot be negative, got " + maxStrokesPerHole);
        }
        if (maxConsecutiveTimeouts <= 0) {
            throw new IllegalArgumentException(
                    "max-consecutive-timeouts must be positive, got " + maxConsecutiveTimeouts);
        }
        timeoutPenalty = Math.max(0, timeoutPenalty);
    }

    public long shotClockTicks() {
        return shotClockSeconds * 20L;
    }
}
