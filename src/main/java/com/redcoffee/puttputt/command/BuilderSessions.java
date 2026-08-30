package com.redcoffee.puttputt.command;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-admin builder state, shared between the command tree and the wand listener so clicking a
 * block and typing a command edit the same selection.
 */
public final class BuilderSessions {

    private final Map<UUID, BuilderSession> sessions = new ConcurrentHashMap<>();

    public BuilderSession of(UUID playerId) {
        return sessions.computeIfAbsent(playerId, id -> new BuilderSession());
    }

    public void forget(UUID playerId) {
        sessions.remove(playerId);
    }
}
