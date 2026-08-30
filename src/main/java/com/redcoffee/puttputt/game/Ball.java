package com.redcoffee.puttputt.game;

import com.redcoffee.puttputt.util.Vec3;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ItemDisplay;

/**
 * Binds a {@link BallState} to the Item Display that renders it.
 *
 * <p>Item Displays are the right primitive here: no AI to fight, no gravity, full transform
 * control, and client-side interpolation between ticks. The server moves the display once per tick
 * and sets {@code interpolation_duration = 1} so the client lerps the gap, which is what makes a
 * 20 tps roll look smooth.
 */
public final class Ball {

    private final BallState state;
    private final ItemDisplay display;
    private final World world;

    public Ball(BallState state, ItemDisplay display, World world) {
        this.state = state;
        this.display = display;
        this.world = world;
        syncDisplay();
    }

    public BallState state() {
        return state;
    }

    public ItemDisplay display() {
        return display;
    }

    public World world() {
        return world;
    }

    public Location location() {
        return toLocation(state.position());
    }

    public Location toLocation(Vec3 vec) {
        return new Location(world, vec.x(), vec.y(), vec.z());
    }

    /** Pushes the current physics position onto the display, letting the client lerp the gap. */
    public void syncDisplay() {
        if (display.isValid()) {
            display.setInterpolationDelay(0);
            display.setInterpolationDuration(1);
            display.setTeleportDuration(1);
            display.teleport(location());
        }
    }

    /** Places the ball somewhere new without interpolating - a reset should read as a jump, not a slide. */
    public void snapTo(Vec3 target) {
        state.placeAt(target);
        if (display.isValid()) {
            display.setTeleportDuration(0);
            display.teleport(toLocation(target));
            display.setTeleportDuration(1);
        }
    }

    public void remove() {
        if (display.isValid()) {
            display.remove();
        }
    }
}
