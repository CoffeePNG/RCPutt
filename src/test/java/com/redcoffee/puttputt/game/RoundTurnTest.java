package com.redcoffee.puttputt.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.redcoffee.puttputt.config.TurnOrderMode;
import com.redcoffee.puttputt.course.Course;
import com.redcoffee.puttputt.course.Hole;
import com.redcoffee.puttputt.util.Bounds;
import com.redcoffee.puttputt.util.Vec3;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The turn state machine, exercised without a server. */
class RoundTurnTest {

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID carol = UUID.randomUUID();
    private Course course;
    private Round round;

    @BeforeEach
    void setUp() {
        course = new Course("c", "c", "w");
        for (int number = 1; number <= 2; number++) {
            Hole hole = course.holeOrCreate(number);
            hole.setPar(3);
            hole.setTee(new Vec3(0.5, 65, 0.5));
            hole.setCup(new Vec3(9.5, 65, 0.5));
            hole.setBounds(Bounds.of(0, 64, -5, 20, 68, 5));
        }
        round = new Round(UUID.randomUUID(), course, UUID.randomUUID(), List.of(alice, bob, carol), 0L);
        round.setTurnOrder(List.of(alice, bob, carol));
    }

    @Test
    void turnsFollowTheOrder() {
        assertEquals(Optional.of(alice), round.currentPlayer());
        assertEquals(Optional.of(bob), round.advanceTurn());
        assertEquals(Optional.of(carol), round.advanceTurn());
    }

    /** Someone who has already sunk must be skipped rather than asked to putt again. */
    @Test
    void finishedPlayersAreSkipped() {
        round.markFinishedThisHole(bob);

        assertEquals(Optional.of(alice), round.currentPlayer());
        assertEquals(Optional.of(carol), round.advanceTurn(), "bob has holed out and is passed over");
    }

    @Test
    void holeIsCompleteOnlyWhenEveryoneHasFinished() {
        round.markFinishedThisHole(alice);
        round.markFinishedThisHole(bob);
        assertFalse(round.isHoleComplete());

        round.markFinishedThisHole(carol);

        assertTrue(round.isHoleComplete());
        assertEquals(Optional.empty(), round.currentPlayer());
    }

    /** Advancing a hole banks every card and re-sorts, punishing whoever now leads. */
    @Test
    void advancingAHoleBanksScoresAndReordersLeaderFirst() {
        round.scorecard(alice).addStrokes(5);
        round.scorecard(bob).addStrokes(2);
        round.scorecard(carol).addStrokes(4);
        round.markFinishedThisHole(alice);
        round.markFinishedThisHole(bob);
        round.markFinishedThisHole(carol);

        Optional<Integer> next = round.advanceHole(TurnOrderMode.ASCENDING);

        assertEquals(Optional.of(2), next);
        assertEquals(List.of(bob, carol, alice), round.turnOrder(), "bob leads on 2, so bob putts first");
        assertEquals(2, round.scorecard(bob).totalStrokes());
        assertEquals(0, round.scorecard(bob).currentStrokes(), "the new hole starts from zero");
        assertTrue(round.finishedThisHole().isEmpty(), "the finished set resets for the new hole");
        assertEquals(Optional.of(bob), round.currentPlayer());
    }

    @Test
    void finishingTheLastHoleEndsTheRound() {
        round.setCurrentHoleNumber(2);
        round.players().forEach(round::markFinishedThisHole);

        assertEquals(Optional.empty(), round.advanceHole(TurnOrderMode.ASCENDING));
        assertTrue(round.everyoneFinished());
    }

    /** A player leaving must not strand the turn pointer past the end of a shorter order. */
    @Test
    void removingAPlayerKeepsTheTurnPointerValid() {
        round.setTurnIndex(2);

        round.removePlayer(carol);

        assertEquals(List.of(alice, bob), round.turnOrder());
        assertTrue(round.turnIndex() < round.turnOrder().size());
        assertTrue(round.currentPlayer().isPresent());
    }

    @Test
    void removingTheLastPlayerLeavesNoTurn() {
        round.removePlayer(alice);
        round.removePlayer(bob);
        round.removePlayer(carol);

        assertEquals(Optional.empty(), round.currentPlayer());
        assertTrue(round.isHoleComplete());
    }
}
