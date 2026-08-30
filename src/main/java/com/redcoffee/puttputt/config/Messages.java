package com.redcoffee.puttputt.config;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.ConfigurationSection;

/**
 * All player-facing strings, as MiniMessage, loaded from config. Nothing user-visible is hardcoded
 * in game logic; a missing key renders as the key itself so a typo is obvious in-game rather than
 * silently blank.
 */
public final class Messages {

    private final Map<String, String> raw = new HashMap<>();
    private String prefix = "";

    public void load(ConfigurationSection section) {
        raw.clear();
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(true)) {
            if (section.isString(key)) {
                raw.put(key, section.getString(key));
            }
        }
        prefix = raw.getOrDefault("prefix", "");
    }

    /** Renders a message with {@code <name>} placeholders, without the prefix. */
    public Component render(String key, Object... placeholders) {
        String template = raw.get(key);
        if (template == null) {
            return Component.text(key);
        }
        return MiniMessage.miniMessage().deserialize(template, resolver(placeholders));
    }

    /** Renders a message with the configured prefix in front of it. */
    public Component prefixed(String key, Object... placeholders) {
        String template = raw.get(key);
        if (template == null) {
            return Component.text(key);
        }
        return MiniMessage.miniMessage().deserialize(prefix + template, resolver(placeholders));
    }

    public void send(Audience audience, String key, Object... placeholders) {
        audience.sendMessage(prefixed(key, placeholders));
    }

    public void sendActionBar(Audience audience, String key, Object... placeholders) {
        audience.sendActionBar(render(key, placeholders));
    }

    public boolean has(String key) {
        return raw.containsKey(key);
    }

    /**
     * Builds placeholder resolvers from alternating name/value pairs. Values are inserted as
     * literal text, never parsed, so a player name can never smuggle MiniMessage tags into a
     * broadcast.
     */
    private static TagResolver resolver(Object... placeholders) {
        if (placeholders.length == 0) {
            return TagResolver.empty();
        }
        if (placeholders.length % 2 != 0) {
            throw new IllegalArgumentException("Placeholders must be name/value pairs, got " + placeholders.length);
        }
        Map<String, String> pairs = new LinkedHashMap<>();
        for (int i = 0; i < placeholders.length; i += 2) {
            pairs.put(String.valueOf(placeholders[i]), String.valueOf(placeholders[i + 1]));
        }
        TagResolver.Builder builder = TagResolver.builder();
        pairs.forEach((name, value) -> builder.resolver(Placeholder.unparsed(name, value)));
        return builder.build();
    }
}
