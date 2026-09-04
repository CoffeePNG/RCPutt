package com.redcoffee.puttputt.course;

import com.redcoffee.puttputt.util.Bounds;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Works out the playable footprint of a hole by flooding outward from the tee until it runs into
 * boundary blocks.
 *
 * <p>A bounding box cannot describe a course that bends: an L-shaped or spiral hole has a rectangle
 * far larger than the fairway, and asking a builder for two corners of something twisty is asking
 * the wrong question. Flooding from the tee follows whatever shape was actually built, so the
 * boundary is the blocks you placed rather than a rectangle you had to approximate.
 *
 * <p>Deliberately free of Bukkit types: the caller supplies an {@link OpenTest}, so the fill can be
 * exercised against a hand-drawn grid in tests and against the world at runtime.
 */
public final class CourseRegion {

    /** Hard ceiling on a single fill, so a gap in a wall cannot walk the whole world. */
    public static final int DEFAULT_MAX_CELLS = 40_000;

    private CourseRegion() {
    }

    /** Whether the ball can occupy this cell - false for boundary blocks and for holes in the floor. */
    @FunctionalInterface
    public interface OpenTest {
        boolean isOpen(int x, int y, int z);
    }

    /**
     * Whether the ball's own layer is unobstructed here, ignoring whether there is a floor.
     *
     * <p>This is what separates a ledge from a wall. Both are "not open": a ledge because nothing
     * is underneath, a wall because something is in the way. Only the first is a drop the ball
     * would follow, and without the distinction a fill that follows drops will happily descend past
     * a one-block-high separator and carry on underneath it.
     */
    @FunctionalInterface
    public interface ClearTest {
        boolean isClear(int x, int y, int z);
    }

    /**
     * @param cells    every reachable cell, in discovery order
     * @param bounds   smallest box containing them, or null if the start cell was not open
     * @param exhausted true when the fill hit its cell limit, which usually means a leak in the wall
     */
    public record Result(Set<long[]> cells, Bounds bounds, boolean exhausted) {
        public int size() {
            return cells.size();
        }

        public boolean isUsable() {
            return bounds != null && !exhausted;
        }
    }

    /** Default number of blocks a fill will follow a drop downward. */
    public static final int DEFAULT_MAX_DROP = 8;

    /** Floods outward on a single layer, following no drops. */
    public static Result fill(int startX, int startY, int startZ, OpenTest open,
                              int maxCells, int heightPadding) {
        return fill(startX, startY, startZ, open, maxCells, heightPadding, 0);
    }

    /**
     * Floods outward from the start cell, stepping down into drops along the way.
     *
     * <p>Courses are rarely flat: a fairway falls away over a ledge into a lower green, and a
     * single-layer fill would read that ledge as the end of the hole. So when a neighbour is not
     * open at the current height, the fill probes downward up to {@code maxDrop} blocks for the
     * first cell that is, and carries on from there. Only downward - a ball rolls off a ledge but
     * never up one, and a fill that climbed would escape over the course's own walls.
     *
     * @param heightPadding blocks of headroom added above the highest layer reached, so walls
     *                      standing on the green end up inside the resulting box
     * @param maxDrop       how far a single step may fall; 0 keeps the fill on one layer
     */
    public static Result fill(int startX, int startY, int startZ, OpenTest open,
                              int maxCells, int heightPadding, int maxDrop) {
        // No obstruction test supplied: every level counts as clear, so a drop is followed purely
        // on the absence of a floor. Callers reading a real world should pass one.
        return fill(startX, startY, startZ, open, (x, y, z) -> true, maxCells, heightPadding, maxDrop);
    }

