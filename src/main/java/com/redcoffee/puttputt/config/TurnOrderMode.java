package com.redcoffee.puttputt.config;

import java.util.Locale;

/** How the order for each hole after the first is derived from running totals. */
public enum TurnOrderMode {
    /**
     * Lowest total strokes putts first - the leader is punished by having to read the green with no
     * information. Keeps rounds tight; the spec's default and the reason the mode exists.
     */
    ASCENDING,
    /** Highest total putts first, rewarding the leader with information instead. */
    DESCENDING;

    public static TurnOrderMode parse(String raw, TurnOrderMode fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return "descending".equalsIgnoreCase(raw.trim()) ? DESCENDING : ASCENDING;
    }
}
