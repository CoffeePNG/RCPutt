package com.redcoffee.puttputt.command;

import com.redcoffee.puttputt.util.Bounds;
import com.redcoffee.puttputt.util.Vec3;

/**
 * Per-admin editing state for the in-world course builder: which course they are editing and the
 * two corners captured for the next {@code setbounds}.
 *
 * <p>Two-corner capture rather than a WorldEdit selection keeps the builder dependency-free;
 * {@code /puttputt admin pos1|pos2} stand in for the wand.
 */
public final class BuilderSession {

    private String courseId;
    private Vec3 corner1;
    private Vec3 corner2;

    public String courseId() {
        return courseId;
    }

    public void selectCourse(String courseId) {
        this.courseId = courseId;
    }

    public void setCorner1(Vec3 corner) {
        this.corner1 = corner;
    }

    public void setCorner2(Vec3 corner) {
        this.corner2 = corner;
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
