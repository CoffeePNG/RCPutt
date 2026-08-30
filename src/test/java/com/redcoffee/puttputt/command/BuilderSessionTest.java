package com.redcoffee.puttputt.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.redcoffee.puttputt.util.Bounds;
import com.redcoffee.puttputt.util.Vec3;
import org.junit.jupiter.api.Test;

class BuilderSessionTest {

    /**
     * A wand mark must land on the top face of the clicked block - the exact plane the physics
     * rolls a ball along, and the reason the wand cannot repeat the stand-on-a-slab mistake.
     */
    @Test
    void wandMarksLandOnTheBlocksTopFace() {
        Vec3 mark = BuilderSession.topFaceOf(118, 64, 200);

        assertEquals(118.5, mark.x(), 1.0e-9, "centred on the block in X");
        assertEquals(65.0, mark.y(), 1.0e-9, "sitting on the top face, not inside the block");
        assertEquals(200.5, mark.z(), 1.0e-9, "centred on the block in Z");
    }

    /** Negative coordinates must centre the same way, not round toward zero. */
    @Test
    void marksAreCorrectInNegativeCoordinates() {
        Vec3 mark = BuilderSession.topFaceOf(-4, 64, -12);

        assertEquals(-3.5, mark.x(), 1.0e-9);
        assertEquals(-11.5, mark.z(), 1.0e-9);
    }

    @Test
    void boundsNeedBothCornersAndAreOrderIndependent() {
        BuilderSession session = new BuilderSession();
        assertFalse(session.hasBothCorners());

        session.setCorner1(BuilderSession.topFaceOf(125, 68, 210));
        assertFalse(session.hasBothCorners(), "one corner is not a selection");
        session.setCorner2(BuilderSession.topFaceOf(95, 64, 195));

        assertTrue(session.hasBothCorners());
        Bounds bounds = session.toBounds();
        assertEquals(95, bounds.minX());
        assertEquals(125, bounds.maxX());
        assertEquals(195, bounds.minZ());
        assertEquals(210, bounds.maxZ());
    }

    @Test
    void selectingACourseAndHoleIsRemembered() {
        BuilderSession session = new BuilderSession();
        assertEquals(1, session.currentHole(), "the wand starts on hole 1");

        session.selectCourse("downtown6");
        session.selectHole(4);

        assertEquals("downtown6", session.courseId());
        assertEquals(4, session.currentHole());
    }
}
