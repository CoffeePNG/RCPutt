package com.redcoffee.puttputt.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.redcoffee.puttputt.config.BallCollisionConfig;
import com.redcoffee.puttputt.config.PhysicsConfig;
import com.redcoffee.puttputt.surface.ResetMode;
import com.redcoffee.puttputt.surface.Surface;
import com.redcoffee.puttputt.surface.SurfaceType;
import com.redcoffee.puttputt.util.Bounds;
import com.redcoffee.puttputt.util.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

class TeleportTest {

    private static final Surface GREEN =
            new Surface("green", SurfaceType.ROLL, 0.92, 0, 0, ResetMode.LAST_REST, null, false);
    private static final Surface WALL =
            new Surface("wall", SurfaceType.WALL, 1.0, 0.70, 0, ResetMode.LAST_REST, null, false);
    private static final Bounds REGION = Bounds.of(-50, 60, -50, 50, 70, 50);

    private final PhysicsEngine engine =
            new PhysicsEngine(PhysicsConfig.DEFAULTS, BallCollisionConfig.DEFAULTS);

    private HoleContext holeWith(TeleportLookup pads) {
        return new HoleContext((x, y, z) -> GREEN, new Vec3(999, 65, 999), REGION, WALL, pads);
    }

    @Test
    void rollingOntoAPadMovesTheBallAndKeepsItsSpeed() {
        Vec3 exit = new Vec3(30.5, 65.0, 30.5);
        HoleContext hole = holeWith((x, y, z) ->
                x == 3 && y == 64 && z == 0 ? new Teleport(exit, true) : null);
        BallState ball = new BallState(new Vec3(0.5, 65.0, 0.5));
        ball.strike(new Vec3(0.5, 0, 0));

        for (int tick = 0; tick < 20 && ball.position().x() < 30; tick++) {
            engine.step(ball, hole, List.of());
        }

        assertEquals(30.5, ball.position().x(), 1.0e-9, "the ball came out at the destination");
        assertFalse(ball.atRest(), "and is still rolling");
        assertTrue(ball.velocity().x() > 0, "carrying its speed out the far side");
    }

    @Test
    void aPadCanAlsoDropTheBallStopped() {
        Vec3 exit = new Vec3(10.5, 65.0, 10.5);
        HoleContext hole = holeWith((x, y, z) ->
                x == 3 && y == 64 && z == 0 ? new Teleport(exit, false) : null);
        BallState ball = new BallState(new Vec3(0.5, 65.0, 0.5));
        ball.strike(new Vec3(0.5, 0, 0));

        for (int tick = 0; tick < 20 && !ball.atRest(); tick++) {
            engine.step(ball, hole, List.of());
        }

        assertEquals(exit, ball.position());
        assertTrue(ball.atRest());
        assertEquals(exit, ball.lastRest(), "arriving stopped sets the hazard reset point too");
    }

    /** Two pads pointing at each other must not trap a ball forever. */
    @Test
    void pairedPadsDoNotLoopForever() {
        Vec3 a = new Vec3(0.5, 65.0, 0.5);
        Vec3 b = new Vec3(20.5, 65.0, 0.5);
        HoleContext hole = holeWith((x, y, z) -> {
            if (y != 64 || z != 0) {
                return null;
            }
            if (x == 20) {
                return new Teleport(a, true);
            }
            if (x == 0) {
                return new Teleport(b, true);
            }
            return null;
        });
        BallState ball = new BallState(new Vec3(18.5, 65.0, 0.5));
        ball.strike(new Vec3(0.5, 0, 0));

        int teleports = 0;
        Vec3 previous = ball.position();
        for (int tick = 0; tick < 400 && !ball.atRest(); tick++) {
            engine.step(ball, hole, List.of());
            if (ball.position().horizontalDistance(previous) > 5.0) {
                teleports++;
            }
            previous = ball.position();
        }

        assertTrue(ball.atRest(), "the ball must eventually settle rather than ping-pong forever");
        assertTrue(teleports < 20, "and must not have teleported unboundedly, saw " + teleports);
    }

    /** A pad outside the hole's region is ignored, same as any other out-of-region block. */
    @Test
    void padsOutsideTheRegionAreIgnored() {
        HoleContext hole = new HoleContext((x, y, z) -> GREEN, new Vec3(999, 65, 999),
                Bounds.of(0, 60, 0, 5, 70, 5), WALL,
                (x, y, z) -> new Teleport(new Vec3(900, 65, 900), true));

        assertEquals(null, hole.teleportAt(400, 64, 400));
    }
}
