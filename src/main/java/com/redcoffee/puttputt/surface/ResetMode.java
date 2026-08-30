package com.redcoffee.puttputt.surface;


/** Where a hazard sends the ball back to. */
public enum ResetMode {
    LAST_REST,
    TEE;

    public static ResetMode parse(String raw, ResetMode fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return "tee".equalsIgnoreCase(raw.trim()) ? TEE : LAST_REST;
    }
}
