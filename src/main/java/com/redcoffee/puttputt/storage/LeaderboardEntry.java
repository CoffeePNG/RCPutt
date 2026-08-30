package com.redcoffee.puttputt.storage;

import java.util.UUID;

/** One row of a course leaderboard: a player's best round on that course. */
public record LeaderboardEntry(UUID playerId, String playerName, int totalStrokes, int parDiff, long completedAt) {
}
