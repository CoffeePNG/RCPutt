package com.redcoffee.puttputt.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The stroke cap is a stall guard, not a rule of the game, so it has to be possible to lift it. */
class TurnConfigTest {

    private static TurnConfig withCap(int cap) {
        return new TurnConfig(TurnOrderMode.ASCENDING, 30, 1, 3, cap);
    }

    @Test
    void zeroMeansPlayTheHoleOut() {
        assertTrue(withCap(0).strokesUncapped());
        assertEquals(0, withCap(0).maxStrokesPerHole());
    }

    @Test
    void aPositiveCapIsStillACap() {
        assertFalse(withCap(10).strokesUncapped());
    }

    /** A negative cap is a typo, not an intent - it would silently finish everyone immediately. */
    @Test
    void aNegativeCapIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> withCap(-1));
    }
}
