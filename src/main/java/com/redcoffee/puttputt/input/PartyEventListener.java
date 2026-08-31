package com.redcoffee.puttputt.input;

import com.redcoffee.puttputt.RCPuttPuttPlugin;
import com.redcoffee.puttputt.game.Round;
import gg.rc.parties.api.event.PartyDisbandEvent;
import gg.rc.parties.api.event.PartyLeaveEvent;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Keeps rounds in step with the party that owns them.
 *
 * <p>RCParties refuses leave, kick and disband while our activity lock is held, so in normal play
 * these never fire mid-round. They still can - an admin force-disbanding, or a shutdown - and a
 * round whose party has evaporated must not be left holding players, balls or a lock.
 */
public final class PartyEventListener implements Listener {

    private final RCPuttPuttPlugin plugin;

    public PartyEventListener(RCPuttPuttPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * The disband event still lists the members the party had, which is the only chance to clean up
     * their per-player round state - by the time it fires, the party itself is gone.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDisband(PartyDisbandEvent event) {
        UUID partyId = event.getParty().id();
        for (Round round : plugin.rounds().roundsOfParty(partyId)) {
            for (UUID memberId : round.players()) {
                Player member = plugin.getServer().getPlayer(memberId);
                if (member != null) {
                    plugin.messages().send(member, "round.party-disbanded",
                            "reason", event.getReason().name().toLowerCase(java.util.Locale.ROOT));
                }
            }
            // Not persisted: a round cut short by a disband is not a comparable result.
            plugin.rounds().closeRound(round, false);
        }
    }

    /** A member leaving mid-round drops out of it; the rest of the party plays on. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onLeave(PartyLeaveEvent event) {
        Player player = plugin.getServer().getPlayer(event.getPlayer());
        if (player != null && plugin.rounds().roundOf(event.getPlayer()).isPresent()) {
            plugin.rounds().leave(player);
        }
    }
}
