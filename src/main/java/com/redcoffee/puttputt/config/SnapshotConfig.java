package com.redcoffee.puttputt.config;

/**
 * Crash-resilience settings (RC-SPEC-PUTTPUTT-001 v2 s9).
 *
 * @param intervalSeconds     how often a live round is written to SQLite
 * @param resumeWindowMinutes how long after a crash a round may still be resumed
 */
public record SnapshotConfig(int intervalSeconds, int resumeWindowMinutes) {

    public static final SnapshotConfig DEFAULTS = new SnapshotConfig(15, 10);

    public SnapshotConfig {
        if (intervalSeconds <= 0) {
            throw new IllegalArgumentException("snapshot interval must be positive, got " + intervalSeconds);
        }
        if (resumeWindowMinutes < 0) {
            throw new IllegalArgumentException("resume window cannot be negative, got " + resumeWindowMinutes);
        }
    }

    public long intervalTicks() {
        return intervalSeconds * 20L;
    }

    public long resumeWindowMillis() {
        return resumeWindowMinutes * 60_000L;
    }
}
