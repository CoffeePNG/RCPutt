package com.redcoffee.puttputt.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.redcoffee.puttputt.config.BallCollisionConfig;
import com.redcoffee.puttputt.config.PhysicsConfig;
import com.redcoffee.puttputt.surface.Impulse;
import com.redcoffee.puttputt.surface.ResetMode;
import com.redcoffee.puttputt.surface.Surface;
import com.redcoffee.puttputt.surface.SurfaceType;
import com.redcoffee.puttputt.util.Bounds;
import com.redcoffee.puttputt.util.Vec3;
import java.util.HashMap;
import java.util.List;
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
    private static final Vec3 FAR_AWAY = new Vec3(9999, BALL_Y, 9999);

    private static Surface roll(String id, double friction) {
        return new Surface(id, SurfaceType.ROLL, friction, 0, 0, ResetMode.LAST_REST, null, false);
    }

    private static final Surface GREEN = roll("green", 0.92);
    private static final Surface ICE = roll("ice", 0.99);
    private static final Surface WALL =
            new Surface("wall", SurfaceType.WALL, 1.0, 0.70, 0, ResetMode.LAST_REST, null, false);
    private static final Surface WATER =
            new Surface("water", SurfaceType.HAZARD, 0.5, 0, 1, ResetMode.LAST_REST, null, false);
    private static final Surface TEE_WATER =
            new Surface("deep", SurfaceType.HAZARD, 0.5, 0, 2, ResetMode.TEE, null, false);
    private static final Surface BOOSTER = new Surface("booster_north", SurfaceType.IMPULSE, 0.92, 0, 0,
            ResetMode.LAST_REST, new Impulse(new Vec3(0, 0, -1), 0.35), false);
    private static final Surface RIVER = new Surface("river_east", SurfaceType.CURRENT, 0.92, 0, 0,
            ResetMode.LAST_REST, new Impulse(new Vec3(1, 0, 0), 0.12), true);

    private final PhysicsEngine engine =
            new PhysicsEngine(PhysicsConfig.DEFAULTS, BallCollisionConfig.DEFAULTS);

    /** A grid that is green everywhere except the cells explicitly placed. */
    private static final class Grid implements SurfaceSampler {
        private final Map<String, Surface> cells = new HashMap<>();

        Grid put(int x, int y, int z, Surface surface) {
            cells.put(x + ":" + y + ":" + z, surface);
            return this;
        }

        Grid fillGround(int fromX, int toX, int z, Surface surface) {
            for (int x = fromX; x <= toX; x++) {
                put(x, GROUND_Y, z, surface);
            }
            return this;
        }

        @Override
        public Surface at(int x, int y, int z) {
            return cells.getOrDefault(x + ":" + y + ":" + z, GREEN);
        }
    }

    /** A context with bounds wide enough not to interfere, unless a test narrows them. */
    private static HoleContext context(Grid grid, Vec3 cup) {
        return new HoleContext(grid, cup, Bounds.of(-500, 0, -500, 500, 200, 500), WALL, TeleportLookup.NONE);
    }

    private static BallState ballAt(double x, double z) {
        return new BallState(new Vec3(x, BALL_Y, z));
    }

    private StepOutcome run(BallState ball, Grid grid, Vec3 cup, int maxTicks) {
        StepOutcome outcome = null;
        for (int tick = 0; tick < maxTicks && !ball.atRest(); tick++) {
            outcome = engine.step(ball, context(grid, cup), List.of());
            if (outcome.result() == StepResult.HAZARD || outcome.result() == StepResult.SUNK) {
                return outcome;
            }
        }
        return outcome;
    }

    // ---------------------------------------------------------------- rolling

    @Test
    void frictionBringsTheBallToRest() {
        BallState ball = ballAt(100.5, 200.5);
        ball.strike(new Vec3(0.4, 0, 0));

        StepOutcome outcome = run(ball, new Grid(), FAR_AWAY, 400);

        assertEquals(StepResult.CAME_TO_REST, outcome.result());
        assertTrue(ball.atRest());
        assertEquals(Vec3.ZERO, ball.velocity());
        assertTrue(ball.position().x() > 100.5, "the ball should have travelled forward before stopping");
        assertEquals(ball.position(), ball.lastRest(), "resting establishes the hazard reset point");
    }

    @Test
    void iceRollsFurtherThanGreen() {
        Grid ice = new Grid().fillGround(90, 140, 200, ICE);

        assertTrue(rollDistance(ice) > rollDistance(new Grid()),
                "a slicker surface must carry the ball further");
    }

    private double rollDistance(Grid grid) {
        BallState ball = ballAt(100.5, 200.5);
        ball.strike(new Vec3(0.5, 0, 0));
        run(ball, grid, FAR_AWAY, 500);
        return ball.position().x() - 100.5;
    }

    @Test
    void wallReflectsOnlyTheAxisItWasHitOn() {
        BallState ball = ballAt(100.5, 200.5);
        ball.strike(new Vec3(0.5, 0, 0.2));
        Grid grid = new Grid().put(101, WALL_Y, 200, WALL);

        engine.step(ball, context(grid, FAR_AWAY), List.of());

        assertTrue(ball.velocity().x() < 0, "the X component should have reversed");
        assertEquals(0.5 * 0.70 * GREEN.friction(), -ball.velocity().x(), 1.0e-9,
                "restitution and then friction should both apply to the reflected component");
        assertTrue(ball.velocity().z() > 0, "the Z component is untouched by an X-axis wall");
    }

    @Test
    void ballNeverExceedsTheTunnelingGuard() {
        BallState ball = ballAt(100.5, 200.5);
        ball.strike(new Vec3(0.5, 0, 0));
        Surface rocket = new Surface("rocket", SurfaceType.IMPULSE, 1.0, 0, 0, ResetMode.LAST_REST,
                new Impulse(new Vec3(1, 0, 0), 0.9), false);
        Grid grid = new Grid().fillGround(90, 200, 200, rocket);

        for (int tick = 0; tick < 100; tick++) {
            engine.step(ball, context(grid, FAR_AWAY), List.of());
            assertTrue(ball.velocity().length() <= PhysicsConfig.DEFAULTS.maxVelocity() + 1.0e-9,
                    "speed must stay under the tunneling guard, was " + ball.velocity().length());
        }
    }

    // ---------------------------------------------------------------- surfaces

    @Test
    void impulsePadPushesTheBall() {
        BallState ball = ballAt(100.5, 200.5);
        ball.strike(new Vec3(0.1, 0, 0));
        Grid grid = new Grid().put(100, GROUND_Y, 200, BOOSTER);

        engine.step(ball, context(grid, FAR_AWAY), List.of());

        assertTrue(ball.velocity().z() < 0, "the pad should have pushed the ball north");
    }

    /** A river must carry a ball that would otherwise have stopped, and not let it settle. */
    @Test
    void currentKeepsTheBallMovingAndSuppressesRest() {
        BallState ball = ballAt(100.5, 200.5);
        ball.strike(new Vec3(0.001, 0, 0));
        Grid grid = new Grid().fillGround(90, 140, 200, RIVER);

        for (int tick = 0; tick < 50; tick++) {
            StepOutcome outcome = engine.step(ball, context(grid, FAR_AWAY), List.of());
            assertNotEquals(StepResult.CAME_TO_REST, outcome.result(),
                    "a ball in a current must not settle mid-stream");
        }
        assertFalse(ball.atRest());
        assertTrue(ball.position().x() > 100.5, "the current should have carried the ball downstream");
    }

    /** Once out of the current, normal friction applies again and the ball parks. */
    @Test
    void ballSettlesOnceItLeavesTheCurrent() {
        BallState ball = ballAt(100.5, 200.5);
        ball.strike(new Vec3(0.2, 0, 0));
        Grid grid = new Grid().fillGround(90, 105, 200, RIVER);

        run(ball, grid, FAR_AWAY, 600);

        assertTrue(ball.atRest(), "the ball should stop after leaving the river");
        assertTrue(ball.position().x() > 105, "and it should have stopped downstream of the river");
    }

    @Test
    void hazardResetsToLastRestAndCostsAStroke() {
        BallState ball = ballAt(100.5, 200.5);
        ball.comeToRest();
        Vec3 rest = ball.lastRest();
        ball.strike(new Vec3(0.5, 0, 0));
        Grid grid = new Grid().put(101, GROUND_Y, 200, WATER);

        StepOutcome outcome = engine.step(ball, context(grid, FAR_AWAY), List.of());

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
        engine.step(ball, context(new Grid(), FAR_AWAY), List.of());
        assertNotEquals(tee, ball.position());

        Grid grid = new Grid().fillGround(90, 140, 200, TEE_WATER);
        StepOutcome outcome = engine.step(ball, context(grid, FAR_AWAY), List.of());

        assertEquals(StepResult.HAZARD, outcome.result());
        assertEquals(2, outcome.penaltyStrokes());
        assertEquals(tee, ball.position());
    }

    // ---------------------------------------------------------------- sinking

    @Test
    void slowBallOverTheCupSinks() {
        BallState ball = ballAt(100.5, 200.5);
        ball.strike(new Vec3(0.1, 0, 0));
        Vec3 cup = new Vec3(100.6, BALL_Y, 200.5);

        StepOutcome outcome = engine.step(ball, context(new Grid(), cup), List.of());

        assertEquals(StepResult.SUNK, outcome.result());
        assertEquals(cup, ball.position());
    }

    @Test
    void fastBallOverTheCupLipsOut() {
        BallState ball = ballAt(100.5, 200.5);
        ball.strike(new Vec3(0.8, 0, 0));
        Vec3 cup = new Vec3(101.2, BALL_Y, 200.5);

        StepOutcome outcome = engine.step(ball, context(new Grid(), cup), List.of());

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
    void puttVelocityIsFlatAndClampedToTheTunnelingGuard() {
        Vec3 aim = new Vec3(1, -0.8, 1);

        Vec3 shot = engine.puttVelocity(aim, 0.4);

        assertEquals(0.0, shot.y(), 1.0e-9, "putts stay in the plane of the green");
        assertEquals(0.4, shot.length(), 1.0e-9);
        assertEquals(PhysicsConfig.DEFAULTS.maxVelocity(), engine.puttVelocity(aim, 5.0).length(), 1.0e-9,
                "a speed over the guard is clamped rather than trusted");
    }

    // ---------------------------------------------------------------- ball-to-ball

    @Test
    void movingBallWakesTheOneItHitsAndLosesTheNormalComponent() {
        BallState striker = ballAt(100.5, 200.5);
        BallState target = ballAt(100.5 + BallCollisionConfig.DEFAULTS.contactDistance() * 0.9, 200.5);
        target.comeToRest();
        striker.strike(new Vec3(0.4, 0, 0));

        StepOutcome outcome = engine.step(striker, context(new Grid(), FAR_AWAY), List.of(target));

        assertSame(target, outcome.struck(), "the outcome should name the ball that was hit");
        assertFalse(target.atRest(), "a struck ball wakes up");
        assertTrue(target.velocity().x() > 0, "and is pushed along the contact normal");
        assertTrue(striker.velocity().x() < 0.4 * 0.92,
                "the striker gives up its normal component rather than passing through");
    }

    @Test
    void restingBallsAreLeftAloneWhenCollisionIsDisabled() {
        PhysicsEngine noCollision = new PhysicsEngine(PhysicsConfig.DEFAULTS,
                new BallCollisionConfig(false, 0.85, 0.18, true));
        BallState striker = ballAt(100.5, 200.5);
        BallState target = ballAt(100.6, 200.5);
        target.comeToRest();
        striker.strike(new Vec3(0.4, 0, 0));

        StepOutcome outcome = noCollision.step(striker, context(new Grid(), FAR_AWAY), List.of(target));

        assertTrue(target.atRest(), "with collision off the ball is passed straight through");
        assertEquals(null, outcome.struck());
    }

    /** A ball only hands over energy when it is closing; a receding pair must not stick together. */
    @Test
    void separatingBallsDoNotExchangeEnergy() {
        BallState striker = ballAt(100.5, 200.5);
        BallState target = ballAt(100.5 - BallCollisionConfig.DEFAULTS.contactDistance() * 0.9, 200.5);
        target.comeToRest();
        striker.strike(new Vec3(0.4, 0, 0));

        engine.step(striker, context(new Grid(), FAR_AWAY), List.of(target));

        assertTrue(target.atRest(), "a ball behind the striker must not be woken");
    }
}
