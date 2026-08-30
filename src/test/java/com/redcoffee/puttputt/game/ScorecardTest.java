package com.redcoffee.puttputt.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ScorecardTest {

    @Test
    void banksStrokesPerHoleAndResetsForTheNext() {
        Scorecard card = new Scorecard();
        card.addStrokes(1);
        card.addStrokes(1);
        card.completeHole(1);
        card.addStrokes(3);

        assertEquals(2, card.strokesFor(1));
        assertEquals(3, card.currentStrokes());
        assertEquals(2, card.totalStrokes(), "a hole still in progress must not move the banked total");
        assertFalse(card.finishedRound());
    }

    /**
     * Turn order sorts on the running total. Using banked-only totals would let a player who has
     * already taken six shots on the current hole still be treated as the leader.
     */
    @Test
    void runningTotalIncludesTheHoleInProgress() {
        Scorecard card = new Scorecard();
        card.addStrokes(4);
        card.completeHole(1);
        card.addStrokes(3);

        assertEquals(4, card.totalStrokes());
        assertEquals(7, card.runningTotal());
    }

    /** Only unbroken timeouts should cap a hole, so any completed putt clears the counter. */
    @Test
    void aCompletedPuttClearsTheTimeoutStreak() {
        Scorecard card = new Scorecard();
        card.recordTimeout();
        card.recordTimeout();
        assertEquals(2, card.consecutiveTimeouts());

        card.clearTimeouts();

        assertEquals(0, card.consecutiveTimeouts());
    }

    @Test
    void completingAHoleAlsoClearsTheTimeoutStreak() {
        Scorecard card = new Scorecard();
        card.recordTimeout();
        card.completeHole(1);

        assertEquals(0, card.consecutiveTimeouts());
    }

    @Test
    void restoreRebuildsACardFromASnapshot() {
        Scorecard card = new Scorecard();

        card.restore(java.util.Map.of(1, 3, 2, 5), 2, 1);

        assertEquals(8, card.totalStrokes());
        assertEquals(10, card.runningTotal());
        assertEquals(2, card.currentStrokes());
        assertEquals(1, card.consecutiveTimeouts());
    }

    @Test
    void scoreNamesFollowGolf() {
        assertEquals("Hole in one", RoundManager.scoreName(1, 3));
        assertEquals("Birdie", RoundManager.scoreName(2, 3));
        assertEquals("Par", RoundManager.scoreName(3, 3));
        assertEquals("Bogey", RoundManager.scoreName(4, 3));
        assertEquals("Over par", RoundManager.scoreName(12, 3));
    }

    @Test
    void parDiffFormatsWithSign() {
        assertEquals("E", RoundManager.formatDiff(0));
        assertEquals("+3", RoundManager.formatDiff(3));
        assertEquals("-2", RoundManager.formatDiff(-2));
    }
}
