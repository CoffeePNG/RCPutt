package com.redcoffee.puttputt.util;

/** Axis-aligned bounding box in block coordinates, inclusive on both corners. */
public record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public static Bounds of(int x1, int y1, int z1, int x2, int y2, int z2) {
        return new Bounds(
                Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
    }

    public boolean contains(Vec3 p) {
        return p.x() >= minX && p.x() <= maxX + 1
                && p.y() >= minY && p.y() <= maxY + 1
                && p.z() >= minZ && p.z() <= maxZ + 1;
    }

    /**
     * Block-cell containment, as opposed to {@link #contains(Vec3)} which tests a continuous
     * position. This is what confines surface sampling to the hole's region.
     */
    public boolean containsBlock(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    /** Grows the box outward, used to leave a sampling margin for perimeter walls. */
    public Bounds expand(int by) {
        return new Bounds(minX - by, minY - by, minZ - by, maxX + by, maxY + by, maxZ + by);
    }

    public Vec3 center() {
        return new Vec3((minX + maxX + 1) / 2.0, (minY + maxY + 1) / 2.0, (minZ + maxZ + 1) / 2.0);
    }
}
