package com.redcoffee.puttputt.course;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Draws a traced region in the world so a builder can see what the fill actually reached.
 *
 * <p>The trace either matches the hole you built or it does not, and the difference is invisible
 * from the ground: a one-block gap in a slab wall turns a tidy course into a fill that walks off
 * into the terrain, and the only symptom is a number in a chat message. Painting the cells shows
 * you the shape, and more usefully shows you where it escaped.
 *
 * <p>Particles are sent to one player rather than the world, so somebody checking their bounds does
 * not spray green dust over everyone else's round.
 */
public final class FillVisualizer {

    /** Green: ordinary fairway, at the height the trace started from. */
    private static final Color FAIRWAY = Color.fromRGB(0x55, 0xDD, 0x55);
    /** Amber: a cell the trace stepped down to, so terracing is visible at a glance. */
    private static final Color LOWER = Color.fromRGB(0xFF, 0xB3, 0x3C);
    /** Blue: the perimeter, where the fill was stopped by a wall. */
    private static final Color EDGE = Color.fromRGB(0x66, 0xC8, 0xFF);
    /** Red: drawn instead of the perimeter when the fill leaked, since there is no clean edge. */
    private static final Color LEAK = Color.fromRGB(0xFF, 0x55, 0x55);

    /** Above this many cells the render is sampled, so a big course cannot stall a client. */
    private static final int MAX_PARTICLES = 4_000;

    private static final long PULSE_TICKS = 10L;

    private FillVisualizer() {
    }

    /**
     * Paints {@code result} for {@code player} for a few seconds.
     *
     * @return the task drawing it, so a later call can cancel an earlier one
     */
    public static BukkitTask show(Plugin plugin, Player player, CourseRegion.Result result,
                                  int startY, int seconds) {
        List<long[]> cells = new ArrayList<>(result.cells());
        Set<String> filled = new HashSet<>();
        for (long[] cell : cells) {
            filled.add(cell[0] + ":" + cell[2]);
        }
        // Sampling keeps the shape readable: every nth cell still traces the same outline.
        int stride = Math.max(1, cells.size() / MAX_PARTICLES);
        boolean leaked = result.exhausted();

        long pulses = Math.max(1, seconds * 20L / PULSE_TICKS);

        return new org.bukkit.scheduler.BukkitRunnable() {
            private long remaining = pulses;

            @Override
            public void run() {
                if (remaining-- <= 0 || !player.isOnline()) {
                    cancel();
                    return;
                }
                for (int i = 0; i < cells.size(); i += stride) {
                    long[] cell = cells.get(i);
                    int x = (int) cell[0], y = (int) cell[1], z = (int) cell[2];
                    Color color;
                    if (isPerimeter(filled, x, z)) {
                        color = leaked ? LEAK : EDGE;
                    } else if (y != startY) {
                        color = LOWER;
                    } else {
                        color = FAIRWAY;
                    }
                    player.spawnParticle(Particle.DUST, x + 0.5, y + 0.15, z + 0.5, 1,
                            0.0, 0.0, 0.0, 0.0, new Particle.DustOptions(color, 1.0f));
                }
            }
        }.runTaskTimer(plugin, 0L, PULSE_TICKS);
    }

    /**
     * A cell with a missing cardinal neighbour is on the edge of the region.
     *
     * <p>Cardinal only, to match the fill: the outline drawn has to be the outline the fill
     * actually saw, or the picture would lie about where it stopped.
     */
    static boolean isPerimeter(Set<String> filled, int x, int z) {
        return !filled.contains((x + 1) + ":" + z)
                || !filled.contains((x - 1) + ":" + z)
                || !filled.contains(x + ":" + (z + 1))
                || !filled.contains(x + ":" + (z - 1));
    }
}
