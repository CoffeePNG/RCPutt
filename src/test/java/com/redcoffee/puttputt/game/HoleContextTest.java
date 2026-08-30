package com.redcoffee.puttputt.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.redcoffee.puttputt.config.BallCollisionConfig;
import com.redcoffee.puttputt.config.PhysicsConfig;
import com.redcoffee.puttputt.surface.ResetMode;
import com.redcoffee.puttputt.surface.Surface;
import com.redcoffee.puttputt.surface.SurfaceType;
import com.redcoffee.puttputt.util.Bounds;
import com.redcoffee.puttputt.util.Vec3;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The region guarantee: a hole's physics must never read a block outside that hole's own bounds.
 * These tests use a sampler that <em>records every cell it is asked about</em>, so an escape shows
 * up as a hard failure rather than as behaviour someone has to eyeball.
 */
class HoleContextTest {

    private static final Surface GREEN =
            new Surface("green", SurfaceType.ROLL, 0.92, 0, 0, ResetMode.LAST_REST, null, false);
    private static final Surface WALL =
            new Surface("wall", SurfaceType.WALL, 1.0, 0.70, 0, ResetMode.LAST_REST, null, false);
    private static final Surface OFF_COURSE =
            new Surface("off_course", SurfaceType.ROLL, 0.10, 0, 0, ResetMode.LAST_REST, null, false);

    /** Records what it was asked, and reports everything as an unmistakable off-course surface. */
    private static final class RecordingSampler implements SurfaceSampler {
        private final List<int[]> reads = new ArrayList<>();

        @Override
        public Surface at(int x, int y, int z) {
            reads.add(new int[]{x, y, z});
            return OFF_COURSE;
        }
    }

    private static final Bounds REGION = Bounds.of(0, 64, 0, 20, 68, 20);

    @Test
    void cellsOutsideTheRegionAreNeverReadFromTheWorld() {
        RecordingSampler sampler = new RecordingSampler();
        HoleContext hole = new HoleContext(sampler, new Vec3(10.5, 65, 10.5), REGION, WALL, TeleportLookup.NONE);

        assertSame(WALL, hole.surfaceAt(500, 65, 500), "a distant block reports as the boundary");
        assertTrue(sampler.reads.isEmpty(), "and the world was never consulted for it");

        hole.surfaceAt(10, 65, 10);
        assertEquals(1, sampler.reads.size(), "only in-region cells reach the world");
    }

    /**
     * Sampling is allowed exactly one cell beyond the region so a perimeter wall standing on the
     * boundary still bounces. Two cells out is off-limits.
     */
    @Test
    void samplingReachesOneCellPastTheRegionForPerimeterWalls() {
        RecordingSampler sampler = new RecordingSampler();
        HoleContext hole = new HoleContext(sampler, Vec3.ZERO, REGION, WALL, TeleportLookup.NONE);

        assertSame(OFF_COURSE, hole.surfaceAt(21, 65, 10), "one past the edge is still read");
        assertSame(WALL, hole.surfaceAt(22, 65, 10), "two past the edge is not");
        assertEquals(1, sampler.reads.size());
    }

    /**
     * The end-to-end guarantee: run a ball hard at the boundary for a long time and assert the
     * world was never asked about anything far outside the hole.
     */
    @Test
    void aBallDrivenAtTheBoundaryCannotReadDistantBlocks() {
        RecordingSampler sampler = new RecordingSampler();
        HoleContext hole = new HoleContext(sampler, new Vec3(999, 65, 999), REGION, WALL, TeleportLookup.NONE);
        PhysicsEngine engine = new PhysicsEngine(PhysicsConfig.DEFAULTS, BallCollisionConfig.DEFAULTS);

        BallState ball = new BallState(new Vec3(10.5, 65.0, 10.5));
        ball.strike(new Vec3(0.9, 0, 0));
        for (int tick = 0; tick < 300 && !ball.atRest(); tick++) {
            engine.step(ball, hole, List.of());
        }

        Bounds allowed = REGION.expand(1);
        for (int[] read : sampler.reads) {
            assertTrue(allowed.containsBlock(read[0], read[1], read[2]),
                    "physics read a block outside the hole region: "
                            + read[0] + "," + read[1] + "," + read[2]);
        }
    }

    /** With confinement on, the bounds act as a wall, so the ball is turned back rather than lost. */
    @Test
    void confinedBoundsTurnTheBallBackInsteadOfLettingItEscape() {
        HoleContext hole = new HoleContext((x, y, z) -> GREEN,
                new Vec3(999, 65, 999), Bounds.of(0, 64, 0, 6, 68, 6), WALL, TeleportLookup.NONE);
        PhysicsEngine engine = new PhysicsEngine(PhysicsConfig.DEFAULTS, BallCollisionConfig.DEFAULTS);

        BallState ball = new BallState(new Vec3(3.5, 65.0, 3.5));
        ball.strike(new Vec3(0.9, 0, 0));
        for (int tick = 0; tick < 300 && !ball.atRest(); tick++) {
            engine.step(ball, hole, List.of());
        }

        assertTrue(ball.position().x() < 8.0,
                "the ball should have been contained, ended at x=" + ball.position().x());
        assertTrue(ball.position().x() > -1.0);
    }

    /** Turning confinement off restores plain world sampling for anyone who wants it. */
    @Test
    void aNullRegionDisablesConfinement() {
        RecordingSampler sampler = new RecordingSampler();
        HoleContext hole = new HoleContext(sampler, Vec3.ZERO, null, WALL, TeleportLookup.NONE);

        assertSame(OFF_COURSE, hole.surfaceAt(5000, 65, 5000));
        assertFalse(sampler.reads.isEmpty());
    }
}
