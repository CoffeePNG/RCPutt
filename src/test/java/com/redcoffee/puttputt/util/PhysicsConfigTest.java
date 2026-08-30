package com.redcoffee.puttputt.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.redcoffee.puttputt.config.PhysicsConfig;
import org.junit.jupiter.api.Test;

class PhysicsConfigTest {

    @Test
    void rejectsAMaxVelocityThatWouldLetBallsTunnel() {
        assertThrows(IllegalArgumentException.class,
                () -> new PhysicsConfig(1.0, 0.02, 0.25, 0.35));
        assertThrows(IllegalArgumentException.class,
                () -> new PhysicsConfig(2.5, 0.02, 0.25, 0.35));
    }

    @Test
    void clampLengthPreservesDirection() {
        Vec3 clamped = new Vec3(3, 0, 4).clampLength(1.0);

        assertEquals(1.0, clamped.length(), 1.0e-9);
        assertEquals(0.6, clamped.x(), 1.0e-9);
        assertEquals(0.8, clamped.z(), 1.0e-9);
    }

    @Test
    void clampLengthLeavesShortVectorsAlone() {
        Vec3 slow = new Vec3(0.1, 0, 0.1);

        assertEquals(slow, slow.clampLength(1.0));
    }

    @Test
    void boundsAreInclusiveOfTheirBlocks() {
        Bounds bounds = Bounds.of(125, 68, 210, 95, 64, 195);

        assertEquals(95, bounds.minX());
        assertEquals(125, bounds.maxX());
        assertTrue(bounds.contains(new Vec3(95.0, 64.0, 195.0)));
        assertTrue(bounds.contains(new Vec3(125.9, 68.5, 210.9)), "the max block itself is inside");
        assertTrue(!bounds.contains(new Vec3(94.9, 65.0, 200.0)));
    }
}
