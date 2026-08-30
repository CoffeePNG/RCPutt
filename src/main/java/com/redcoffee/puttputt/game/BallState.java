package com.redcoffee.puttputt.game;

import com.redcoffee.puttputt.util.Vec3;

/**
 * Mutable physics state for a single ball. Deliberately free of Bukkit types - the entity that
 * renders this state is held separately by {@code Ball}, so the integrator can be unit-tested.
 */
public final class BallState {

    /** Ticks a ball ignores pads for after using one. */
    private static final int TELEPORT_COOLDOWN_TICKS = 20;

    private Vec3 position;
    private Vec3 velocity = Vec3.ZERO;
    private Vec3 lastRest;
    private Vec3 tee;
    private boolean atRest = true;
    private int teleportCooldown;

    public BallState(Vec3 tee) {
        this.tee = tee;
        this.position = tee;
        this.lastRest = tee;
    }

    public Vec3 position() {
        return position;
    }

    public void setPosition(Vec3 position) {
        this.position = position;
    }

    public Vec3 velocity() {
        return velocity;
    }

    public void setVelocity(Vec3 velocity) {
        this.velocity = velocity;
    }

    public Vec3 lastRest() {
        return lastRest;
    }

    public Vec3 tee() {
        return tee;
    }

    public boolean atRest() {
        return atRest;
    }

    /** Launches the ball. Ignored while it is still rolling - one stroke at a time. */
    public boolean strike(Vec3 impulse) {
        if (!atRest) {
            return false;
        }
        this.velocity = impulse;
        this.atRest = false;
        return true;
    }

    /** True while the ball is allowed to take a teleport pad. */
    public boolean canTeleport() {
        return teleportCooldown <= 0;
    }

    public void tickTeleportCooldown() {
        if (teleportCooldown > 0) {
            teleportCooldown--;
        }
    }

    /**
     * Moves the ball through a pad. The cooldown is generous enough to clear the destination pad
     * even at low speed, so two pads pointing at each other cannot trap a ball.
     */
    public void teleportTo(Vec3 destination, Vec3 newVelocity) {
        this.position = destination;
        this.velocity = newVelocity;
        this.teleportCooldown = TELEPORT_COOLDOWN_TICKS;
        if (newVelocity.lengthSquared() == 0.0) {
            this.atRest = true;
            this.lastRest = destination;
        }
    }

    /** Sets a resting ball moving because something hit it. Unlike a stroke, this is not a putt. */
    public void wake(Vec3 velocity) {
        this.velocity = velocity;
        this.atRest = false;
    }

    /** Parks the ball where it lies and makes that spot the hazard-reset point. */
    public void comeToRest() {
        this.velocity = Vec3.ZERO;
        this.atRest = true;
        this.lastRest = position;
    }

    /** Puts the ball back on a known-good spot (hazard reset, or a fresh hole). */
    public void placeAt(Vec3 target) {
        this.position = target;
        this.velocity = Vec3.ZERO;
        this.atRest = true;
        this.lastRest = target;
    }

    /** Re-tees the ball for the next hole. */
    public void resetForHole(Vec3 newTee) {
        this.tee = newTee;
        placeAt(newTee);
    }
}
