package com.redcoffee.puttputt.party;

import java.util.List;
import java.util.UUID;

/**
 * A snapshot of a party at the moment a round starts. RCPuttPutt never holds a live RCParties
 * object - it takes a copy so a party mutating mid-round cannot corrupt the round's roster.
 */
public record PartyView(UUID partyId, UUID leaderId, List<UUID> memberIds) {

    public PartyView {
        memberIds = List.copyOf(memberIds);
    }

    /** A solo player is a party of one; the rest of the plugin never special-cases them. */
    public static PartyView solo(UUID playerId) {
        return new PartyView(playerId, playerId, List.of(playerId));
    }

    public boolean isLeader(UUID playerId) {
        return leaderId.equals(playerId);
    }
}
