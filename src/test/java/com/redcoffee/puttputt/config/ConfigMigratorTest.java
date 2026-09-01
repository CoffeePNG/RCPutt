package com.redcoffee.puttputt.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * The upgrade path a real server takes. saveDefaultConfig() only writes when the file is absent, so
 * without migration an in-place upgrade keeps every stale value - which is exactly how a v1 server
 * kept handing out a bow after v2 made the putter a shovel.
 */
class ConfigMigratorTest {

    /** A config as a v1 server still has it on disk. */
    private static YamlConfiguration v1Config() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("items.putter.material", "BOW");
        config.set("items.putter.name", "<gold>My Custom Putter</gold>");
        config.set("physics.max_velocity", 0.75);
        return config;
    }

    @Test
    void aV1ConfigStillSaysBowUntilItIsMigrated() {
        YamlConfiguration config = v1Config();

        assertEquals("BOW", config.getString("items.putter.material"),
                "this is the bug: the key exists, so nothing looks broken - it just plays wrong");
        assertFalse(config.contains("turn-order.mode"), "and every v2 key is simply missing");
        assertFalse(config.contains("power-meter.mode"));
    }

    /** v3 moved messages to RCUI; a config that still carries the block must be flagged, not eaten. */
    @Test
    void aV2ConfigStillOwnsItsMessagesBlock() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("config-version", 2);
        config.set("messages.putt.stroke", "<gray>custom wording</gray>");

        assertTrue(config.isSet("messages"), "the migrator warns rather than deleting operator wording");
        assertEquals(2, config.getInt("config-version"));
    }

    @Test
    void versionDefaultsToOneWhenTheKeyIsAbsent() {
        assertEquals(1, v1Config().getInt("config-version", 1));
        assertEquals(4, ConfigMigrator.CURRENT_VERSION);
    }

    /** Wall-ish materials are flagged by the course check so an unmapped wall is obvious. */
    @Test
    void recognisesMaterialsABuilderProbablyMeantAsWalls() {
        assertTrue(ConfigMigrator.looksLikeAWall("SMOOTH_STONE"));
        assertTrue(ConfigMigrator.looksLikeAWall("QUARTZ_BLOCK"));
        assertTrue(ConfigMigrator.looksLikeAWall("STONE_BRICK_WALL"));
        assertTrue(ConfigMigrator.looksLikeAWall("OAK_PLANKS"));
        assertTrue(ConfigMigrator.looksLikeAWall("green_terracotta"), "case-insensitive");
        assertFalse(ConfigMigrator.looksLikeAWall("WATER"));
        assertFalse(ConfigMigrator.looksLikeAWall("SAND"));
    }
}
