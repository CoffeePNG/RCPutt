package com.redcoffee.puttputt.input;

import com.redcoffee.puttputt.RCPuttPuttPlugin;
import com.redcoffee.puttputt.game.Round;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Keeps a round's props and players intact, and gates putter input to whoever is on the clock.
 *
 * <p>The charge itself is <em>not</em> driven from events. The putter is a shovel carrying a
 * {@code consumable} component purely so right-click-hold registers as an in-progress use, and
 * {@code RoundManager} polls {@link Player#isHandRaised()} each tick. Events only reject input from
 * players it is not the turn of, and stop the consume from ever completing.
 */
public final class PuttListener implements Listener {

    private final RCPuttPuttPlugin plugin;

    public PuttListener(RCPuttPuttPlugin plugin) {
        this.plugin = plugin;
    }

    /** Tells a player why nothing happened when they charge out of turn. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        if (!plugin.items().isPutter(event.getItem())) {
            return;
        }
        Round round = plugin.rounds().roundOf(player.getUniqueId()).orElse(null);
        if (round == null) {
            plugin.messages().sendActionBar(player, "putt.no-round");
            event.setCancelled(true);
            return;
        }
        boolean theirTurn = plugin.rounds().turnState(round.roundId())
                .map(state -> player.getUniqueId().equals(state.currentPlayer()))
                .orElse(false);
        if (!theirTurn) {
            plugin.messages().sendActionBar(player, "turn.not-yours");
            event.setCancelled(true);
        }
    }

    /**
     * The putter's consumable component exists only to make hold-to-charge detectable. If a hold
     * ever ran long enough to finish, this stops the item being eaten.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (plugin.items().isPutter(event.getItem())) {
            event.setCancelled(true);
        }
    }

    /** A putter is round equipment, not loot - it should not end up on the floor. */
    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (plugin.items().isPutter(event.getItemDrop().getItemStack())
                && plugin.rounds().roundOf(event.getPlayer().getUniqueId()).isPresent()) {
            event.setCancelled(true);
            plugin.messages().sendActionBar(event.getPlayer(), "putt.cannot-drop");
        }
    }

    /** Dying mid-putt would strand a ball and a scorecard; players on a course take no damage. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player
                && plugin.rounds().roundOf(player.getUniqueId()).isPresent()) {
            event.setCancelled(true);
        }
    }

    /**
     * A disconnect drops the player from the round. Their turn is handed on immediately so the
     * rest of the party is never left waiting on someone who has gone.
     */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (plugin.rounds().roundOf(event.getPlayer().getUniqueId()).isPresent()) {
            plugin.rounds().leave(event.getPlayer());
        }
    }
}
