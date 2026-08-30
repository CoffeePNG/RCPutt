package com.redcoffee.puttputt.game;

import com.redcoffee.puttputt.surface.Surface;

/**
 * Reads the surface at a block cell. The world-backed implementation lives in
 * {@code WorldSurfaceSampler}; tests supply a hand-built grid instead.
 */
@FunctionalInterface
public interface SurfaceSampler {

    Surface at(int x, int y, int z);
}
