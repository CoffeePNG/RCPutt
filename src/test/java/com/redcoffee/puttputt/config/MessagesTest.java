package com.redcoffee.puttputt.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.republicraft.rcui.api.MessageBundle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Messages now delegates to RCUI, so these test the part RCUI cannot know about: which placeholder
 * values are trusted to render and which are escaped.
 */
class MessagesTest {

    private static final String PREFIX = "[PP] ";

    /** Stands in for RCUI's bundle: same contract, MiniMessage rendering, prefix on message(). */
    private static final class FakeBundle implements MessageBundle {
        private final Map<String, String> catalog = Map.of("line", "on <course> by <player>");

        @Override
        public String namespace() {
            return "rcputtputt";
        }

        @Override
        public Component component(String key, TagResolver... arguments) {
            String template = catalog.get(key);
            return template == null
                    ? Component.text(key)
                    : MiniMessage.miniMessage().deserialize(template, arguments);
        }

        @Override
        public Component message(String key, TagResolver... arguments) {
            return Component.text(PREFIX).append(component(key, arguments));
        }

        @Override
        public void broadcast(String key, TagResolver... arguments) {
            throw new UnsupportedOperationException();
        }
    }

    private final Messages messages = new Messages();

    @BeforeEach
    void setUp() {
        messages.bind(new FakeBundle());
    }

    /** A course display name is authored config and is documented as MiniMessage: it must render. */
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

    /** RCUI's split: component() is raw for action bars and GUI text, message() carries the prefix. */
    @Test
    void onlyPrefixedLookupsCarryTheSharedPrefix() {
        assertEquals("on X by Y", plain(messages.render("line", "course", "X", "player", "Y")));
        assertEquals(PREFIX + "on X by Y",
                plain(messages.prefixed("line", "course", "X", "player", "Y")));
    }

    @Test
    void unknownKeyRendersAsTheKeySoTyposAreObviousInGame() {
        assertEquals("nope.missing", plain(messages.render("nope.missing")));
    }

    /** Before RCUI binds, lookups must degrade to the key rather than throwing on a null bundle. */
    @Test
    void anUnboundMessagesRendersTheKey() {
        Messages unbound = new Messages();

        assertEquals("some.key", plain(unbound.render("some.key")));
        assertEquals("some.key", plain(unbound.prefixed("some.key")));
    }

    private static boolean isGold(Component component) {
        return component.color() == NamedTextColor.GOLD
                || component.children().stream().anyMatch(MessagesTest::isGold);
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
