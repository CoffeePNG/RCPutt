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

    /**
     * Floods outward on one horizontal layer from the start cell.
     *
     * @param heightPadding blocks of headroom added above the layer, so walls standing on the green
     *                      end up inside the resulting box
     */
    public static Result fill(int startX, int startY, int startZ, OpenTest open,
                              int maxCells, int heightPadding) {
        Set<String> seen = new LinkedHashSet<>();
        Set<long[]> cells = new LinkedHashSet<>();
        if (!open.isOpen(startX, startY, startZ)) {
            return new Result(Set.of(), null, false);
        }
        int limit = Math.max(1, maxCells);
        int minX = startX, maxX = startX, minZ = startZ, maxZ = startZ;

        Deque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{startX, startZ});
        seen.add(startX + ":" + startZ);
        boolean exhausted = false;

        while (!queue.isEmpty()) {
            if (cells.size() >= limit) {
                exhausted = true;
                break;
            }
            int[] at = queue.poll();
            int x = at[0], z = at[1];
            cells.add(new long[]{x, startY, z});
            minX = Math.min(minX, x);  maxX = Math.max(maxX, x);
            minZ = Math.min(minZ, z);  maxZ = Math.max(maxZ, z);

            // Four-way, not eight: a diagonal gap between two wall blocks is a wall to a rolling
            // ball, and letting the fill squeeze through it would leak outside the course.
            for (int[] step : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int nx = x + step[0], nz = z + step[1];
                String key = nx + ":" + nz;
                if (seen.contains(key) || !open.isOpen(nx, startY, nz)) {
                    continue;
                }
                seen.add(key);
                queue.add(new int[]{nx, nz});
            }
        }
        Bounds bounds = Bounds.of(minX, startY - 1, minZ,
                maxX, startY + Math.max(0, heightPadding), maxZ);
        return new Result(cells, bounds, exhausted);
    }
}
