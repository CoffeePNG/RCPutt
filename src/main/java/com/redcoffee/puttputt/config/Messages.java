package com.redcoffee.puttputt.config;

import java.util.LinkedHashMap;
import java.util.Map;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.republicraft.rcui.api.MessageBundle;

/**
 * All player-facing strings, delegated to RCUI.
 *
 * <p>RCUI owns the catalog and, crucially, the <em>prefix</em>: one top-level setting shared by
 * every message this bundle sends, which is what keeps RCPuttPutt's chat styling in step with the
 * other RC plugins instead of drifting on its own. {@code message}/{@code send} apply the prefix;
 * {@code component} deliberately does not, so raw lookups stay usable for action bars, GUI text and
 * composition.
 *
 * <p>This class stays as the seam rather than calling the bundle from ~60 sites directly, because
 * it enforces one rule RCUI cannot know about: a {@link Component} value is trusted and inserted
 * as-is, while anything else is inserted as literal text. That is the security boundary - course
 * display names are authored config and are meant to render, but a player name must never be able
 * to smuggle MiniMessage tags into a broadcast.
 */
public final class Messages {

    private MessageBundle bundle;

    /** Binds the RCUI bundle. Until this is called every lookup renders as its key. */
    public void bind(MessageBundle bundle) {
        this.bundle = bundle;
    }

    public boolean isBound() {
        return bundle != null;
    }

    /** Renders a message without the prefix - action bars, GUI text, composition. */
    public Component render(String key, Object... placeholders) {
        if (bundle == null) {
            return Component.text(key);
        }
        return bundle.component(key, resolvers(placeholders));
    }

    /** Renders a message with RCUI's shared prefix in front of it. */
    public Component prefixed(String key, Object... placeholders) {
        if (bundle == null) {
            return Component.text(key);
        }
        return bundle.message(key, resolvers(placeholders));
    }

    public void send(Audience audience, String key, Object... placeholders) {
        audience.sendMessage(prefixed(key, placeholders));
    }

    /** Action bars are unprefixed: there is no room, and the context is already obvious. */
    public void sendActionBar(Audience audience, String key, Object... placeholders) {
        audience.sendActionBar(render(key, placeholders));
    }

    /**
     * Builds RCUI's resolvers from alternating name/value pairs, applying the trust rule above.
     */
    private static TagResolver[] resolvers(Object... placeholders) {
        if (placeholders.length == 0) {
            return new TagResolver[0];
        }
        if (placeholders.length % 2 != 0) {
            throw new IllegalArgumentException("Placeholders must be name/value pairs, got " + placeholders.length);
        }
        Map<String, Object> pairs = new LinkedHashMap<>();
        for (int i = 0; i < placeholders.length; i += 2) {
            pairs.put(String.valueOf(placeholders[i]), placeholders[i + 1]);
        }
        return pairs.entrySet().stream()
                .map(entry -> entry.getValue() instanceof Component component
                        ? Placeholder.component(entry.getKey(), component)
                        : Placeholder.unparsed(entry.getKey(), String.valueOf(entry.getValue())))
                .toArray(TagResolver[]::new);
    }
}