    public static Result fill(int startX, int startY, int startZ, OpenTest open, ClearTest clear,
                              int maxCells, int heightPadding, int maxDrop) {
        Set<String> seen = new LinkedHashSet<>();
        Set<long[]> cells = new LinkedHashSet<>();
        if (!open.isOpen(startX, startY, startZ)) {
            return new Result(Set.of(), null, false);
        }
        int limit = Math.max(1, maxCells);
        int drop = Math.max(0, maxDrop);
        int minX = startX, maxX = startX, minZ = startZ, maxZ = startZ;
        int minY = startY, maxY = startY;

        Deque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{startX, startY, startZ});
        seen.add(startX + ":" + startZ);
        boolean exhausted = false;

        while (!queue.isEmpty()) {
            if (cells.size() >= limit) {
                exhausted = true;
                break;
            }
            int[] at = queue.poll();
            int x = at[0], y = at[1], z = at[2];
            cells.add(new long[]{x, y, z});
            minX = Math.min(minX, x);  maxX = Math.max(maxX, x);
            minZ = Math.min(minZ, z);  maxZ = Math.max(maxZ, z);
            minY = Math.min(minY, y);  maxY = Math.max(maxY, y);

            // Four-way, not eight: a diagonal gap between two wall blocks is a wall to a rolling
            // ball, and letting the fill squeeze through it would leak outside the course.
            for (int[] step : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int nx = x + step[0], nz = z + step[1];
                String key = nx + ":" + nz;
                if (seen.contains(key)) {
                    continue;
                }
                int landing = landingHeight(open, clear, nx, y, nz, drop);
                if (landing == NO_LANDING) {
                    continue;
                }
                seen.add(key);
                queue.add(new int[]{nx, landing, nz});
            }
        }
        Bounds bounds = Bounds.of(minX, minY - 1, minZ,
                maxX, maxY + Math.max(0, heightPadding), maxZ);
        return new Result(cells, bounds, exhausted);
    }

    /** Returned by {@link #snapToOpen} when no playable height was found near the start. */
    public static final int NO_START = Integer.MIN_VALUE;

    /**
     * Finds the playable height at a column, searching outward from {@code aroundY}.
     *
     * <p>The fill needs to begin on the ball's own plane - the air sitting on the fairway - and a
     * stored tee is not reliably on it. {@code /settee} records the player's feet, so standing on a
     * slab, a stair or a carpet puts the tee half a block high and flooring it lands on the floor
     * block itself; standing on the boundary wall puts it a block above. Either way the first cell
     * reads as closed and the whole trace returns nothing, whatever the boundary material is, which
     * looks exactly like bounds being broken.
     *
     * <p>So the height is resolved rather than trusted. Ties go upward: at a ledge, the higher of
     * two playable planes is the one the tee was placed on.
     *
     * @param radius how far above and below {@code aroundY} to look
     * @return the height to start from, or {@link #NO_START} if this column is not playable at all
     */
    public static int snapToOpen(int x, int aroundY, int z, OpenTest open, int radius) {
        if (open.isOpen(x, aroundY, z)) {
            return aroundY;
        }
        for (int offset = 1; offset <= Math.max(0, radius); offset++) {
            if (open.isOpen(x, aroundY + offset, z)) {
                return aroundY + offset;
            }
            if (open.isOpen(x, aroundY - offset, z)) {
                return aroundY - offset;
            }
        }
        return NO_START;
    }

    private static final int NO_LANDING = Integer.MIN_VALUE;

    /**
     * The height at which a ball entering this column would come to rest, or {@link #NO_LANDING} if
     * it cannot enter at all.
     *
     * <p>The descent stops at the first level that is obstructed, not merely the first that is
     * unusable. A separator only one block high would otherwise be walked under: the cell beside it
     * is blocked, the cell below is open water, and the fill would surface on the far side as if
     * the separator were not there.
     */
    private static int landingHeight(OpenTest open, ClearTest clear, int x, int fromY, int z,
                                     int maxDrop) {
        for (int y = fromY; y >= fromY - maxDrop; y--) {
            if (!clear.isClear(x, y, z)) {
                return NO_LANDING;   // something is in the way; this is a wall, not a drop
            }
            if (open.isOpen(x, y, z)) {
                return y;
            }
        }
        return NO_LANDING;
    }
}
