package com.redcoffee.puttputt.game;

import com.redcoffee.puttputt.surface.Impulse;
import com.redcoffee.puttputt.surface.Surface;
import com.redcoffee.puttputt.surface.SurfaceRegistry;
import com.redcoffee.puttputt.surface.SurfaceType;
import com.redcoffee.puttputt.util.Vec3;
import java.util.Locale;
import java.util.Map;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;

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
    private final Map<String, Double> facingBoosts;

    public WorldSurfaceSampler(World world, SurfaceRegistry registry, Map<String, String> overrides) {
        this(world, registry, overrides, Map.of());
    }

    /**
     * @param facingBoosts material name -> push strength for blocks that boost the way they face
     */
    public WorldSurfaceSampler(World world, SurfaceRegistry registry, Map<String, String> overrides,
                               Map<String, Double> facingBoosts) {
        this.world = world;
        this.registry = registry;
        this.overrides = overrides;
        this.facingBoosts = facingBoosts;
    }

    @Override
    public Surface at(int x, int y, int z) {
        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            return registry.defaultSurface();
        }
        Block block = world.getBlockAt(x, y, z);
        String material = block.getType().name();

        // A facing boost is one material doing the work of four: the direction comes from how the
        // block was placed, so a builder turns an arrow rather than looking up which colour pushes
        // north. An explicit override still wins - a course may want this block to be something else.
        Double strength = facingBoosts.get(material.toUpperCase(Locale.ROOT));
        if (strength != null && !overrides.containsKey(material.toUpperCase(Locale.ROOT))) {
            Vec3 push = facingVector(block);
            if (push != null) {
                return new Surface(material.toLowerCase(Locale.ROOT), SurfaceType.IMPULSE,
                        registry.defaultSurface().friction(), 0.0, 0,
                        registry.defaultSurface().reset(), new Impulse(push, strength), false);
            }
        }
        return registry.forMaterial(material, overrides);
    }

    /** The block's horizontal facing as a unit vector, or null if it has no facing to read. */
    private static Vec3 facingVector(Block block) {
        if (!(block.getBlockData() instanceof Directional directional)) {
            return null;
        }
        BlockFace face = directional.getFacing();
        // Vertical facings carry no useful push for a ball rolling on a plane.
        if (face.getModX() == 0 && face.getModZ() == 0) {
            return null;
        }
        return new Vec3(face.getModX(), 0, face.getModZ());
    }
}
