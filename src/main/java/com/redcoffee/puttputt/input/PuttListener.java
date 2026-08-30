package com.redcoffee.puttputt.input;

import com.redcoffee.puttputt.RCPuttPuttPlugin;
import com.redcoffee.puttputt.game.Round;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Turns bow input into strokes and keeps a round's props and players intact.
 *
 * <p>The putter <em>is</em> a bow: face to aim, draw to set power. That is vanilla input everyone
 * already understands, and {@link EntityShootBowEvent#getForce()} hands over the draw as 0..1 with
 * no draw-tracking of our own.
 */
public final class PuttListener implements Listener {

    private final RCPuttPuttPlugin plugin;

    public PuttListener(RCPuttPuttPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player) || !plugin.items().isPutter(event.getBow())) {
            return;
        }
        // The arrow is never wanted: the bow is only an input device here.
        event.setCancelled(true);
        if (event.getProjectile() != null) {
            event.getProjectile().remove();
        }
        if (plugin.rounds().roundOf(player.getUniqueId()).isEmpty()) {
            plugin.messages().sendActionBar(player, "putt.no-round");
            return;
        }
        plugin.rounds().putt(player, event.getForce());
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
     * A disconnect drops the player from the round. Disconnect-grace resumption is explicitly out
     * of scope for v1, so leaving cleanly beats holding a slot nobody can reclaim.
     */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Round round = plugin.rounds().roundOf(event.getPlayer().getUniqueId()).orElse(null);
        if (round != null) {
            plugin.rounds().leave(event.getPlayer());
        }
    }
}
