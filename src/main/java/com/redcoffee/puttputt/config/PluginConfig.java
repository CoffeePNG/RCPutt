package com.redcoffee.puttputt.config;

import com.redcoffee.puttputt.surface.Impulse;
import com.redcoffee.puttputt.surface.ResetMode;
import com.redcoffee.puttputt.surface.Surface;
import com.redcoffee.puttputt.surface.SurfaceRegistry;
import com.redcoffee.puttputt.surface.SurfaceType;
import com.redcoffee.puttputt.util.Vec3;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Reads {@code config.yml} into the runtime objects the rest of the plugin uses. Everything
 * physics-, surface- and text-related is config-driven, so adding a bumper or a new hazard is a
 * registry entry rather than a code change.
 */
public final class PluginConfig {

    private final Logger logger;
    private PhysicsConfig physics = PhysicsConfig.DEFAULTS;
    private final SurfaceRegistry surfaces = new SurfaceRegistry();
    private final Messages messages = new Messages();
    private ItemDefinition ballItem = new ItemDefinition("SNOWBALL", null, null, "<white>Golf Ball</white>");
    private ItemDefinition putterItem = new ItemDefinition("IRON_SHOVEL", null, null, "<gold>Putter</gold>");
    private ItemDefinition wandItem =
            new ItemDefinition("BLAZE_ROD", null, null, "<light_purple>Course Wand</light_purple>");
    private BallCollisionConfig ballCollision = BallCollisionConfig.DEFAULTS;
    private TurnConfig turns = TurnConfig.DEFAULTS;
    private PowerMeterConfig powerMeter = PowerMeterConfig.DEFAULTS;
    private SnapshotConfig snapshots = SnapshotConfig.DEFAULTS;
    private boolean confineToBounds = true;
    private String boundsMarker = "AMETHYST_BLOCK";
    private java.util.List<String> boundaryMaterials = java.util.List.of("SMOOTH_STONE_SLAB");
    private int boundsMaxCells = com.redcoffee.puttputt.course.CourseRegion.DEFAULT_MAX_CELLS;
    private int boundsScanRadius = 64;
    private int boundsHeightPadding = 4;
    private boolean economyEnabled;
    private int leaderboardSize = 10;
    private double outOfBoundsPenalty = 1;

    public PluginConfig(Logger logger) {
        this.logger = logger;
    }

    public void load(FileConfiguration config) {
        physics = readPhysics(config.getConfigurationSection("physics"));
        readSurfaces(config.getConfigurationSection("surfaces"));
        readMaterialMap(config.getConfigurationSection("material_map"));
        ballItem = ItemDefinition.read(config.getConfigurationSection("items.ball"), "SNOWBALL", "<white>Golf Ball</white>");
        putterItem = ItemDefinition.read(config.getConfigurationSection("items.putter"), "IRON_SHOVEL", "<gold>Putter</gold>");
        ballCollision = readBallCollision(config.getConfigurationSection("ball-collision"));
        turns = readTurns(config);
        powerMeter = readPowerMeter(config.getConfigurationSection("power-meter"));
        snapshots = readSnapshots(config.getConfigurationSection("snapshot"));
        wandItem = ItemDefinition.read(config.getConfigurationSection("items.wand"),
                "BLAZE_ROD", "<light_purple>Course Wand</light_purple>");
        confineToBounds = config.getBoolean("bounds.confine", true);
        boundsMarker = config.getString("bounds.marker-material", "AMETHYST_BLOCK");
        java.util.List<String> configured = config.getStringList("bounds.boundary-materials");
        boundaryMaterials = configured.isEmpty() ? java.util.List.of("SMOOTH_STONE_SLAB") : java.util.List.copyOf(configured);
        boundsMaxCells = Math.max(64, config.getInt("bounds.max-cells",
                com.redcoffee.puttputt.course.CourseRegion.DEFAULT_MAX_CELLS));
        boundsScanRadius = Math.max(1, config.getInt("bounds.scan-radius", 64));
        boundsHeightPadding = Math.max(0, config.getInt("bounds.height-padding", 4));
        economyEnabled = config.getBoolean("economy.enabled", false);
        leaderboardSize = Math.max(1, config.getInt("leaderboard.size", 10));
        outOfBoundsPenalty = Math.max(0, config.getInt("out_of_bounds.penalty", 1));
    }

