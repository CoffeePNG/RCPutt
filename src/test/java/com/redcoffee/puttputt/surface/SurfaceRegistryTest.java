package com.redcoffee.puttputt.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.redcoffee.puttputt.util.Vec3;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SurfaceRegistryTest {

    private final SurfaceRegistry registry = new SurfaceRegistry();
    private final Surface green = Surface.fallback("green", 0.92);
    private final Surface sand = Surface.fallback("sand", 0.75);
    private final Surface ice = Surface.fallback("ice", 0.99);

    @BeforeEach
    void setUp() {
        registry.register(green);
        registry.register(sand);
        registry.register(ice);
        registry.setDefaultSurface(green);
        registry.mapMaterial("SAND", "sand");
    }

    @Test
    void mapsMaterialsCaseInsensitively() {
        assertSame(sand, registry.forMaterial("sand", Map.of()));
        assertSame(sand, registry.forMaterial("SAND", null));
    }

    @Test
    void unmappedMaterialFallsBackToTheDefault() {
        assertSame(green, registry.forMaterial("DIRT", Map.of()));
    }

    @Test
    void courseOverrideBeatsTheGlobalMap() {
        assertSame(ice, registry.forMaterial("SAND", Map.of("SAND", "ICE")));
    }

    @Test
    void mappingToAnUnknownSurfaceFallsBackRatherThanThrowing() {
        registry.mapMaterial("STONE", "nope");
        assertSame(green, registry.forMaterial("STONE", Map.of()));
    }

    @Test
    void impulseDirectionIsNormalisedSoStrengthIsTheOnlyKnob() {
        Impulse impulse = new Impulse(new Vec3(0, 0, -5), 0.35);

        assertEquals(1.0, impulse.direction().length(), 1.0e-9);
        assertEquals(0.35, impulse.asVelocityDelta().length(), 1.0e-9);
    }

    @Test
    void surfaceTypeAliasesParse() {
        assertEquals(SurfaceType.HAZARD, SurfaceType.parse("water", SurfaceType.ROLL));
        assertEquals(SurfaceType.IMPULSE, SurfaceType.parse("BOOSTER", SurfaceType.ROLL));
        assertEquals(SurfaceType.HOLE, SurfaceType.parse("cup", SurfaceType.ROLL));
        assertEquals(SurfaceType.ROLL, SurfaceType.parse("nonsense", SurfaceType.ROLL));
        assertEquals(SurfaceType.WALL, SurfaceType.parse(null, SurfaceType.WALL));
    }
}
