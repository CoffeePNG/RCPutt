package com.redcoffee.puttputt.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MessagesTest {

    private final Messages messages = new Messages();

    @BeforeEach
    void setUp() {
        MemoryConfiguration config = new MemoryConfiguration();
        config.set("prefix", "<gray>[PP]</gray> ");
        config.set("line", "on <course> by <player>");
        messages.load(config);
    }

    /**
     * A course display name is documented as MiniMessage in the course file, so a Component value
     * must keep its formatting rather than being escaped into literal tag text.
     */
    @Test
    void componentPlaceholdersKeepTheirFormatting() {
        Component course = MiniMessage.miniMessage().deserialize("<gold>Downtown 9</gold>");

        Component rendered = messages.render("line", "course", course, "player", "Steve");

        assertEquals("on Downtown 9 by Steve", plain(rendered));
        assertTrue(isGold(rendered), "the course name should still be gold, not escaped tag text");
    }

    /** Untrusted values must not be able to smuggle MiniMessage tags into a message. */
    @Test
    void stringPlaceholdersAreEscaped() {
        Component rendered = messages.render("line", "course", "Downtown", "player", "<red>Hax</red>");

        assertEquals("on Downtown by <red>Hax</red>", plain(rendered),
                "a player-supplied tag must survive as literal text");
    }

    @Test
    void unknownKeyRendersAsTheKeySoTyposAreObviousInGame() {
        assertEquals("nope.missing", plain(messages.render("nope.missing")));
    }

    @Test
    void prefixIsAppliedOnlyByPrefixed() {
        assertEquals("on X by Y", plain(messages.render("line", "course", "X", "player", "Y")));
        assertEquals("[PP] on X by Y", plain(messages.prefixed("line", "course", "X", "player", "Y")));
    }

    private static boolean isGold(Component component) {
        return component.color() == NamedTextColor.GOLD
                || component.children().stream().anyMatch(MessagesTest::isGold);
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