    private PhysicsConfig readPhysics(ConfigurationSection section) {
        if (section == null) {
            return PhysicsConfig.DEFAULTS;
        }
        PhysicsConfig defaults = PhysicsConfig.DEFAULTS;
        try {
            return new PhysicsConfig(
                    section.getDouble("max_velocity", defaults.maxVelocity()),
                    section.getDouble("rest_epsilon", defaults.restEpsilon()),
                    section.getDouble("max_sink_speed", defaults.maxSinkSpeed()),
                    section.getDouble("sink_radius", defaults.sinkRadius()));
        } catch (IllegalArgumentException ex) {
            // A bad constant (usually max_velocity >= 1, which would let balls tunnel through walls)
            // must not start a server that silently plays wrong.
            logger.warning("Invalid physics config (" + ex.getMessage() + "); using defaults.");
            return defaults;
        }
    }

    private void readSurfaces(ConfigurationSection section) {
        surfaces.clear();
        if (section == null) {
            logger.warning("No surfaces configured; every block will behave like plain green.");
            return;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection node = section.getConfigurationSection(id);
            if (node == null) {
                continue;
            }
            SurfaceType type = SurfaceType.parse(node.getString("type"), SurfaceType.ROLL);
            // A current is an impulse with a different name and, usually, preventRest - so accept
            // either key and let the type decide how it behaves.
            Impulse push = readImpulse(node.getConfigurationSection("impulse"), id);
            if (push == null) {
                push = readImpulse(node.getConfigurationSection("current"), id);
            }
            Surface surface = new Surface(
                    id,
                    type,
                    node.getDouble("friction", 0.92),
                    node.getDouble("restitution", 0.70),
                    node.getInt("penalty", 1),
                    ResetMode.parse(node.getString("reset"), ResetMode.LAST_REST),
                    push,
                    node.getBoolean("preventRest", type == SurfaceType.CURRENT));
            if ((type == SurfaceType.IMPULSE || type == SurfaceType.CURRENT) && !surface.hasImpulse()) {
                logger.warning("Surface '" + id + "' is type " + type
                        + " but defines no direction/strength; it will just roll.");
            }
            surfaces.register(surface);
        }
        Surface green = surfaces.byId("green");
        surfaces.setDefaultSurface(green != null ? green : Surface.fallback("green", 0.92));
    }

    private Impulse readImpulse(ConfigurationSection section, String surfaceId) {
        if (section == null) {
            return null;
        }
        List<?> direction = section.getList("direction");
        if (direction == null || direction.size() < 3) {
            logger.warning("Surface '" + surfaceId + "' has an impulse with no 3-component direction; ignoring it.");
            return null;
        }
        double x = toDouble(direction.get(0));
        double y = toDouble(direction.get(1));
        double z = toDouble(direction.get(2));
        Vec3 vec = new Vec3(x, y, z);
        if (vec.lengthSquared() == 0.0) {
            logger.warning("Surface '" + surfaceId + "' has a zero-length impulse direction; ignoring it.");
            return null;
        }
        return new Impulse(vec, section.getDouble("strength", 0.35));
    }

    private static double toDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    private void readMaterialMap(ConfigurationSection section) {
        if (section == null) {
            return;
        }
        for (String material : section.getKeys(false)) {
            String surfaceId = section.getString(material);
            if (surfaceId == null) {
                continue;
            }
            if (surfaces.byId(surfaceId) == null) {
                logger.warning("material_map maps " + material + " to unknown surface '" + surfaceId + "'; ignoring.");
                continue;
            }
            surfaces.mapMaterial(material, surfaceId);
        }
    }

    private BallCollisionConfig readBallCollision(ConfigurationSection section) {
        if (section == null) {
            return BallCollisionConfig.DEFAULTS;
        }
        BallCollisionConfig defaults = BallCollisionConfig.DEFAULTS;
        try {
            return new BallCollisionConfig(
                    section.getBoolean("enabled", defaults.enabled()),
                    section.getDouble("restitution", defaults.restitution()),
                    section.getDouble("radius", defaults.radius()),
                    section.getBoolean("allow-knock-in", defaults.allowKnockIn()));
        } catch (IllegalArgumentException ex) {
            logger.warning("Invalid ball-collision config (" + ex.getMessage() + "); using defaults.");
            return defaults;
        }
    }

