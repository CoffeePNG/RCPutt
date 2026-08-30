package com.redcoffee.puttputt.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ScorecardTest {

    @Test
    void banksStrokesPerHoleAndAdvances() {
        Scorecard card = new Scorecard(1);
        card.addStrokes(1);
        card.addStrokes(1);
        card.completeHole(2);
        card.addStrokes(3);

        assertEquals(2, card.strokesFor(1));
        assertEquals(2, card.currentHole());
        assertEquals(3, card.currentStrokes());
        assertEquals(2, card.totalStrokes(), "a hole still in progress must not move the running total");
        assertFalse(card.finished());
    }

    @Test
    void finishBanksTheLastHole() {
        Scorecard card = new Scorecard(9);
        card.addStrokes(4);
        card.finish();

        assertTrue(card.finished());
        assertEquals(4, card.totalStrokes());
        assertEquals(0, card.currentStrokes());
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
