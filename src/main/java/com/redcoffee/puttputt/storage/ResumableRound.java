package com.redcoffee.puttputt.storage;

import java.util.UUID;

/**
 * A round that was in progress when the server stopped, and the snapshot needed to rebuild it.
 *
 * @param roundId        the round's id, kept so the resumed round writes to the same row
 * @param courseId       course it was played on
 * @param partyJson      encoded member list, matched against who reconnects
 * @param snapshotJson   the serialised {@code RoundSnapshot}
 * @param savedAt        when the snapshot was written, for the resume window
 */
public record ResumableRound(UUID roundId, String courseId, String partyJson, String snapshotJson, long savedAt) {
}
