package com.redcoffee.puttputt.event;

import java.util.Map;
import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired once a round is closed and torn down, carrying its results.
 *
 * <p>This is the seam a future wager layer settles bets against - no betting logic lives in
 * RCPuttPutt itself. Players whose scorecards were unfinished are absent from both maps: an
 * abandoned round is not a result anyone should be paid on.
 */
public final class RCPuttPuttRoundCompleteEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID roundId;
    private final String courseId;
    private final UUID partyId;
    private final Map<UUID, Integer> totalStrokes;
    private final Map<UUID, Integer> parDiffs;

    public RCPuttPuttRoundCompleteEvent(UUID roundId, String courseId, UUID partyId,
                                        Map<UUID, Integer> totalStrokes, Map<UUID, Integer> parDiffs) {
        this.roundId = roundId;
        this.courseId = courseId;
        this.partyId = partyId;
        this.totalStrokes = Map.copyOf(totalStrokes);
        this.parDiffs = Map.copyOf(parDiffs);
    }

    public UUID roundId() {
        return roundId;
    }

    public String courseId() {
        return courseId;
    }

    public UUID partyId() {
        return partyId;
    }

    public Map<UUID, Integer> totalStrokes() {
        return totalStrokes;
    }

    public Map<UUID, Integer> parDiffs() {
        return parDiffs;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
