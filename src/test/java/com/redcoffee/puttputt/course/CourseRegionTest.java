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

    /**
     * A terraced map: each digit is how many blocks below the tee that cell's floor sits, so the
     * cell is open at exactly one height. '#' is still boundary.
     */
    private static CourseRegion.OpenTest terraces(String... rows) {
        Set<String> open = new HashSet<>();
        for (int z = 0; z < rows.length; z++) {
            for (int x = 0; x < rows[z].length(); x++) {
                char c = rows[z].charAt(x);
                if (c != '#') {
                    open.add(x + ":" + (Y - (c - '0')) + ":" + z);
                }
            }
        }
        return (x, y, z) -> open.contains(x + ":" + y + ":" + z);
    }

    /** A fairway that falls over a ledge is still one hole, so the trace steps down with it. */
    @Test
    void followsAFairwayDownADrop() {
        CourseRegion.Result result = CourseRegion.fill(1, Y, 1,
                terraces(
                        "#####",
                        "#000#",
                        "#112#",
                        "#####"),
                CourseRegion.DEFAULT_MAX_CELLS, 4, 8);

        assertTrue(result.isUsable());
        assertEquals(6, result.size(), "both terraces belong to the hole");
        // The floor sits one below the lowest cell reached, two down from the tee.
        assertEquals(Y - 3, result.bounds().minY());
        assertEquals(Y + 4, result.bounds().maxY());
    }

    /** With drops off, the same map is one flat layer - the lower terrace is somebody else's hole. */
    @Test
    void staysOnOneLayerWhenDropsAreDisabled() {
        CourseRegion.Result result = CourseRegion.fill(1, Y, 1,
                terraces(
                        "#####",
                        "#000#",
                        "#112#",
                        "#####"),
                CourseRegion.DEFAULT_MAX_CELLS, 4, 0);

        assertEquals(3, result.size());
        assertEquals(Y, result.bounds().maxY() - 4);
    }

    /** A drop deeper than the limit is the edge of the hole, not a route into the ravine below. */
    @Test
    void willNotFollowADropBeyondTheLimit() {
        CourseRegion.Result result = CourseRegion.fill(1, Y, 1,
                terraces(
                        "####",
                        "#00#",
                        "#99#",
                        "####"),
                CourseRegion.DEFAULT_MAX_CELLS, 4, 3);

        assertEquals(2, result.size(), "only the top terrace is reachable");
    }

    /** A wall is a wall at every height: the trace must not tunnel underneath one. */
    @Test
    void doesNotProbeDownwardPastAWall() {
        CourseRegion.OpenTest open = (x, y, z) -> {
            if (z != 0 || x < 0 || x > 3) {
                return false;
            }
            if (x == 0) {
                return y == Y;          // the tee
            }
            if (x == 1) {
                return false;           // a wall, at every height
            }
            return y == Y - 2;          // open ground beyond it, two down
        };

        CourseRegion.Result result = CourseRegion.fill(0, Y, 0, open,
                CourseRegion.DEFAULT_MAX_CELLS, 4, 8);

        assertEquals(1, result.size(), "the wall ends the hole even though it is short");
    }

    /**
     * A fairway with a real floor: open only on the ball plane at Y, solid below, air above. This is
     * what the world looks like to the scanner, and the reason a start height one block out returns
     * nothing at all.
     */
    private static CourseRegion.OpenTest oneOpenPlane(int planeY) {
        return (x, y, z) -> y == planeY && x >= 0 && x <= 4 && z >= 0 && z <= 4;
    }

    /** The bug behind "bounds don't work whatever material I use": one block out yields nothing. */
    @Test
    void aStartHeightOneBlockOutTracesNothing() {
        CourseRegion.Result low = CourseRegion.fill(2, Y - 1, 2, oneOpenPlane(Y),
                CourseRegion.DEFAULT_MAX_CELLS, 4, 0);
        CourseRegion.Result high = CourseRegion.fill(2, Y + 1, 2, oneOpenPlane(Y),
                CourseRegion.DEFAULT_MAX_CELLS, 4, 0);

        assertEquals(0, low.size());
        assertNull(low.bounds(), "and no bounds at all, not merely a small region");
        assertEquals(0, high.size());
        assertNull(high.bounds());
    }

    /** So the height is resolved first. A tee on the floor block snaps up onto the ball plane. */
    @Test
    void snapFindsThePlaneFromBelow() {
        assertEquals(Y, CourseRegion.snapToOpen(2, Y - 1, 2, oneOpenPlane(Y), 3));
    }

    /** And a tee set while stood on the boundary wall snaps back down onto it. */
    @Test
    void snapFindsThePlaneFromAbove() {
        assertEquals(Y, CourseRegion.snapToOpen(2, Y + 1, 2, oneOpenPlane(Y), 3));
    }

    /** An exact hit must not be moved: a tee already on the plane stays where it is. */
    @Test
    void snapLeavesAnExactStartAlone() {
        assertEquals(Y, CourseRegion.snapToOpen(2, Y, 2, oneOpenPlane(Y), 3));
    }

    /** Snapping is a nudge, not a search of the whole column: solid rock stays unplayable. */
    @Test
    void snapGivesUpOnAColumnThatIsNotPlayable() {
        assertEquals(CourseRegion.NO_START,
                CourseRegion.snapToOpen(2, Y + 20, 2, oneOpenPlane(Y), 3));
        assertEquals(CourseRegion.NO_START,
                CourseRegion.snapToOpen(99, Y, 99, oneOpenPlane(Y), 3));
    }

    /** With the height resolved, the same course that traced nothing now traces in full. */
    @Test
    void snappingRescuesATraceThatWouldHaveFailed() {
        CourseRegion.OpenTest open = oneOpenPlane(Y);
        int start = CourseRegion.snapToOpen(2, Y - 1, 2, open, 3);
        CourseRegion.Result result = CourseRegion.fill(2, start, 2, open,
                CourseRegion.DEFAULT_MAX_CELLS, 4, 0);

        assertEquals(25, result.size(), "the whole 5x5 green");
        assertTrue(result.isUsable());
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
