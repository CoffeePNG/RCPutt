package com.redcoffee.puttputt.game;

import com.redcoffee.puttputt.surface.Surface;
import com.redcoffee.puttputt.surface.SurfaceRegistry;
import java.util.Map;
import org.bukkit.World;

/**
 * Reads surfaces straight out of the world for one hole, applying that hole's material overrides
 * on top of the global map.
 *
 * <p>Chunk loads are never forced: a cell in an unloaded chunk reports as the default surface. A
 * ball can only get there by leaving the hole's bounds, which the round logic already treats as
 * out of play.
 */
public final class WorldSurfaceSampler implements SurfaceSampler {

    private final World world;
    private final SurfaceRegistry registry;
    private final Map<String, String> overrides;

    public WorldSurfaceSampler(World world, SurfaceRegistry registry, Map<String, String> overrides) {
        this.world = world;
        this.registry = registry;
        this.overrides = overrides;
    }

    @Override
    public Surface at(int x, int y, int z) {
        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            return registry.defaultSurface();
        }
        return registry.forMaterial(world.getBlockAt(x, y, z).getType().name(), overrides);
    }
}
