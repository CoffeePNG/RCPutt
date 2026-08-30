package com.redcoffee.puttputt.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.redcoffee.puttputt.config.PhysicsConfig;
import com.redcoffee.puttputt.surface.Impulse;
import com.redcoffee.puttputt.surface.ResetMode;
import com.redcoffee.puttputt.surface.Surface;
import com.redcoffee.puttputt.surface.SurfaceType;
import com.redcoffee.puttputt.util.Vec3;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The integrator is pure, so these run against a hand-built grid with no server involved.
 *
 * <p>Grid convention matches {@link PhysicsEngine}: the ball's y is the top face of the green, so
 * ground is sampled at y-1 and walls at y.
 */
class PhysicsEngineTest {

    private static final int GROUND_Y = 64;
    private static final double BALL_Y = 65.0;
    private static final int WALL_Y = 65;

    private static final Surface GREEN = new Surface("green", SurfaceType.ROLL, 0.92, 0, 0, ResetMode.LAST_REST, null);
    private static final Surface ICE = new Surface("ice", SurfaceType.ROLL, 0.99, 0, 0, ResetMode.LAST_REST, null);
    private static final Surface WALL = new Surface("wall", SurfaceType.WALL, 1.0, 0.70, 0, ResetMode.LAST_REST, null);
    private static final Surface WATER =
            new Surface("water", SurfaceType.HAZARD, 0.5, 0, 1, ResetMode.LAST_REST, null);
    private static final Surface TEE_WATER =
            new Surface("deep", SurfaceType.HAZARD, 0.5, 0, 2, ResetMode.TEE, null);

    private final PhysicsEngine engine = new PhysicsEngine(PhysicsConfig.DEFAULTS);

    /** A grid that is green everywhere except the cells explicitly placed. */
    private static final class Grid implements SurfaceSampler {
        private final Map<String, Surface> cells = new HashMap<>();

        Grid put(int x, int y, int z, Surface surface) {
            cells.put(x + ":" + y + ":" + z, surface);
            return this;
        }

        @Override
        public Surface at(int x, int y, int z) {
            return cells.getOrDefault(x + ":" + y + ":" + z, GREEN);
        }
    }

    private static BallState ballAt(double x, double z) {
        return new BallState(new Vec3(x, BALL_Y, z));
    }

    @Test
    void frictionBringsTheBallToRest() {
        BallState ball = ballAt(100.5, 200.5);
        ball.strike(new Vec3(0.4, 0, 0));
        Grid grid = new Grid();

        StepOutcome outcome = null;
        int ticks = 0;
        while (ticks++ < 400 && (outcome == null || outcome.result() == StepResult.MOVING)) {
            outcome = engine.step(ball, grid, new Vec3(999, BALL_Y, 999));
        }

        assertEquals(StepResult.CAME_TO_REST, outcome.result());
        assertTrue(ball.atRest());
        assertEquals(Vec3.ZERO, ball.velocity());
        assertTrue(ball.position().x() > 100.5, "the ball should have travelled forward before stopping");
        assertEquals(ball.position(), ball.lastRest(), "resting establishes the hazard reset point");
    }

    @Test
    void iceRollsFurtherThanGreen() {
        Grid green = new Grid();
        Grid ice = new Grid();
        for (int x = 90; x < 140; x++) {
            ice.put(x, GROUND_Y, 200, ICE);
        }

        assertTrue(rollDistance(ice) > rollDistance(green), "a slicker surface must carry the ball further");
    }

    private double rollDistance(Grid grid) {
        BallState ball = ballAt(100.5, 200.5);
        ball.strike(new Vec3(0.5, 0, 0));
        for (int tick = 0; tick < 500 && !ball.atRest(); tick++) {
            engine.step(ball, grid, new Vec3(999, BALL_Y, 999));
        }
        return ball.position().x() - 100.5;
    }

    @Test
    void wallReflectsOnlyTheAxisItWasHitOn() {
        BallState ball = ballAt(100.5, 200.5);
        ball.strike(new Vec3(0.5, 0, 0.2));
        Grid grid = new Grid().put(101, WALL_Y, 200, WALL);

        engine.step(ball, grid, new Vec3(999, BALL_Y, 999));

        assertTrue(ball.velocity().x() < 0, "the X component should have reversed");
        assertEquals(0.5 * 0.70 * GREEN.friction(), -ball.velocity().x(), 1.0e-9,
                "restitution and then friction should both apply to the reflected component");
        assertTrue(ball.velocity().z() > 0, "the Z component is untouched by an X-axis wall");
    }

