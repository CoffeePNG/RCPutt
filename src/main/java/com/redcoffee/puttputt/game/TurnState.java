package com.redcoffee.puttputt.game;

import com.redcoffee.puttputt.RCPuttPuttPlugin;
import java.util.UUID;
import org.bukkit.entity.Player;

/** Live turn bookkeeping for one round: who is up, their shot clock, and their power meter. */
public final class TurnState {

    /** Segments in the action-bar clock. */
    private static final int CLOCK_BAR_WIDTH = 20;

    private UUID currentPlayer;
    private long clockTicksLeft;
    private long totalClockTicks;
    private PowerMeter meter;
    private boolean struck;

    public UUID currentPlayer() {
        return currentPlayer;
    }

    public PowerMeter meter() {
        return meter;
    }

    public boolean struck() {
        return struck;
    }

    public void markStruck() {
        this.struck = true;
    }

    public void beginTurn(UUID playerId, long clockTicks) {
        this.currentPlayer = playerId;
        this.clockTicksLeft = clockTicks;
        this.totalClockTicks = clockTicks;
        this.struck = false;
    }

    public void setClockTicksLeft(long ticks) {
        this.clockTicksLeft = ticks;
    }

    public long clockTicksLeft() {
        return clockTicksLeft;
    }

    public int secondsLeft() {
        return (int) Math.ceil(clockTicksLeft / 20.0);
    }

    /** Counts the shot clock down a tick. Returns true when it has just expired. */
    public boolean tickClock() {
        if (clockTicksLeft <= 0) {
            return true;
        }
        clockTicksLeft--;
        return clockTicksLeft <= 0;
    }

    public double secondsLeftExact() {
        return Math.max(0, clockTicksLeft) / 20.0;
    }

    /** Fraction of the shot clock still remaining, 0..1. */
    public double clockFraction() {
        return totalClockTicks <= 0 ? 0 : Math.clamp((double) clockTicksLeft / totalClockTicks, 0.0, 1.0);
    }

    /**
     * The clock as a drained bar for the action bar. Uses block characters rather than a colour
     * alone so it still reads for a colour-blind player.
     */
    public String clockBar() {
        int filled = (int) Math.round(clockFraction() * CLOCK_BAR_WIDTH);
        return "|".repeat(filled) + "·".repeat(Math.max(0, CLOCK_BAR_WIDTH - filled));
    }

    /** Starts a charge if one is not already running, showing the bar to the whole party. */
    public void startChargeIfNeeded(RCPuttPuttPlugin plugin, Round round, UUID playerId) {
        if (meter != null) {
            return;
        }
        meter = new PowerMeter(playerId, plugin.config().powerMeter(),
                plugin.messages().render("power.title", "player", plugin.nameOf(playerId), "percent", "0"));
        for (UUID viewerId : round.players()) {
            Player viewer = plugin.getServer().getPlayer(viewerId);
            if (viewer != null) {
                meter.showTo(viewer);
            }
        }
    }

    /** Tears the bar down for everyone who could see it. */
    public void clearCharge(RCPuttPuttPlugin plugin, Round round) {
        if (meter == null) {
            return;
        }
        for (UUID viewerId : round.players()) {
            Player viewer = plugin.getServer().getPlayer(viewerId);
            if (viewer != null) {
                meter.hideFrom(viewer);
            }
        }
        meter = null;
    }
}
