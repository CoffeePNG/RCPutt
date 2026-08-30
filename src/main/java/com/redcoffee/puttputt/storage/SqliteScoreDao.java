package com.redcoffee.puttputt.storage;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * SQLite implementation of {@link ScoreDao} (RC-SPEC-PUTTPUTT-001 s8).
 *
 * <p>Holds one connection: SQLite is single-writer anyway, and every call site runs off the main
 * thread through {@code AsyncScoreDao}, so a pool would buy nothing.
 *
 * <p>The schema adds a {@code player_name} column the spec's DDL does not carry. A leaderboard has
 * to render names for players who are offline, and resolving UUIDs at read time would mean a
 * blocking Mojang lookup per row.
 */
public final class SqliteScoreDao implements ScoreDao {

    private final File databaseFile;
    private Connection connection;

    public SqliteScoreDao(File databaseFile) {
        this.databaseFile = databaseFile;
    }

    @Override
    public void initialise() throws StorageException {
        try {
            // Paper loads the driver into the plugin's own classloader via plugin.yml's libraries:
            // block, where the JDBC service loader will not find it on its own.
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException ex) {
            throw new StorageException("The SQLite driver is not on the classpath", ex);
        }
        File parent = databaseFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new StorageException("Could not create data directory " + parent, null);
        }
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getAbsolutePath());
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("PRAGMA foreign_keys = ON");
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS pp_rounds (
                          round_id       TEXT PRIMARY KEY,
                          course_id      TEXT NOT NULL,
                          party_snapshot TEXT NOT NULL DEFAULT '[]',
                          state          TEXT NOT NULL DEFAULT 'IN_PROGRESS',
                          started_at     INTEGER NOT NULL,
                          ended_at       INTEGER
                        )""");
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS pp_round_state (
                          round_id      TEXT PRIMARY KEY,
                          snapshot_json TEXT NOT NULL,
                          saved_at      INTEGER NOT NULL
                        )""");
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS pp_scores (
                          round_id      TEXT NOT NULL,
                          player_uuid   TEXT NOT NULL,
                          player_name   TEXT NOT NULL DEFAULT '',
                          course_id     TEXT NOT NULL,
                          total_strokes INTEGER NOT NULL,
                          par_diff      INTEGER NOT NULL,
                          completed_at  INTEGER NOT NULL,
                          PRIMARY KEY (round_id, player_uuid)
                        )""");
                statement.executeUpdate(
                        "CREATE INDEX IF NOT EXISTS idx_pp_scores_course ON pp_scores(course_id, total_strokes)");
            }
        } catch (SQLException ex) {
            throw new StorageException("Could not open the score database", ex);
        }
    }

    @Override
    public void recordRoundStart(UUID roundId, String courseId, String partySnapshotJson, long startedAt)
            throws StorageException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT OR REPLACE INTO pp_rounds (round_id, course_id, party_snapshot, state, started_at)
                VALUES (?, ?, ?, 'IN_PROGRESS', ?)""")) {
            statement.setString(1, roundId.toString());
            statement.setString(2, courseId);
            statement.setString(3, partySnapshotJson);
            statement.setLong(4, startedAt);
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new StorageException("Could not record round start for " + roundId, ex);
        }
    }

    @Override
    public void recordRoundEnd(UUID roundId, long endedAt, String state) throws StorageException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE pp_rounds SET ended_at = ?, state = ? WHERE round_id = ?")) {
            statement.setLong(1, endedAt);
            statement.setString(2, state);
            statement.setString(3, roundId.toString());
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new StorageException("Could not record round end for " + roundId, ex);
        }
    }

    @Override
    public void saveSnapshot(UUID roundId, String snapshotJson, long savedAt) throws StorageException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR REPLACE INTO pp_round_state (round_id, snapshot_json, saved_at) VALUES (?, ?, ?)")) {
            statement.setString(1, roundId.toString());
            statement.setString(2, snapshotJson);
            statement.setLong(3, savedAt);
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new StorageException("Could not save a snapshot for " + roundId, ex);
        }
    }

    @Override
    public void clearSnapshot(UUID roundId) throws StorageException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM pp_round_state WHERE round_id = ?")) {
            statement.setString(1, roundId.toString());
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new StorageException("Could not clear the snapshot for " + roundId, ex);
        }
    }

    @Override
    public List<ResumableRound> resumableRounds(long savedAfter) throws StorageException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT r.round_id, r.course_id, r.party_snapshot, s.snapshot_json, s.saved_at
                FROM pp_rounds r
                JOIN pp_round_state s ON s.round_id = r.round_id
                WHERE r.state = 'IN_PROGRESS' AND s.saved_at >= ?
                ORDER BY s.saved_at DESC""")) {
            statement.setLong(1, savedAfter);
            try (ResultSet rows = statement.executeQuery()) {
                List<ResumableRound> out = new ArrayList<>();
                while (rows.next()) {
                    out.add(new ResumableRound(
                            UUID.fromString(rows.getString("round_id")),
                            rows.getString("course_id"),
                            rows.getString("party_snapshot"),
                            rows.getString("snapshot_json"),
                            rows.getLong("saved_at")));
                }
                return out;
            }
        } catch (SQLException ex) {
            throw new StorageException("Could not read resumable rounds", ex);
        }
    }

    @Override
    public void archiveRound(UUID roundId) throws StorageException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE pp_rounds SET state = 'ARCHIVED' WHERE round_id = ?")) {
            statement.setString(1, roundId.toString());
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new StorageException("Could not archive round " + roundId, ex);
        }
    }

    @Override
    public void recordScore(UUID roundId, UUID playerId, String playerName, String courseId,
                            int totalStrokes, int parDiff, long completedAt) throws StorageException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT OR REPLACE INTO pp_scores
                  (round_id, player_uuid, player_name, course_id, total_strokes, par_diff, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)""")) {
            statement.setString(1, roundId.toString());
            statement.setString(2, playerId.toString());
            statement.setString(3, playerName);
            statement.setString(4, courseId);
            statement.setInt(5, totalStrokes);
            statement.setInt(6, parDiff);
            statement.setLong(7, completedAt);
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new StorageException("Could not record score for " + playerId, ex);
        }
    }

    @Override
    public List<LeaderboardEntry> leaderboard(String courseId, int limit) throws StorageException {
        // One row per player: their best round, ties broken by who got there first.
        //
        // A window function rather than a grouped subquery on purpose. Matching on
        // (player_uuid, MIN(total_strokes), MIN(completed_at)) looks equivalent but is not: the two
        // aggregates are computed independently, so a player whose best round was not also their
        // earliest matches no row and drops off the leaderboard entirely. ROW_NUMBER ranks whole
        // rows, so the score, par diff and name all come from the same round.
        String sql = """
                SELECT player_uuid, player_name, total_strokes, par_diff, completed_at
                FROM (
                  SELECT player_uuid, player_name, total_strokes, par_diff, completed_at,
                         ROW_NUMBER() OVER (
                           PARTITION BY player_uuid
                           ORDER BY total_strokes ASC, completed_at ASC) AS rank_in_player
                  FROM pp_scores
                  WHERE course_id = ?
                )
                WHERE rank_in_player = 1
                ORDER BY total_strokes ASC, completed_at ASC
                LIMIT ?""";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, courseId);
            statement.setInt(2, Math.max(1, limit));
            try (ResultSet rows = statement.executeQuery()) {
                List<LeaderboardEntry> entries = new ArrayList<>();
                while (rows.next()) {
                    entries.add(readEntry(rows));
                }
                return entries;
            }
        } catch (SQLException ex) {
            throw new StorageException("Could not read the leaderboard for " + courseId, ex);
        }
    }

    @Override
    public LeaderboardEntry personalBest(String courseId, UUID playerId) throws StorageException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT player_uuid, player_name, total_strokes, par_diff, completed_at
                FROM pp_scores
                WHERE course_id = ? AND player_uuid = ?
                ORDER BY total_strokes ASC, completed_at ASC
                LIMIT 1""")) {
            statement.setString(1, courseId);
            statement.setString(2, playerId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? readEntry(rows) : null;
            }
        } catch (SQLException ex) {
            throw new StorageException("Could not read a personal best for " + playerId, ex);
        }
    }

    private static LeaderboardEntry readEntry(ResultSet rows) throws SQLException {
        return new LeaderboardEntry(
                UUID.fromString(rows.getString("player_uuid")),
                rows.getString("player_name"),
                rows.getInt("total_strokes"),
                rows.getInt("par_diff"),
                rows.getLong("completed_at"));
    }

    @Override
    public void close() {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Closing on shutdown; nothing useful left to do with the failure.
        }
    }
}
