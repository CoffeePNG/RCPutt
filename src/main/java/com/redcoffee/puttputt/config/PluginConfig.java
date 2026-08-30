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
    private ItemDefinition putterItem = new ItemDefinition("BOW", null, null, "<gold>Putter</gold>");
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
        messages.load(config.getConfigurationSection("messages"));
        ballItem = ItemDefinition.read(config.getConfigurationSection("items.ball"), "SNOWBALL", "<white>Golf Ball</white>");
        putterItem = ItemDefinition.read(config.getConfigurationSection("items.putter"), "BOW", "<gold>Putter</gold>");
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
                    section.getDouble("sink_radius", defaults.sinkRadius()),
                    section.getDouble("max_putt_power", defaults.maxPuttPower()));
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
            Surface surface = new Surface(
                    id,
                    type,
                    node.getDouble("friction", 0.92),
                    node.getDouble("restitution", 0.70),
                    node.getInt("penalty", 1),
                    ResetMode.parse(node.getString("reset"), ResetMode.LAST_REST),
                    readImpulse(node.getConfigurationSection("impulse"), id));
            if (type == SurfaceType.IMPULSE && !surface.hasImpulse()) {
                logger.warning("Surface '" + id + "' is type impulse but defines no impulse; it will just roll.");
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
