package com.redcoffee.puttputt.storage;

import java.util.List;
import java.util.UUID;

/**
 * Persistence seam for rounds and scores. SQLite ships in v1; the interface exists so a MySQL
 * implementation can be dropped in without touching game logic (RC-DEV-STD-001).
 */
public interface ScoreDao extends AutoCloseable {

    void initialise() throws StorageException;

    void recordRoundStart(UUID roundId, String courseId, String partySnapshotJson, long startedAt)
            throws StorageException;

    void recordRoundEnd(UUID roundId, long endedAt, String state) throws StorageException;

    /** Writes (or replaces) the live snapshot for a round. */
    void saveSnapshot(UUID roundId, String snapshotJson, long savedAt) throws StorageException;

    /** Drops a round's snapshot once it is finished or abandoned. */
    void clearSnapshot(UUID roundId) throws StorageException;

    /** Rounds still marked IN_PROGRESS with a snapshot saved no longer ago than the cutoff. */
    List<ResumableRound> resumableRounds(long savedAfter) throws StorageException;

    /** Marks every still-open round as archived; used when a resume window has passed. */
    void archiveRound(UUID roundId) throws StorageException;

    void recordScore(UUID roundId, UUID playerId, String playerName, String courseId,
                     int totalStrokes, int parDiff, long completedAt) throws StorageException;

    /** Best (lowest) round per player on a course, ascending. */
    List<LeaderboardEntry> leaderboard(String courseId, int limit) throws StorageException;

    /** A single player's best round on a course, or {@code null} if they have never finished one. */
    LeaderboardEntry personalBest(String courseId, UUID playerId) throws StorageException;

    @Override
    void close();
}
