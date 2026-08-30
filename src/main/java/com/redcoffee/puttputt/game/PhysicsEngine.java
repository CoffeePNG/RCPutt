package com.redcoffee.puttputt.game;

import com.redcoffee.puttputt.config.BallCollisionConfig;
import com.redcoffee.puttputt.config.PhysicsConfig;
import com.redcoffee.puttputt.surface.ResetMode;
import com.redcoffee.puttputt.surface.Surface;
import com.redcoffee.puttputt.util.Vec3;
import java.util.List;

/**
 * The rolling-ball integrator (RC-SPEC-PUTTPUTT-001 v2 s4.1). Stateless and Bukkit-free: everything
 * it needs arrives as arguments, so a tick can be replayed in a test against a hand-built grid.
 *
 * <p>Two coordinate conventions matter here. A ball's {@code y} is the <em>top face</em> of the
 * green it rolls on, so the block it rolls over sits at {@code floor(y - 0.5)} while walls it can
 * hit stand at {@code floor(y)}. Sampling ground and walls at different heights is what lets a
 * water block under the ball be a hazard while a stone block beside it is a bounce.
 */
public final class PhysicsEngine {

    private final PhysicsConfig config;
    private final BallCollisionConfig collision;

    public PhysicsEngine(PhysicsConfig config, BallCollisionConfig collision) {
        this.config = config;
        this.collision = collision;
    }

    public PhysicsConfig config() {
        return config;
    }

    public BallCollisionConfig collision() {
        return collision;
    }

    /**
     * Advances one ball by a single tick.
     *
     * @param ball    the ball to move; mutated in place
     * @param sampler surface lookup for the hole being played
     * @param cup     centre of the cup, used for the sink gate
     * @param others  every other ball on this hole; a struck one is woken in place
     */
    public StepOutcome step(BallState ball, SurfaceSampler sampler, Vec3 cup, List<BallState> others) {
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

        // 3. Ball-to-ball, swept along the path travelled this tick rather than tested at the
        // endpoint. At putting speeds a ball covers more ground per tick than the contact distance
        // is wide, so an endpoint-only test would let it pass clean through a resting ball - the
        // same tunneling failure the wall guard exists to prevent.
        BallState struck = null;
        if (collision.enabled() && others != null) {
            Vec3 travel = position.subtract(ball.position());
            double earliest = Double.MAX_VALUE;
            BallState hit = null;
            Vec3 contactPoint = null;
            for (BallState other : others) {
                if (other == ball) {
                    continue;
                }
                Double t = sweepHit(ball.position(), travel, other.position(), collision.contactDistance());
                if (t != null && t < earliest) {
                    earliest = t;
                    hit = other;
                    contactPoint = ball.position().add(travel.multiply(t));
                }
            }
            if (hit != null) {
                Vec3 normal = contactPoint.horizontalDirectionTo(hit.position());
                double approach = velocity.dot(normal);
                if (approach > 0.0) {
                    // Equal masses: the normal component crosses to the struck ball (scaled by
                    // restitution) and the striker keeps only what was tangential.
                    hit.wake(hit.velocity().add(normal.multiply(approach * collision.restitution())));
                    velocity = velocity.subtract(normal.multiply(approach));
                    struck = hit;
                }
                // Stop at the contact point so the pair cannot overlap or re-collide next tick.
                position = contactPoint;
            }
        }

        // 4. Sample the surface the ball is now rolling over.
        Surface surface = groundSurface(position, sampler);

        // 5. Impulse pads and currents both add velocity before friction eats into it.
        if (surface.hasImpulse()) {
            velocity = velocity.add(surface.impulse().asVelocityDelta());
        }

        // 6. Friction, skipped on a preventRest surface so a river carries the ball rather than
        // letting it grind to a halt mid-stream.
        if (!surface.preventsRest()) {
            velocity = velocity.multiply(surface.friction());
        }

        // 7. Tunneling guard. Clamping after the impulse is deliberate: a chain of boosters must
        // not be able to accelerate a ball past one block per tick.
        velocity = velocity.clampLength(config.maxVelocity());

        ball.setPosition(position);
        ball.setVelocity(velocity);

        // 10. Hazards win over resting and sinking - a ball that rolls into water is gone whatever
        // its speed, and the reset spot must be established before any rest bookkeeping runs.
        if (surface.isHazard()) {
            Vec3 target = surface.reset() == ResetMode.TEE ? ball.tee() : ball.lastRest();
            ball.placeAt(target);
            return new StepOutcome(StepResult.HAZARD, surface, Math.max(0, surface.penalty()), struck);
        }

        // 11. Sink gate. Being over the cup is not enough: too fast and the ball lips out and rolls
        // on, which is what makes a sunk putt feel earned rather than magnetic.
        double speed = velocity.length();
        if (position.horizontalDistance(cup) <= config.sinkRadius()) {
            if (speed <= config.maxSinkSpeed()) {
                ball.placeAt(cup);
                return new StepOutcome(StepResult.SUNK, surface, 0, struck);
            }
            return new StepOutcome(StepResult.MOVING, surface, 0, struck);
        }

        // 9. Rest check, suppressed while the ball is being carried by a current.
        if (speed < config.restEpsilon() && !surface.preventsRest()) {
            ball.comeToRest();
            return new StepOutcome(StepResult.CAME_TO_REST, surface, 0, struck);
        }
        return new StepOutcome(StepResult.MOVING, surface, 0, struck);
    }

    /** Converts an aim direction and a 0..1 meter reading into a launch velocity. */
    public Vec3 puttVelocity(Vec3 aimDirection, double speed) {
        Vec3 flat = new Vec3(aimDirection.x(), 0.0, aimDirection.z()).normalize();
        return flat.multiply(Math.max(0.0, speed)).clampLength(config.maxVelocity());
    }

    /**
     * Earliest fraction of this tick's travel at which a ball starting at {@code from} and moving
     * by {@code travel} comes within {@code contact} of a ball at {@code target}, or null if it
     * never does. Standard swept-circle root solve, flattened to the XZ plane.
     */
    static Double sweepHit(Vec3 from, Vec3 travel, Vec3 target, double contact) {
        Vec3 relative = new Vec3(from.x() - target.x(), 0.0, from.z() - target.z());
        Vec3 flatTravel = new Vec3(travel.x(), 0.0, travel.z());
        double a = flatTravel.lengthSquared();
        if (a < 1.0e-12) {
            return null;
        }
        double b = 2.0 * relative.dot(flatTravel);
        double c = relative.lengthSquared() - contact * contact;
        if (c < 0.0) {
            // Already overlapping at the start of the tick - resolve immediately.
            return 0.0;
        }
        double discriminant = b * b - 4.0 * a * c;
        if (discriminant < 0.0) {
            return null;
        }
        double root = (-b - Math.sqrt(discriminant)) / (2.0 * a);
        return root >= 0.0 && root <= 1.0 ? root : null;
    }

    private Surface groundSurface(Vec3 position, SurfaceSampler sampler) {
        return sampler.at(position.blockX(), (int) Math.floor(position.y() - 0.5), position.blockZ());
    }
}
