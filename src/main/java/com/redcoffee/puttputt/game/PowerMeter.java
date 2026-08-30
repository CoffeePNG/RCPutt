package com.redcoffee.puttputt.game;

import com.redcoffee.puttputt.config.PowerMeterConfig;
import java.util.UUID;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;

/**
 * One player's charge, rendered as a boss bar (RC-SPEC-PUTTPUTT-001 v2 s5).
 *
 * <p>The bar is shown to the <em>whole party</em>, not just the player charging. In a turn-based
 * game everyone is watching the same putt, and seeing the meter is most of the tension.
 */
public final class PowerMeter {

    private final UUID playerId;
    private final PowerMeterConfig config;
    private final BossBar bar;
    private int heldTicks;
    private boolean shown;

    public PowerMeter(UUID playerId, PowerMeterConfig config, Component title) {
        this.playerId = playerId;
        this.config = config;
        this.bar = BossBar.bossBar(title, 0.0f, BossBar.Color.YELLOW, BossBar.Overlay.NOTCHED_10);
    }

    public UUID playerId() {
        return playerId;
    }

    public BossBar bar() {
        return bar;
    }

    public int heldTicks() {
        return heldTicks;
    }

    /** Advances the charge by a tick and returns the current 0..1 reading. */
    public double tick() {
        heldTicks++;
        double power = power();
        bar.progress((float) Math.clamp(power, 0.0, 1.0));
        // Colour tracks the reading so a glance at the bar reads as weak / medium / full power.
        bar.color(power < 0.34 ? BossBar.Color.GREEN
                : power < 0.75 ? BossBar.Color.YELLOW
                : BossBar.Color.RED);
        return power;
    }

    public double power() {
        return config.powerAt(heldTicks);
    }

    /** Launch speed for the current reading. */
    public double velocity() {
        return config.velocityFor(power());
    }

    public void updateTitle(Component title) {
        bar.name(title);
    }

    public void showTo(Audience audience) {
        audience.showBossBar(bar);
        shown = true;
    }

    public void hideFrom(Audience audience) {
        if (shown) {
            audience.hideBossBar(bar);
        }
    }
}
