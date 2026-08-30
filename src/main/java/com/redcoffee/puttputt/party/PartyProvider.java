package com.redcoffee.puttputt.party;

import java.util.Optional;
import java.util.UUID;
import org.bukkit.entity.Player;

/**
 * The RCParties seam. Everything the round logic needs from parties goes through here so the
 * integration can be swapped, stubbed in tests, or degraded to solo play if RCParties changes
 * shape under us.
 */
public interface PartyProvider {

    /** Human-readable name for the backing implementation; shown in {@code /puttputt admin} output. */
    String name();

    /** The party the player belongs to, or a solo party if they are not in one. */
    PartyView partyOf(Player player);

    /**
     * Marks the party as busy so members cannot leave or disband out from under a round.
     *
     * @return {@code true} if the lock was taken; {@code false} if the party is already in an activity
     */
    boolean acquireActivityLock(UUID partyId, String activityId);

    void releaseActivityLock(UUID partyId, String activityId);

    /** The reason a lock could not be taken, when the provider can supply one. */
    default Optional<String> lockFailureReason(UUID partyId) {
        return Optional.empty();
    }
}