    @Test
    void ballNeverExceedsTheTunnelingGuard() {
        BallState ball = ballAt(100.5, 200.5);
        ball.strike(new Vec3(0.5, 0, 0));
        // A corridor of strong boosters: without the clamp this would accelerate past a block/tick.
        Surface booster = new Surface("rocket", SurfaceType.IMPULSE, 1.0, 0, 0, ResetMode.LAST_REST,
                new Impulse(new Vec3(1, 0, 0), 0.9));
        Grid grid = new Grid();
        for (int x = 90; x < 200; x++) {
            grid.put(x, GROUND_Y, 200, booster);
        }

        for (int tick = 0; tick < 100; tick++) {
            engine.step(ball, grid, new Vec3(9999, BALL_Y, 9999));
            assertTrue(ball.velocity().length() <= PhysicsConfig.DEFAULTS.maxVelocity() + 1.0e-9,
                    "speed must stay under the tunneling guard, was " + ball.velocity().length());
        }
    }

    @Test
    void impulsePadPushesTheBall() {
        Surface pad = new Surface("booster_north", SurfaceType.IMPULSE, 0.92, 0, 0, ResetMode.LAST_REST,
                new Impulse(new Vec3(0, 0, -1), 0.35));
        BallState ball = ballAt(100.5, 200.5);
        ball.strike(new Vec3(0.1, 0, 0));
        Grid grid = new Grid().put(100, GROUND_Y, 200, pad);

        engine.step(ball, grid, new Vec3(999, BALL_Y, 999));

        assertTrue(ball.velocity().z() < 0, "the pad should have pushed the ball north");
    }

    @Test
    void hazardResetsToLastRestAndCostsAStroke() {
        BallState ball = ballAt(100.5, 200.5);
        ball.comeToRest();
        Vec3 rest = ball.lastRest();
        ball.strike(new Vec3(0.5, 0, 0));
        Grid grid = new Grid().put(101, GROUND_Y, 200, WATER);

        StepOutcome outcome = engine.step(ball, grid, new Vec3(999, BALL_Y, 999));

        assertEquals(StepResult.HAZARD, outcome.result());
        assertEquals(1, outcome.penaltyStrokes());
        assertEquals(rest, ball.position());
        assertTrue(ball.atRest());
    }

    @Test
    void hazardCanSendTheBallBackToTheTee() {
        BallState ball = ballAt(100.5, 200.5);
        Vec3 tee = ball.tee();
        ball.strike(new Vec3(0.5, 0, 0));
        engine.step(ball, new Grid(), new Vec3(999, BALL_Y, 999));
        assertNotEquals(tee, ball.position());

        Grid grid = new Grid();
        for (int x = 90; x < 140; x++) {
            grid.put(x, GROUND_Y, 200, TEE_WATER);
        }
        StepOutcome outcome = engine.step(ball, grid, new Vec3(999, BALL_Y, 999));

        assertEquals(StepResult.HAZARD, outcome.result());
        assertEquals(2, outcome.penaltyStrokes());
        assertEquals(tee, ball.position());
    }

    @Test
    void slowBallOverTheCupSinks() {
        BallState ball = ballAt(100.5, 200.5);
        ball.strike(new Vec3(0.1, 0, 0));
        Vec3 cup = new Vec3(100.6, BALL_Y, 200.5);

        StepOutcome outcome = engine.step(ball, new Grid(), cup);

        assertEquals(StepResult.SUNK, outcome.result());
        assertEquals(cup, ball.position());
    }

    @Test
    void fastBallOverTheCupLipsOut() {
        BallState ball = ballAt(100.5, 200.5);
        ball.strike(new Vec3(0.8, 0, 0));
        Vec3 cup = new Vec3(101.2, BALL_Y, 200.5);

        StepOutcome outcome = engine.step(ball, new Grid(), cup);

        assertNotEquals(StepResult.SUNK, outcome.result(), "over the cup but too fast must not drop");
        assertFalse(ball.atRest());
    }

    @Test
    void aBallAtRestCannotBeStruckTwice() {
        BallState ball = ballAt(100.5, 200.5);
        assertTrue(ball.strike(new Vec3(0.3, 0, 0)));
        assertFalse(ball.strike(new Vec3(0.9, 0, 0)), "a rolling ball must ignore a second stroke");
    }

    @Test
    void puttVelocityIsFlatAndScaledByDraw() {
        Vec3 aim = new Vec3(1, -0.8, 1);

        Vec3 full = engine.puttVelocity(aim, 1.0);
        Vec3 half = engine.puttVelocity(aim, 0.5);

        assertEquals(0.0, full.y(), 1.0e-9, "putts stay in the plane of the green");
        assertEquals(PhysicsConfig.DEFAULTS.maxPuttPower(), full.length(), 1.0e-9);
        assertEquals(full.length() / 2.0, half.length(), 1.0e-9);
        assertEquals(PhysicsConfig.DEFAULTS.maxPuttPower(), engine.puttVelocity(aim, 5.0).length(), 1.0e-9,
                "an over-unit force is clamped rather than trusted");
    }
}
