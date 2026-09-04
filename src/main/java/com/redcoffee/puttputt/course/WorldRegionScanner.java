package com.redcoffee.puttputt.course;

import com.redcoffee.puttputt.surface.SurfaceRegistry;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.World;

/** Builds the {@link CourseRegion.OpenTest} for a real world. */
public final class WorldRegionScanner {

    private WorldRegionScanner() {
    }

    /**
     * A cell is playable when the ball's own layer is clear and there is floor beneath it.
     *
     * <p>Three things stop the fill, which together are what let a builder draw any shape:
     * an explicit boundary material (slabs, say), any block mapped to a wall surface, and a missing
     * floor - so an unfenced drop bounds the course just as a wall does.
     */
    /**
     * Why a cell is not playable, in the words a builder can act on.
     *
     * <p>The trace failing on the tee is reported to a builder who is standing on what looks like
     * perfectly ordinary ground, so "not on open ground" on its own invites disbelief rather than a
     * fix. This names the block and the rule that rejected it.
     *
     * @return null when the cell is open
     */
    public static String closureReason(World world, SurfaceRegistry registry,
                                       Map<String, String> overrides,
                                       Set<Material> boundaryMaterials,
                                       int x, int ballY, int z) {
        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            return "that chunk is not loaded";
        }
        Material atBall = world.getBlockAt(x, ballY, z).getType();
        if (boundaryMaterials.contains(atBall)) {
            return atBall.name() + " at ball height is a boundary material";
        }
        if (registry.forMaterial(atBall.name(), overrides).isWall()) {
            return atBall.name() + " at ball height is mapped to the wall surface";
        }
        if (atBall.isSolid()) {
            return atBall.name() + " at ball height is solid";
        }
        Material floor = world.getBlockAt(x, ballY - 1, z).getType();
        if (boundaryMaterials.contains(floor)) {
            return "the floor (" + floor.name() + ") is a boundary material";
        }
        if (floor.isAir()) {
            return "there is no floor below - nothing to roll on";
        }
        return null;
    }

    /**
     * Whether the ball's layer is unobstructed, ignoring the floor.
     *
     * <p>Deliberately the same three obstruction rules {@code openTest} applies at ball height, so
     * a block that stops the fill on the flat also stops it from descending past.
     */
    public static CourseRegion.ClearTest clearTest(World world, SurfaceRegistry registry,
                                                   Map<String, String> overrides,
                                                   Set<Material> boundaryMaterials) {
        return (x, ballY, z) -> {
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                return false;
            }
            Material atBall = world.getBlockAt(x, ballY, z).getType();
            return !boundaryMaterials.contains(atBall)
                    && !registry.forMaterial(atBall.name(), overrides).isWall()
                    && !atBall.isSolid();
        };
    }

    public static CourseRegion.OpenTest openTest(World world, SurfaceRegistry registry,
                                                 Map<String, String> overrides,
                                                 Set<Material> boundaryMaterials) {
        return (x, ballY, z) -> {
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                return false;
            }
            Material atBall = world.getBlockAt(x, ballY, z).getType();
            if (boundaryMaterials.contains(atBall)) {
                return false;
            }
            if (registry.forMaterial(atBall.name(), overrides).isWall()) {
                return false;
            }
            // Anything solid at ball height blocks the ball even if it is not a mapped wall.
            if (atBall.isSolid()) {
                return false;
            }
            Material floor = world.getBlockAt(x, ballY - 1, z).getType();
            if (boundaryMaterials.contains(floor)) {
                return false;
            }
            return !floor.isAir();
        };
    }
}
