package com.redcoffee.puttputt.game;

import com.redcoffee.puttputt.surface.Surface;
import com.redcoffee.puttputt.util.Bounds;
import com.redcoffee.puttputt.util.Vec3;

/**
 * Everything the integrator may read about the hole being played: its surfaces, its cup, its
 * region, and any teleport pads.
 *
 * <p>The region is enforced here rather than merely checked afterwards. {@link #surfaceAt} refuses
 * to consult a block outside the hole's bounds and hands back {@code outside} instead, so the
 * physics <em>cannot</em> read world blocks that are not part of the course - a green-terracotta
 * build a thousand blocks away can never influence a ball. Sampling is allowed one cell beyond the
 * bounds so a perimeter wall standing on the boundary still bounces.
 *
 * @param surfaces world lookup for this hole
 * @param cup      cup centre, for the sink gate
 * @param bounds   the hole's region; sampling is confined to it
 * @param outside  what a cell beyond the region reports as - a wall by default, which turns the
 *                 bounds into an invisible barrier rather than relying on the out-of-bounds reset
 * @param teleports pads anchored on this hole
 */
public record HoleContext(
        SurfaceSampler surfaces,
        Vec3 cup,
        Bounds bounds,
        Surface outside,
        TeleportLookup teleports) {

    public HoleContext {
        if (teleports == null) {
            teleports = TeleportLookup.NONE;
        }
    }

    /** Surface at a cell, confined to the hole's region. */
    public Surface surfaceAt(int x, int y, int z) {
        if (bounds != null && !bounds.expand(1).containsBlock(x, y, z)) {
            return outside;
        }
        return surfaces.at(x, y, z);
    }

    public Teleport teleportAt(int x, int y, int z) {
        if (bounds != null && !bounds.containsBlock(x, y, z)) {
            return null;
        }
        return teleports.at(x, y, z);
    }
}
