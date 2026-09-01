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
