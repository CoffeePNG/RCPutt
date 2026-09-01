package com.redcoffee.puttputt.course;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.redcoffee.puttputt.util.Bounds;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The flood fill exists because a bounding box cannot describe a hole that bends, so these tests are
 * drawn as ASCII courses: '#' is boundary, '.' is fairway, 'T' is the tee.
 */
class CourseRegionTest {

    private static final int Y = 65;

    private static CourseRegion.OpenTest map(String... rows) {
        Set<String> open = new HashSet<>();
        for (int z = 0; z < rows.length; z++) {
            for (int x = 0; x < rows[z].length(); x++) {
                if (rows[z].charAt(x) != '#') {
                    open.add(x + ":" + z);
                }
            }
        }
        return (x, y, z) -> open.contains(x + ":" + z);
    }

    private static CourseRegion.Result fill(String... rows) {
        for (int z = 0; z < rows.length; z++) {
            int x = rows[z].indexOf('T');
            if (x >= 0) {
                return CourseRegion.fill(x, Y, z, map(rows), CourseRegion.DEFAULT_MAX_CELLS, 4);
            }
        }
        throw new IllegalArgumentException("no tee in map");
    }

    /** The headline case: an L-shaped hole whose box is far larger than its fairway. */
    @Test
    void tracesAnLShapedCourse() {
        CourseRegion.Result result = fill(
                "##########",
                "#T....####",
                "#####.####",
                "#####....#",
                "##########");

        assertTrue(result.isUsable());
        assertEquals(10, result.size(), "5 along the top arm, 1 elbow, 4 along the lower arm");
        Bounds b = result.bounds();
        assertEquals(1, b.minX());
        assertEquals(8, b.maxX());
        assertEquals(1, b.minZ());
        assertEquals(3, b.maxZ());
        assertFalse(result.exhausted());
    }

    /** Area walled off from the tee is not part of the hole, even inside the same box. */
    @Test
    void doesNotLeakIntoASealedSideRoom() {
        CourseRegion.Result result = fill(
                "########",
                "#T..#..#",
                "########");

        assertEquals(3, result.size(), "the two cells behind the wall are a different room");
        assertEquals(3, result.bounds().maxX());
    }

    /**
     * A diagonal gap between two boundary blocks is a wall to a rolling ball, so the fill must not
     * squeeze through it, or the traced region would spill outside the course.
     */
    @Test
    void willNotSqueezeThroughADiagonalGap() {
        CourseRegion.Result result = fill(
                "#####",
                "#T#.#",
                "##..#",
                "#####");

        assertEquals(1, result.size(), "the tee cell is sealed off by the diagonal");
    }

    @Test
    void spiralIsTracedInFull() {
        CourseRegion.Result result = fill(
                "#########",
                "#T......#",
                "#######.#",
                "#.......#",
                "#.#######",
                "#.......#",
                "#########");

        assertTrue(result.isUsable());
        assertEquals(7 + 1 + 7 + 1 + 7, result.size());
        assertEquals(1, result.bounds().minZ());
        assertEquals(5, result.bounds().maxZ());
    }

    @Test
    void reportsALeakInsteadOfRunningAway() {
        CourseRegion.Result result = CourseRegion.fill(50, Y, 50, (x, y, z) -> true, 200, 4);

        assertTrue(result.exhausted());
        assertFalse(result.isUsable(), "a leaked trace must not be accepted as a region");
    }

    @Test
    void aTeeInsideAWallYieldsNothing() {
        CourseRegion.Result result = CourseRegion.fill(0, Y, 0, (x, y, z) -> false, 1000, 4);

        assertEquals(0, result.size());
        assertNull(result.bounds());
    }

    /** Bounds must include the floor beneath the ball and headroom above it for walls. */
    @Test
    void boundsCoverFloorAndWallHeight() {
        CourseRegion.Result result = fill(
                "#####",
                "#T..#",
                "#####");

        assertEquals(Y - 1, result.bounds().minY(), "the floor layer is inside");
        assertEquals(Y + 4, result.bounds().maxY(), "plus the configured headroom");
    }
}
