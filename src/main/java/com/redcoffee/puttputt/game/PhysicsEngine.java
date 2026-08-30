package com.redcoffee.puttputt.game;

import com.redcoffee.puttputt.config.PhysicsConfig;
import com.redcoffee.puttputt.surface.ResetMode;
import com.redcoffee.puttputt.surface.Surface;
import com.redcoffee.puttputt.util.Vec3;

/**
 * The rolling-ball integrator (RC-SPEC-PUTTPUTT-001 s3.1). Stateless and Bukkit-free: everything it
 * needs arrives as arguments, so a tick can be replayed in a test against a hand-built grid.
 *
 * <p>Two coordinate conventions matter here. A ball's {@code y} is the <em>top face</em> of the
 * green it rolls on, so the block it rolls over sits at {@code floor(y - 0.5)} while walls it can
 * hit stand at {@code floor(y)}. Sampling ground and walls at different heights is what lets a
 * water block under the ball be a hazard while a stone block beside it is a bounce.
 */
public final class PhysicsEngine {

    private final PhysicsConfig config;

    public PhysicsEngine(PhysicsConfig config) {
        this.config = config;
    }

    public PhysicsConfig config() {
        return config;
    }

    /**
     * Advances one ball by a single tick.
     *
     * @param ball    the ball to move; mutated in place
     * @param sampler surface lookup for the hole being played
     * @param cup     centre of the cup, used for the sink gate
     */
    public StepOutcome step(BallState ball, SurfaceSampler sampler, Vec3 cup) {
        if (ball.atRest()) {
            return StepOutcome.of(StepResult.CAME_TO_REST, groundSurface(ball.position(), sampler));
        }

        // 1-2. Integrate, then resolve walls one axis at a time. Axis separation is what makes a
        // corner bounce come out right: a ball that clips a wall on X only loses its X component.
        Vec3 position = ball.position();
        Vec3 velocity = ball.velocity();
        int wallY = position.blockY();

        double nextX = position.x() + velocity.x();
        Surface alongX = sampler.at((int) Math.floor(nextX), wallY, position.blockZ());
        if (alongX.isWall()) {
            velocity = velocity.withX(-velocity.x() * alongX.restitution());
        } else {
            position = position.withX(nextX);
        }

        double nextZ = position.z() + velocity.z();
        Surface alongZ = sampler.at(position.blockX(), wallY, (int) Math.floor(nextZ));
        if (alongZ.isWall()) {
            velocity = velocity.withZ(-velocity.z() * alongZ.restitution());
        } else {
            position = position.withZ(nextZ);
        }

        // 3. Sample the surface the ball is now rolling over.
        Surface surface = groundSurface(position, sampler);

        // 4. Impulse pads (booster / push blocks) add velocity before friction eats into it.
        if (surface.hasImpulse()) {
            velocity = velocity.add(surface.impulse().asVelocityDelta());
        }

        // 5-6. Friction, then the tunneling guard. Clamping after the impulse is deliberate: a
        // chain of boosters must not be able to accelerate a ball past one block per tick.
        velocity = velocity.multiply(surface.friction()).clampLength(config.maxVelocity());

        ball.setPosition(position);
        ball.setVelocity(velocity);

        // 9. Hazards win over resting and sinking - a ball that rolls into water is gone whatever
        // its speed, and the reset spot must be established before any rest bookkeeping runs.
        if (surface.isHazard()) {
            Vec3 target = surface.reset() == ResetMode.TEE ? ball.tee() : ball.lastRest();
            ball.placeAt(target);
            return new StepOutcome(StepResult.HAZARD, surface, Math.max(0, surface.penalty()));
        }

        // 10. Sink gate. Being over the cup is not enough: too fast and the ball lips out and rolls
        // on, which is what makes a sunk putt feel earned rather than magnetic.
        double speed = velocity.length();
        if (position.horizontalDistance(cup) <= config.sinkRadius()) {
            if (speed <= config.maxSinkSpeed()) {
                ball.placeAt(cup);
                return StepOutcome.of(StepResult.SUNK, surface);
            }
            return StepOutcome.of(StepResult.MOVING, surface);
        }

        // 8. Rest check.
        if (speed < config.restEpsilon()) {
            ball.comeToRest();
            return StepOutcome.of(StepResult.CAME_TO_REST, surface);
        }
        return StepOutcome.of(StepResult.MOVING, surface);
    }

    /**
     * Converts an aim direction and a bow-draw force into a launch velocity, clamped to the
     * tunneling guard. Force arrives from the bow as 0..1.
     */
    public Vec3 puttVelocity(Vec3 aimDirection, double force) {
        double clamped = Math.clamp(force, 0.0, 1.0);
        Vec3 flat = new Vec3(aimDirection.x(), 0.0, aimDirection.z()).normalize();
        return flat.multiply(clamped * config.maxPuttPower()).clampLength(config.maxVelocity());
    }

    private Surface groundSurface(Vec3 position, SurfaceSampler sampler) {
        return sampler.at(position.blockX(), (int) Math.floor(position.y() - 0.5), position.blockZ());
    }
}
