package com.redcoffee.puttputt.surface;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Registry of surfaces plus the global material -> surface mapping.
 * Materials are held as upper-case names rather than Bukkit {@code Material} constants so the
 * registry (and everything downstream of it) can be exercised without a running server.
 */
public final class SurfaceRegistry {

    private final Map<String, Surface> surfaces = new HashMap<>();
    private final Map<String, String> materialMap = new HashMap<>();
    private Surface defaultSurface = Surface.fallback("green", 0.92);

    public void register(Surface surface) {
        surfaces.put(key(surface.id()), surface);
    }

    public void mapMaterial(String material, String surfaceId) {
        materialMap.put(key(material), key(surfaceId));
    }

    public void clear() {
        surfaces.clear();
        materialMap.clear();
    }

    public void setDefaultSurface(Surface surface) {
        this.defaultSurface = surface;
    }

    public Surface defaultSurface() {
        return defaultSurface;
    }

    public Surface byId(String id) {
        return id == null ? null : surfaces.get(key(id));
    }

    public Map<String, Surface> surfaces() {
        return Map.copyOf(surfaces);
    }

    public Map<String, String> materialMap() {
        return Map.copyOf(materialMap);
    }

    /**
     * Resolves the surface for a material. Per-course overrides win over the global map;
     * an unmapped material falls back to the default (green) surface so a half-built course
     * still plays instead of throwing on every tick.
     */
    public Surface forMaterial(String material, Map<String, String> courseOverrides) {
        if (material == null) {
            return defaultSurface;
        }
        String materialKey = key(material);
        // Air is not a surface with no entry - it is the absence of one. Falling back to the
        // default here is what let a ball roll out over open air as if it were fairway. An explicit
        // mapping still wins, so a course can make air behave however it likes.
        if (AIR.contains(materialKey)
                && (courseOverrides == null || !courseOverrides.containsKey(materialKey))
                && !materialMap.containsKey(materialKey)) {
            return Surface.EMPTY;
        }
        String surfaceId = null;
        if (courseOverrides != null && !courseOverrides.isEmpty()) {
            surfaceId = courseOverrides.get(materialKey);
        }
        if (surfaceId == null) {
            surfaceId = materialMap.get(materialKey);
        }
        if (surfaceId == null) {
            return defaultSurface;
        }
        Surface surface = surfaces.get(key(surfaceId));
        return surface != null ? surface : defaultSurface;
    }

    private static final java.util.Set<String> AIR =
            java.util.Set.of("AIR", "CAVE_AIR", "VOID_AIR");

    private static String key(String raw) {
        return raw.trim().toUpperCase(Locale.ROOT);
    }
}
