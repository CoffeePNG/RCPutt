package com.redcoffee.puttputt.config;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

/**
 * Brings an existing {@code config.yml} up to the current version.
 *
 * <p>This exists because {@code saveDefaultConfig()} only writes the file when it is <em>absent</em>.
 * A server upgrading in place keeps its old config, so every key added by a later version is simply
 * missing - and worse, keys whose <em>meaning</em> changed keep their old value. That is how a v1
 * server ended up handing out a bow after v2 made the putter a shovel: the key was still there, so
 * nothing looked broken, it just silently played wrong.
 *
 * <p>Missing keys are filled from the packaged defaults. Keys that changed meaning are rewritten
 * explicitly below, and every change is logged so an operator can see what happened to their file.
 */
public final class ConfigMigrator {

    /** Bump when a release adds keys or changes what an existing key means. */
    public static final int CURRENT_VERSION = 4;

    private static final String VERSION_KEY = "config-version";

    private final Plugin plugin;
    private final Logger logger;

    public ConfigMigrator(Plugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    /**
     * Migrates in place and saves if anything changed.
     *
     * @return the version the config was on before migrating
     */
    public int migrate(FileConfiguration config) {
        int from = config.getInt(VERSION_KEY, 1);
        if (from >= CURRENT_VERSION) {
            return from;
        }
        List<String> changes = new ArrayList<>();

        YamlConfiguration packaged = packagedDefaults();
        if (packaged != null) {
            for (String key : packaged.getKeys(true)) {
                // isSet, not contains: getConfig() carries the packaged config as its defaults, so
                // contains() answers true for keys that are only present as a default and the merge
                // would silently do nothing.
                if (!config.isSet(key) && !packaged.isConfigurationSection(key)) {
                    config.set(key, packaged.get(key));
                    changes.add("added " + key);
                }
            }
        }

        // v1 -> v2: the putter became a shovel. A bow cannot drive the boss-bar power meter, so a
        // stale value here is not a preference to respect - it is a broken round.
        if (from < 2) {
            String putter = config.getString("items.putter.material", "");
            if ("BOW".equalsIgnoreCase(putter.trim())) {
                config.set("items.putter.material", "IRON_SHOVEL");
                changes.add("items.putter.material: BOW -> IRON_SHOVEL (v2 putters are shovels)");
            }
        }

        // v2 -> v3: messages moved to RCUI, which owns the catalog and the shared prefix. Any
        // customised strings left in config.yml would silently stop being read, so say so loudly
        // rather than deleting them quietly - the operator needs to re-apply them in RCUI's catalog.
        if (from < 3 && config.isSet("messages")) {
            logger.warning("Message customisation has moved to RCUI. Your config.yml still has a "
                    + "'messages' block; it is no longer read. Re-apply your wording in RCUI's "
                    + "catalog for RCPuttPutt, then delete the block.");
            changes.add("messages: now owned by RCUI (your block was left in place, unread)");
        }

        // v3 -> v4: bounds.max-drop arrived, so a traced hole follows a fairway over a ledge
        // instead of stopping at it. The packaged-defaults merge above adds the key; this step
        // exists so servers already on v3 run that merge at all.

        config.set(VERSION_KEY, CURRENT_VERSION);
        plugin.saveConfig();

        logger.warning("Updated config.yml from version " + from + " to " + CURRENT_VERSION
                + " (" + changes.size() + " change(s)). Your existing settings were kept.");
        for (String change : changes) {
            logger.info("  config: " + change);
        }
        return from;
    }

    private YamlConfiguration packagedDefaults() {
        try (var stream = plugin.getResource("config.yml")) {
            if (stream == null) {
                return null;
            }
            return YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            logger.warning("Could not read the packaged config.yml to migrate: " + ex.getMessage());
            return null;
        }
    }

    /** Materials a builder is likely to have meant as a wall. Used by the course check command. */
    public static boolean looksLikeAWall(String material) {
        String name = material.toUpperCase(Locale.ROOT);
        return name.endsWith("_WALL") || name.endsWith("_FENCE") || name.contains("BRICK")
                || name.contains("STONE") || name.contains("QUARTZ") || name.contains("PLANKS")
                || name.contains("LOG") || name.contains("CONCRETE") || name.contains("TERRACOTTA");
    }
}
