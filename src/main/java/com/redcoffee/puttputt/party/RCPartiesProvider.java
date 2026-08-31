package com.redcoffee.puttputt.party;

import gg.rc.parties.api.Party;
import gg.rc.parties.api.PartyService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * The RCParties integration, bound against the published {@code rcparties-api} artifact.
 *
 * <p>RCParties is a game-agnostic grouping primitive and knows nothing about golf; everything
 * golf-shaped stays on this side of the seam. The API classes are shaded into RCParties' own jar
 * and shipped at runtime, which is why the Maven dependency is {@code provided} - bundling them a
 * second time would cause a {@code LinkageError}.
 */
public final class RCPartiesProvider implements PartyProvider {

    private final PartyService service;
    private final Logger logger;

    private RCPartiesProvider(PartyService service, Logger logger) {
        this.service = service;
        this.logger = logger;
    }

    /**
     * Resolves the service from the Bukkit services manager. Empty means RCParties is absent or
     * registered nothing - a hard dependency failure the caller should refuse to start on rather
     * than paper over.
     */
    public static Optional<PartyProvider> bind(Logger logger) {
        RegisteredServiceProvider<PartyService> registration =
                Bukkit.getServicesManager().getRegistration(PartyService.class);
        if (registration == null || registration.getProvider() == null) {
            return Optional.empty();
        }
        return Optional.of(new RCPartiesProvider(registration.getProvider(), logger));
    }

    @Override
    public String name() {
        return "RCParties";
    }

    /**
     * The party a player is playing as.
     *
     * <p>A player who is not grouped gets a party of one created for them, which is what gives the
     * round logic a single code path: solo is never special-cased, it is just a party with one
     * member.
     */
    @Override
    public PartyView partyOf(Player player) {
        UUID playerId = player.getUniqueId();
        Optional<Party> existing = service.getParty(playerId);
        if (existing.isPresent()) {
            return snapshot(existing.get());
        }
        try {
            return snapshot(service.createParty(playerId));
        } catch (IllegalStateException ex) {
            // Raced with a join between the lookup and the create; re-read rather than fail.
            return service.getParty(playerId).map(RCPartiesProvider::snapshot)
                    .orElseGet(() -> PartyView.solo(playerId));
        }
    }

    /**
     * Takes the round's activity lock.
     *
     * <p>{@code lockActivity} is void and re-locking with the same key is a no-op, so "is this
     * party already busy" is a separate {@code isLocked} check - which also catches a party held by
     * a different plugin's activity, not just ours.
     */
    @Override
    public boolean acquireActivityLock(UUID partyId, String activityId) {
        try {
            if (service.isLocked(partyId)) {
                return false;
            }
            service.lockActivity(partyId, activityId);
            return true;
        } catch (IllegalArgumentException ex) {
            logger.log(Level.WARNING, "RCParties refused an activity lock for party " + partyId, ex);
            return false;
        }
    }

    @Override
    public void releaseActivityLock(UUID partyId, String activityId) {
        try {
            service.unlockActivity(partyId, activityId);
        } catch (IllegalArgumentException ex) {
            // A stuck lock traps the party until an admin clears it, so this is worth shouting
            // about - but never worth throwing out of round teardown.
            logger.log(Level.WARNING, "Could not release the RCParties activity lock for party "
                    + partyId + "; an admin may need /party admin clearlocks.", ex);
        }
    }

    /** Party objects are immutable snapshots rather than live views, so copying one is safe. */
    private static PartyView snapshot(Party party) {
        return new PartyView(party.id(), party.leader(), List.copyOf(party.members()));
    }
}
