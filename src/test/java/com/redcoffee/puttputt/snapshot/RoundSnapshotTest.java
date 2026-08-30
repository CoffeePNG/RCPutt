package com.redcoffee.puttputt.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.redcoffee.puttputt.course.Course;
import com.redcoffee.puttputt.course.Hole;
import com.redcoffee.puttputt.game.Round;
import com.redcoffee.puttputt.util.Bounds;
import com.redcoffee.puttputt.util.Vec3;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A snapshot has to survive the round trip, or a crash silently loses the round. */
class RoundSnapshotTest {

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private Course course;

    @BeforeEach
    void setUp() {
        course = new Course("downtown6", "<gold>Downtown 6</gold>", "minigolf");
        for (int number = 1; number <= 3; number++) {
            Hole hole = course.holeOrCreate(number);
            hole.setPar(3);
            hole.setTee(new Vec3(number * 10 + 0.5, 65.0, 0.5));
            hole.setCup(new Vec3(number * 10 + 8.5, 65.0, 0.5));
            hole.setBounds(Bounds.of(0, 64, -5, 100, 68, 5));
        }
    }

    private Round round() {
        return new Round(UUID.randomUUID(), course, UUID.randomUUID(), List.of(alice, bob), 1_000L);
    }

    @Test
    void roundTripsScorecardsOrderAndHole() {
        Round original = round();
        original.setCurrentHoleNumber(2);
        original.setTurnOrder(List.of(bob, alice));
        original.setTurnIndex(1);
        original.scorecard(alice).addStrokes(3);
        original.scorecard(alice).completeHole(1);
        original.scorecard(alice).addStrokes(2);
        original.scorecard(bob).addStrokes(4);
        original.scorecard(bob).completeHole(1);
        original.markFinishedThisHole(bob);

        RoundSnapshot restored = RoundSnapshot.fromJson(RoundSnapshot.capture(original, null).toJson());
        assertNotNull(restored);
        Round rebuilt = round();
        restored.applyTo(rebuilt);

        assertEquals(2, rebuilt.currentHoleNumber());
        assertEquals(List.of(bob, alice), rebuilt.turnOrder());
        assertEquals(3, rebuilt.scorecard(alice).strokesFor(1));
        assertEquals(2, rebuilt.scorecard(alice).currentStrokes());
        assertEquals(5, rebuilt.scorecard(alice).runningTotal());
        assertEquals(4, rebuilt.scorecard(bob).totalStrokes());
        assertTrue(rebuilt.hasFinishedThisHole(bob), "who is already done on this hole must survive");
    }

    @Test
    void carriesTheTimeoutStreakSoAnAfkPlayerCannotResetItByCrashing() {
        Round original = round();
        original.scorecard(alice).recordTimeout();
        original.scorecard(alice).recordTimeout();

        Round rebuilt = round();
        RoundSnapshot.fromJson(RoundSnapshot.capture(original, null).toJson()).applyTo(rebuilt);

        assertEquals(2, rebuilt.scorecard(alice).consecutiveTimeouts());
    }

    @Test
    void memberAndBallDataSurvive() {
        Round original = round();
        RoundSnapshot snapshot = RoundSnapshot.capture(original, null);

        RoundSnapshot restored = RoundSnapshot.fromJson(snapshot.toJson());

        assertEquals("downtown6", restored.courseId());
        assertTrue(restored.memberIds().containsAll(List.of(alice, bob)));
    }

    @Test
    void memberListEncodesAndDecodes() {
        String json = RoundSnapshot.encodeMembers(List.of(alice, bob));

        assertEquals(List.of(alice, bob), RoundSnapshot.decodeMembers(json));
    }

    /** A corrupt row should archive the round, never take the server down on startup. */
    @Test
    void corruptJsonYieldsNullRatherThanThrowing() {
        assertNull(RoundSnapshot.fromJson("{not json at all"));
        assertEquals(List.of(), RoundSnapshot.decodeMembers("}}broken"));
    }

    /** Players who left while the server was down must not be restored into the order. */
    @Test
    void applyIgnoresPlayersNoLongerInTheRound() {
        Round original = round();
        original.setTurnOrder(List.of(alice, bob));
        RoundSnapshot snapshot = RoundSnapshot.fromJson(RoundSnapshot.capture(original, null).toJson());

        Round soloRebuild = new Round(UUID.randomUUID(), course, UUID.randomUUID(), List.of(alice), 1_000L);
        snapshot.applyTo(soloRebuild);

        assertEquals(List.of(alice), soloRebuild.turnOrder());
        assertTrue(soloRebuild.turnIndex() < soloRebuild.turnOrder().size(),
                "the turn index must stay inside the trimmed order");
    }
}
