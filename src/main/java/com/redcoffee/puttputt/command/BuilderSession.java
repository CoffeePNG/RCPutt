package com.redcoffee.puttputt.command;

import com.redcoffee.puttputt.util.Bounds;
import com.redcoffee.puttputt.util.Vec3;

/**
 * Per-admin editing state for the in-world course builder: which course they are editing and the
 * two corners captured for the next {@code setbounds}.
 *
 * <p>Two-corner capture rather than a WorldEdit selection keeps the builder dependency-free. The
 * corners can be set by standing on them ({@code /puttputt admin pos1|pos2}) or by clicking them
 * with the builder wand, which is the same selection either way.
 */
public final class BuilderSession {

    private String courseId;
    private int currentHole = 1;
    private Vec3 corner1;
    private Vec3 corner2;

    public String courseId() {
        return courseId;
    }

    public void selectCourse(String courseId) {
        this.courseId = courseId;
    }

    /** The hole the wand edits. Commands that name a hole explicitly still win. */
    public int currentHole() {
        return currentHole;
    }

    public void selectHole(int hole) {
        this.currentHole = hole;
    }

    /**
     * The ball plane for a clicked block: centred on the block in X and Z, and sitting on its
     * <em>top face</em> in Y.
     *
     * <p>This is why the wand is better than standing on the spot: it reads the block itself rather
     * than the player's feet, so a slab, stair or carpet underfoot can no longer capture a
     * fractional Y that leaves the ball sampling the wrong ground layer.
     */
    public static Vec3 topFaceOf(int blockX, int blockY, int blockZ) {
        return new Vec3(blockX + 0.5, blockY + 1.0, blockZ + 0.5);
    }

    /**
     * The clicked block itself, centred in X and Z but keeping its own Y.
     *
     * <p>Bounds corners must NOT use {@link #topFaceOf}: that returns {@code blockY + 1}, and
     * {@link #toBounds()} floors it, so marking two floor blocks at y=64 recorded the box as y=65
     * and excluded the very layer the ball rolls on.
     */
    public static Vec3 blockOf(int blockX, int blockY, int blockZ) {
        return new Vec3(blockX + 0.5, blockY, blockZ + 0.5);
    }

    public void setCorner1(Vec3 corner) {
        this.corner1 = corner;
    }

    public void setCorner2(Vec3 corner) {
        this.corner2 = corner;
    }

    public Vec3 corner1() {
        return corner1;
    }

    public Vec3 corner2() {
        return corner2;
    }

    public boolean hasBothCorners() {
        return corner1 != null && corner2 != null;
    }

    public Bounds toBounds() {
        return Bounds.of(
                corner1.blockX(), corner1.blockY(), corner1.blockZ(),
                corner2.blockX(), corner2.blockY(), corner2.blockZ());
    }
}
