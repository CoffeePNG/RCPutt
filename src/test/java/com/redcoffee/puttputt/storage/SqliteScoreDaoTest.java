package com.redcoffee.puttputt.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the real SQLite backend against a temporary database file. */
class SqliteScoreDaoTest {

    @TempDir
    Path directory;

    private SqliteScoreDao dao;

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @BeforeEach
    void setUp() throws StorageException {
        dao = new SqliteScoreDao(new File(directory.toFile(), "scores.db"));
        dao.initialise();
    }

    @AfterEach
    void tearDown() {
        dao.close();
    }

    private void record(UUID player, String name, String course, int strokes, int diff, long at)
            throws StorageException {
        UUID roundId = UUID.randomUUID();
        dao.recordRoundStart(roundId, course, "[]", at);
        dao.recordScore(roundId, player, name, course, strokes, diff, at);
        dao.recordRoundEnd(roundId, at, "COMPLETE");
    }

    /**
     * The leaderboard is "best round per player", so a player with several rounds must appear once,
     * with their lowest score - not once per round they have ever played.
     */
    @Test
    void leaderboardKeepsOnlyEachPlayersBestRound() throws StorageException {
        record(alice, "Alice", "downtown9", 34, 1, 1_000L);
        record(alice, "Alice", "downtown9", 28, -5, 2_000L);
        record(alice, "Alice", "downtown9", 31, -2, 3_000L);
        record(bob, "Bob", "downtown9", 30, -3, 4_000L);

        List<LeaderboardEntry> rows = dao.leaderboard("downtown9", 10);

        assertEquals(2, rows.size(), "one row per player, not one per round");
        assertEquals("Alice", rows.get(0).playerName());
        assertEquals(28, rows.get(0).totalStrokes(), "Alice's best round, not her latest");
        assertEquals(-5, rows.get(0).parDiff());
        assertEquals("Bob", rows.get(1).playerName());
        assertEquals(30, rows.get(1).totalStrokes());
    }

    @Test
    void leaderboardIsScopedToOneCourse() throws StorageException {
        record(alice, "Alice", "downtown9", 28, -5, 1_000L);
        record(bob, "Bob", "seaside18", 12, -9, 2_000L);

        assertEquals(1, dao.leaderboard("downtown9", 10).size());
        assertEquals("Bob", dao.leaderboard("seaside18", 10).getFirst().playerName());
        assertTrue(dao.leaderboard("nosuchcourse", 10).isEmpty());
    }

    @Test
    void leaderboardRespectsItsLimit() throws StorageException {
        for (int i = 0; i < 8; i++) {
            record(UUID.randomUUID(), "P" + i, "downtown9", 20 + i, i, 1_000L + i);
        }

        assertEquals(3, dao.leaderboard("downtown9", 3).size());
    }

    @Test
    void personalBestPicksTheLowestRound() throws StorageException {
        record(alice, "Alice", "downtown9", 34, 1, 1_000L);
        record(alice, "Alice", "downtown9", 29, -4, 2_000L);

        LeaderboardEntry best = dao.personalBest("downtown9", alice);

        assertEquals(29, best.totalStrokes());
        assertNull(dao.personalBest("downtown9", bob), "a player with no rounds has no personal best");
    }

    /** Re-opening an existing database must not fail or wipe what is already there. */
    @Test
    void initialiseIsIdempotentAcrossRestarts() throws StorageException {
        record(alice, "Alice", "downtown9", 28, -5, 1_000L);
        dao.close();

        SqliteScoreDao reopened = new SqliteScoreDao(new File(directory.toFile(), "scores.db"));
        reopened.initialise();
        try {
            assertEquals(1, reopened.leaderboard("downtown9", 10).size());
        } finally {
            reopened.close();
        }
    }
}