    private TurnConfig readTurns(FileConfiguration config) {
        TurnConfig defaults = TurnConfig.DEFAULTS;
        try {
            return new TurnConfig(
                    TurnOrderMode.parse(config.getString("turn-order.mode"), defaults.mode()),
                    config.getInt("shot-clock.seconds", defaults.shotClockSeconds()),
                    config.getInt("shot-clock.timeout-penalty", defaults.timeoutPenalty()),
                    config.getInt("shot-clock.max-consecutive-timeouts", defaults.maxConsecutiveTimeouts()),
                    config.getInt("max-strokes-per-hole", defaults.maxStrokesPerHole()));
        } catch (IllegalArgumentException ex) {
            logger.warning("Invalid turn/shot-clock config (" + ex.getMessage() + "); using defaults.");
            return defaults;
        }
    }

    private PowerMeterConfig readPowerMeter(ConfigurationSection section) {
        if (section == null) {
            return PowerMeterConfig.DEFAULTS;
        }
        PowerMeterConfig defaults = PowerMeterConfig.DEFAULTS;
        try {
            return new PowerMeterConfig(
                    PowerMeterConfig.Mode.parse(section.getString("mode"), defaults.mode()),
                    section.getDouble("min-velocity", defaults.minVelocity()),
                    section.getDouble("max-velocity", defaults.maxVelocity()),
                    section.getInt("sweep-ticks", defaults.sweepTicks()),
                    section.getDouble("sneak-rate", defaults.sneakRate()));
        } catch (IllegalArgumentException ex) {
            logger.warning("Invalid power-meter config (" + ex.getMessage() + "); using defaults.");
            return defaults;
        }
    }

    private SnapshotConfig readSnapshots(ConfigurationSection section) {
        if (section == null) {
            return SnapshotConfig.DEFAULTS;
        }
        SnapshotConfig defaults = SnapshotConfig.DEFAULTS;
        try {
            return new SnapshotConfig(
                    section.getInt("interval-seconds", defaults.intervalSeconds()),
                    section.getInt("resume-window-minutes", defaults.resumeWindowMinutes()));
        } catch (IllegalArgumentException ex) {
            logger.warning("Invalid snapshot config (" + ex.getMessage() + "); using defaults.");
            return defaults;
        }
    }

    public BallCollisionConfig ballCollision() {
        return ballCollision;
    }

    public TurnConfig turns() {
        return turns;
    }

    public PowerMeterConfig powerMeter() {
        return powerMeter;
    }

    public SnapshotConfig snapshots() {
        return snapshots;
    }

    public PhysicsConfig physics() {
        return physics;
    }

    public SurfaceRegistry surfaces() {
        return surfaces;
    }

    public Messages messages() {
        return messages;
    }

    public ItemDefinition ballItem() {
        return ballItem;
    }

    public ItemDefinition putterItem() {
        return putterItem;
    }

    public ItemDefinition wandItem() {
        return wandItem;
    }

    /**
     * What a block outside a hole's region reports as. A wall by default, which makes the bounds an
     * invisible barrier and guarantees a ball can never reach - let alone read - a block that is
     * not part of the course. Set {@code bounds.confine: false} to fall back to the plain
     * out-of-bounds reset instead.
     */
    public Surface outsideBoundsSurface() {
        if (!confineToBounds) {
            return surfaces.defaultSurface();
        }
        Surface wall = surfaces.byId("wall");
        return wall != null && wall.isWall()
                ? wall
                : new Surface("bounds", SurfaceType.WALL, 1.0, 0.70, 0, ResetMode.LAST_REST, null, false);
    }

    /** Block material that marks a hole's corners in the world. */
    public String boundsMarker() {
        return boundsMarker;
    }

    /** Materials that bound a hole, whatever shape it is drawn in. */
    public java.util.List<String> boundaryMaterials() {
        return boundaryMaterials;
    }

    public java.util.Set<org.bukkit.Material> boundaryMaterialSet() {
        java.util.Set<org.bukkit.Material> out = new java.util.LinkedHashSet<>();
        for (String name : boundaryMaterials) {
            org.bukkit.Material material = org.bukkit.Material.matchMaterial(name);
            if (material == null) {
                logger.warning("Unknown bounds.boundary-materials entry '" + name + "'; ignoring it.");
            } else {
                out.add(material);
            }
        }
        return out;
    }

    public int boundsMaxCells() {
        return boundsMaxCells;
    }

    public int boundsScanRadius() {
        return boundsScanRadius;
    }

    public int boundsHeightPadding() {
        return boundsHeightPadding;
    }

    public boolean confineToBounds() {
        return confineToBounds;
    }

    public boolean economyEnabled() {
        return economyEnabled;
    }

    public int leaderboardSize() {
        return leaderboardSize;
    }

    public int outOfBoundsPenalty() {
        return (int) outOfBoundsPenalty;
    }
}
