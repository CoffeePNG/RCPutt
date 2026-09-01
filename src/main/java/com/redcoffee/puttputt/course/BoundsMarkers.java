package com.redcoffee.puttputt.course;

import com.redcoffee.puttputt.util.Bounds;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

/**
 * Derives a hole's bounds from marker blocks placed in the world.
 *
 * <p>Two-corner capture is invisible once set: nothing in the world shows where the box is, and
 * re-editing means remembering which corners you clicked. A marker block is the opposite - you can
 * see the boundary while building, move a corner by moving a block, and check it at a glance.
 *
 * <p>The scan is deliberately bounded and only ever runs from an admin command, never from the
 * physics loop.
 */
public final class BoundsMarkers {

    /** A scan wider than this would search millions of cells; refuse rather than freeze the server. */
    public static final int MAX_RADIUS = 128;

    private BoundsMarkers() {
    }

    /** What a scan found, so the command layer can explain a failure rather than just say "no". */
    public record Result(Bounds bounds, int markersFound) {
        public boolean isUsable() {
            return bounds != null;
        }
    }

    /**
     * Scans a cube around {@code centre} for marker blocks and builds the box that contains them.
     *
     * @param heightPadding blocks of headroom added above the highest marker, so a marker laid flat
     *                      on the floor still produces a box tall enough to hold the walls
     */
    public static Result scan(Location centre, Material marker, int radius, int heightPadding) {
        World world = centre.getWorld();
        int limit = Math.min(Math.max(1, radius), MAX_RADIUS);
        int cx = centre.getBlockX();
        int cy = centre.getBlockY();
        int cz = centre.getBlockZ();

        List<int[]> found = new ArrayList<>();
        int minY = Math.max(world.getMinHeight(), cy - limit);
        int maxY = Math.min(world.getMaxHeight() - 1, cy + limit);

        for (int x = cx - limit; x <= cx + limit; x++) {
            for (int z = cz - limit; z <= cz + limit; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    if (world.getBlockAt(x, y, z).getType() == marker) {
                        found.add(new int[]{x, y, z});
                    }
                }
            }
        }
        if (found.size() < 2) {
            return new Result(null, found.size());
        }

        int lowX = Integer.MAX_VALUE, lowY = Integer.MAX_VALUE, lowZ = Integer.MAX_VALUE;
        int highX = Integer.MIN_VALUE, highY = Integer.MIN_VALUE, highZ = Integer.MIN_VALUE;
        for (int[] block : found) {
            lowX = Math.min(lowX, block[0]);   highX = Math.max(highX, block[0]);
            lowY = Math.min(lowY, block[1]);   highY = Math.max(highY, block[1]);
            lowZ = Math.min(lowZ, block[2]);   highZ = Math.max(highZ, block[2]);
        }
        // Headroom above the markers so walls standing on the green are inside the box even when
        // every marker was laid flat on the floor.
        return new Result(Bounds.of(lowX, lowY, lowZ, highX, highY + Math.max(0, heightPadding), highZ),
                found.size());
    }
}
