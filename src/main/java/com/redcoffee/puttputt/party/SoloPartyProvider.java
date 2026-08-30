package com.redcoffee.puttputt.party;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;

/**
 * Fallback provider: every player is a party of one and locks are tracked in memory.
 *
 * <p>This is what runs when the RCParties bridge cannot bind (an API change, say). Degrading to
 * solo play keeps the course open instead of failing every {@code /puttputt start} on a server
 * where the dependency merely drifted.
 */
public final class SoloPartyProvider implements PartyProvider {

    private final Set<UUID> locked = ConcurrentHashMap.newKeySet();

    @Override
    public String name() {
        return "solo (RCParties unavailable)";
    }

    @Override
    public PartyView partyOf(Player player) {
        return PartyView.solo(player.getUniqueId());
    }

    @Override
    public boolean acquireActivityLock(UUID partyId, String activityId) {
        return locked.add(partyId);
    }

    @Override
    public void releaseActivityLock(UUID partyId, String activityId) {
        locked.remove(partyId);
    }
}
