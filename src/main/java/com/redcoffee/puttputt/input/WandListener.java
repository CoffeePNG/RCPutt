package com.redcoffee.puttputt.input;

import com.redcoffee.puttputt.RCPuttPuttPlugin;
import com.redcoffee.puttputt.command.BuilderSession;
import com.redcoffee.puttputt.course.Course;
import com.redcoffee.puttputt.course.Hole;
import com.redcoffee.puttputt.util.Vec3;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

/**
 * The course-builder wand (RC-DEV-STD-001 in-world building rule: no hand-editing geometry).
 *
 * <p>Bindings follow WorldEdit muscle memory for the selection, with the two course-specific marks
 * on sneak:
 *
 * <ul>
 *   <li>left-click a block - corner 1</li>
 *   <li>right-click a block - corner 2</li>
 *   <li><b>sneak</b> + left-click - tee for the selected hole</li>
 *   <li><b>sneak</b> + right-click - cup for the selected hole</li>
 * </ul>
 *
 * <p>Marks land on the <em>top face</em> of the clicked block, which is exactly the plane the
 * physics rolls a ball along. That removes the failure mode of the stand-here commands, where
 * standing on a slab or carpet captured a fractional Y and left the ball sampling the wrong layer.
 */
public final class WandListener implements Listener {

    private final RCPuttPuttPlugin plugin;

    public WandListener(RCPuttPuttPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onWandUse(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Block block = event.getClickedBlock();
        if (block == null || !plugin.items().isWand(event.getItem())) {
            return;
        }
        boolean leftClick = event.getAction() == Action.LEFT_CLICK_BLOCK;
        boolean rightClick = event.getAction() == Action.RIGHT_CLICK_BLOCK;
        if (!leftClick && !rightClick) {
            return;
        }
        // Always consume the click: the wand must never break or place while marking a course.
        event.setCancelled(true);
        if (!player.hasPermission("rcputtputt.admin")) {
            plugin.messages().send(player, "wand.no-permission");
            return;
        }

        BuilderSession session = plugin.builders().of(player.getUniqueId());
        Vec3 mark = BuilderSession.topFaceOf(block.getX(), block.getY(), block.getZ());

        if (player.isSneaking()) {
            markTeeOrCup(player, session, mark, block, leftClick);
            return;
        }
        if (leftClick) {
            session.setCorner1(mark);
        } else {
            session.setCorner2(mark);
        }
        plugin.messages().send(player, "wand.corner",
                "corner", leftClick ? "1" : "2",
                "x", String.valueOf(block.getX()),
                "y", String.valueOf(block.getY()),
                "z", String.valueOf(block.getZ()),
                "ready", String.valueOf(session.hasBothCorners()));
    }

    private void markTeeOrCup(Player player, BuilderSession session, Vec3 mark, Block block, boolean tee) {
        Course course = plugin.courses().course(session.courseId()).orElse(null);
        if (course == null) {
            plugin.messages().send(player, "admin.no-course-selected");
            return;
        }
        if (course.world() != null && !course.world().equals(block.getWorld().getName())) {
            // Marking a hole in a different world than the course lives in is always a mistake.
            plugin.messages().send(player, "wand.wrong-world", "world", course.world());
            return;
        }
        Hole hole = course.holeOrCreate(session.currentHole());
        if (tee) {
            hole.setTee(mark);
        } else {
            hole.setCup(mark);
        }
        plugin.messages().send(player, tee ? "wand.tee" : "wand.cup",
                "hole", String.valueOf(hole.number()),
                "x", String.valueOf(block.getX()),
                "y", String.valueOf(block.getY() + 1),
                "z", String.valueOf(block.getZ()));
    }
}
