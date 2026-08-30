package com.redcoffee.puttputt.game;

import com.redcoffee.puttputt.RCPuttPuttPlugin;
import com.redcoffee.puttputt.course.Course;
import com.redcoffee.puttputt.course.Hole;
import com.redcoffee.puttputt.event.RCPuttPuttRoundCompleteEvent;
import com.redcoffee.puttputt.item.PuttItems;
import com.redcoffee.puttputt.party.PartyView;
import com.redcoffee.puttputt.util.Vec3;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * Owns every live round: starting them, ticking their balls, moving players between holes and
 * tearing them down.
 *
 * <p>Balls are keyed on {@code (roundId, player)} and never test against each other, only against
 * course geometry. That is what lets several parties share one course without turning cross-party
 * interference into a support queue.
 */
public final class RoundManager {

    /** The activity-lock id RCParties sees for a putt-putt round. */
    public static final String ACTIVITY_ID = "rcputtputt";

    private final RCPuttPuttPlugin plugin;
    private final PhysicsEngine engine;
    private final PuttItems items;

    private final Map<UUID, Round> roundsById = new LinkedHashMap<>();
    private final Map<UUID, UUID> roundIdByPlayer = new HashMap<>();
    private BukkitTask tickTask;

    public RoundManager(RCPuttPuttPlugin plugin, PhysicsEngine engine, PuttItems items) {
        this.plugin = plugin;
        this.engine = engine;
        this.items = items;
    }

    public void start() {
        if (tickTask == null) {
            // Synchronous: every step touches blocks and entities, both main-thread only.
            tickTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        }
    }

    public void shutdown() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        for (Round round : List.copyOf(roundsById.values())) {
            closeRound(round, false);
        }
    }

    public Optional<Round> roundOf(UUID playerId) {
        UUID roundId = roundIdByPlayer.get(playerId);
        return roundId == null ? Optional.empty() : Optional.ofNullable(roundsById.get(roundId));
    }

    public int activeRounds() {
        return roundsById.size();
    }

    // ------------------------------------------------------------------ start

    /** Result of a start attempt, so the command layer owns all the messaging. */
    public sealed interface StartResult {
        record Started(Round round) implements StartResult {}
        record Failed(String messageKey, Object[] placeholders) implements StartResult {}
    }

    private static StartResult fail(String key, Object... placeholders) {
        return new StartResult.Failed(key, placeholders);
    }

    public StartResult startRound(Player leader, Course course) {
        PartyView party = plugin.parties().partyOf(leader);
        if (!party.isLeader(leader.getUniqueId())) {
            return fail("round.not-leader");
        }
        if (!course.isPlayable()) {
            return fail("round.course-incomplete",
                    "course", course.id(),
                    "holes", course.unplayableHoles().toString());
        }
        World world = plugin.getServer().getWorld(course.world());
        if (world == null) {
            return fail("round.world-missing", "world", String.valueOf(course.world()));
        }

        List<UUID> members = new ArrayList<>();
        for (UUID memberId : party.memberIds()) {
            Player member = plugin.getServer().getPlayer(memberId);
            if (member == null || !member.isOnline()) {
                continue;
            }
            if (roundIdByPlayer.containsKey(memberId)) {
                return fail("round.member-busy", "player", member.getName());
            }
            members.add(memberId);
        }
        if (members.isEmpty()) {
            return fail("round.no-members");
        }
        if (!plugin.parties().acquireActivityLock(party.partyId(), ACTIVITY_ID)) {
            return fail("round.party-busy");
        }

        Round round = new Round(course, party.partyId(), new java.util.LinkedHashSet<>(members));
        roundsById.put(round.roundId(), round);
        Hole first = course.holes().getFirst();
        for (UUID memberId : members) {
            roundIdByPlayer.put(memberId, round.roundId());
            Player member = plugin.getServer().getPlayer(memberId);
            if (member != null) {
                sendToHole(round, member, first);
                member.getInventory().addItem(items.createPutter(plugin.config().putterItem()));
            }
        }
        plugin.runStorage(dao -> dao.recordRoundStart(round.roundId(), course.id(), round.startedAt()));
        return new StartResult.Started(round);
    }

    // ------------------------------------------------------------------ putting

    /**
     * Takes a stroke. Returns false when the shot is rejected (no round, ball still rolling, or a
     * draw too light to count), so the caller can decide whether to say anything about it.
     */
    public boolean putt(Player player, double force) {
        Round round = roundOf(player.getUniqueId()).orElse(null);
        if (round == null) {
            return false;
        }
        Ball ball = round.ball(player.getUniqueId());
        Scorecard card = round.scorecard(player.getUniqueId());
        if (ball == null || card == null || card.finished()) {
            return false;
        }
        if (!ball.state().atRest()) {
            plugin.messages().sendActionBar(player, "putt.still-rolling");
            return false;
        }
        Vec3 aim = directionOf(player);
        Vec3 velocity = engine.puttVelocity(aim, force);
        if (velocity.lengthSquared() == 0.0) {
            return false;
        }
        ball.state().strike(velocity);
        card.addStrokes(1);
        plugin.messages().sendActionBar(player, "putt.stroke",
                "strokes", String.valueOf(card.currentStrokes()),
                "hole", String.valueOf(card.currentHole()));
        return true;
    }

    private static Vec3 directionOf(Player player) {
        var direction = player.getLocation().getDirection();
        return new Vec3(direction.getX(), 0.0, direction.getZ());
    }

    // ------------------------------------------------------------------ tick

    private void tick() {
        for (Round round : List.copyOf(roundsById.values())) {
            for (Map.Entry<UUID, Ball> entry : round.balls().entrySet()) {
                tickBall(round, entry.getKey(), entry.getValue());
            }
            if (round.everyoneFinished() && round.state() == RoundState.IN_PROGRESS) {
                closeRound(round, true);
            }
        }
    }

    private void tickBall(Round round, UUID playerId, Ball ball) {
        Scorecard card = round.scorecard(playerId);
        if (card == null || card.finished() || ball.state().atRest()) {
            return;
        }
        Hole hole = round.course().hole(card.currentHole()).orElse(null);
        // A hole can be edited or deleted mid-round by an admin; without geometry there is nothing
        // to simulate against, so park the ball rather than stepping it into undefined space.
        if (hole == null || hole.cup() == null || hole.bounds() == null) {
            ball.state().comeToRest();
            return;
        }
        Player player = plugin.getServer().getPlayer(playerId);
        SurfaceSampler sampler = new WorldSurfaceSampler(
                ball.world(), plugin.config().surfaces(), hole.materialOverrides());

        StepOutcome outcome = engine.step(ball.state(), sampler, hole.cup());

        // A ball outside the hole's AABB has escaped the geometry the collision model assumes;
        // treat it exactly like a hazard rather than letting it roll off into the world.
        if (outcome.result() != StepResult.SUNK && !hole.bounds().contains(ball.state().position())) {
            ball.snapTo(ball.state().lastRest());
            card.addStrokes(plugin.config().outOfBoundsPenalty());
            if (player != null) {
                plugin.messages().send(player, "putt.out-of-bounds",
                        "penalty", String.valueOf(plugin.config().outOfBoundsPenalty()));
            }
            return;
        }

        switch (outcome.result()) {
            case MOVING -> ball.syncDisplay();
            case CAME_TO_REST -> ball.syncDisplay();
            case HAZARD -> {
                card.addStrokes(outcome.penaltyStrokes());
                ball.snapTo(ball.state().position());
                if (player != null) {
                    plugin.messages().send(player, "putt.hazard",
                            "surface", outcome.surface().id(),
                            "penalty", String.valueOf(outcome.penaltyStrokes()));
                }
            }
            case SUNK -> {
                ball.syncDisplay();
                onSink(round, playerId, player, card, hole);
            }
        }
    }

    private void onSink(Round round, UUID playerId, Player player, Scorecard card, Hole hole) {
        int strokes = card.currentStrokes();
        if (player != null) {
            plugin.messages().send(player, "putt.sunk",
                    "hole", String.valueOf(hole.number()),
                    "strokes", String.valueOf(strokes),
                    "par", String.valueOf(hole.par()),
                    "result", scoreName(strokes, hole.par()));
        }
        Optional<Hole> next = nextHole(round.course(), hole.number());
        if (next.isEmpty()) {
            card.finish();
            Ball ball = round.ball(playerId);
            if (ball != null) {
                ball.remove();
            }
            if (player != null) {
                plugin.messages().send(player, "round.player-finished",
                        "strokes", String.valueOf(card.totalStrokes()),
                        "diff", formatDiff(round.parDiffFor(playerId)));
            }
            return;
        }
        card.completeHole(next.get().number());
        if (player != null) {
            sendToHole(round, player, next.get());
        }
    }

    private static Optional<Hole> nextHole(Course course, int afterNumber) {
        return course.holes().stream().filter(h -> h.number() > afterNumber).findFirst();
    }

    // ------------------------------------------------------------------ hole setup

    /** Teleports the player to a tee and gives them a fresh ball there. */
    public void sendToHole(Round round, Player player, Hole hole) {
        World world = plugin.getServer().getWorld(round.course().world());
        if (world == null) {
            return;
        }
        Location tee = new Location(world, hole.tee().x(), hole.tee().y(), hole.tee().z());
        // Face the cup so the first stroke starts from a sane aim rather than wherever they walked in from.
        Location cup = new Location(world, hole.cup().x(), hole.cup().y(), hole.cup().z());
        tee.setDirection(cup.toVector().subtract(tee.toVector()));
        player.teleport(tee);

        round.setBall(player.getUniqueId(), spawnBall(world, hole.tee()));
        plugin.messages().send(player, "round.hole-start",
                "hole", String.valueOf(hole.number()),
                "par", String.valueOf(hole.par()));
    }

    private Ball spawnBall(World world, Vec3 position) {
        Location location = new Location(world, position.x(), position.y(), position.z());
        ItemDisplay display = world.spawn(location, ItemDisplay.class, spawned ->
                items.applyBallModel(spawned, plugin.config().ballItem()));
        return new Ball(new BallState(position), display, world);
    }

    // ------------------------------------------------------------------ leaving / closing

    /** Drops one player out of their round. The round survives unless they were the last one. */
    public boolean leave(Player player) {
        Round round = roundOf(player.getUniqueId()).orElse(null);
        if (round == null) {
            return false;
        }
        removeFromRound(round, player.getUniqueId());
        plugin.messages().send(player, "round.left");
        if (round.players().isEmpty()) {
            closeRound(round, false);
        }
        return true;
    }

    private void removeFromRound(Round round, UUID playerId) {
        round.removePlayer(playerId);
        roundIdByPlayer.remove(playerId);
        Player player = plugin.getServer().getPlayer(playerId);
        if (player != null) {
            items.stripPutters(player);
        }
    }

    /** Ends the round for everyone. {@code persist} is false when nothing was actually completed. */
    public void closeRound(Round round, boolean persist) {
        if (round.state() == RoundState.COMPLETE && !roundsById.containsKey(round.roundId())) {
            return;
        }
        round.markComplete();
        long endedAt = System.currentTimeMillis();

        Map<UUID, Integer> totals = new LinkedHashMap<>();
        Map<UUID, Integer> diffs = new LinkedHashMap<>();
        if (persist) {
            for (Map.Entry<UUID, Scorecard> entry : round.scorecards().entrySet()) {
                UUID playerId = entry.getKey();
                Scorecard card = entry.getValue();
                if (!card.finished()) {
                    // An unfinished card is not a comparable round; leave it off the leaderboard.
                    continue;
                }
                int total = card.totalStrokes();
                int diff = round.parDiffFor(playerId);
                totals.put(playerId, total);
                diffs.put(playerId, diff);
                String name = nameOf(playerId);
                plugin.runStorage(dao -> dao.recordScore(round.roundId(), playerId, name,
                        round.course().id(), total, diff, endedAt));
            }
        }
        plugin.runStorage(dao -> dao.recordRoundEnd(round.roundId(), endedAt));

        for (UUID playerId : round.players()) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null) {
                plugin.messages().send(player, "round.complete", "course", round.course().displayName());
            }
            removeFromRound(round, playerId);
        }
        for (Ball ball : round.balls().values()) {
            ball.remove();
        }
        roundsById.remove(round.roundId());
        plugin.parties().releaseActivityLock(round.partyId(), ACTIVITY_ID);

        // Fired last, once the round is fully torn down, so a wager layer settling on it sees final state.
        plugin.getServer().getPluginManager().callEvent(
                new RCPuttPuttRoundCompleteEvent(round.roundId(), round.course().id(), round.partyId(), totals, diffs));
    }

    private String nameOf(UUID playerId) {
        Player online = plugin.getServer().getPlayer(playerId);
        if (online != null) {
            return online.getName();
        }
        String offline = plugin.getServer().getOfflinePlayer(playerId).getName();
        return offline != null ? offline : playerId.toString();
    }

    // ------------------------------------------------------------------ formatting helpers

    public static String formatDiff(int diff) {
        if (diff == 0) {
            return "E";
        }
        return diff > 0 ? "+" + diff : String.valueOf(diff);
    }

    /** Golf names for a hole score, used in the sink message. */
    public static String scoreName(int strokes, int par) {
        if (strokes == 1) {
            return "Hole in one";
        }
        return switch (strokes - par) {
            case -3 -> "Albatross";
            case -2 -> "Eagle";
            case -1 -> "Birdie";
            case 0 -> "Par";
            case 1 -> "Bogey";
            case 2 -> "Double bogey";
            case 3 -> "Triple bogey";
            default -> strokes < par ? "Under par" : "Over par";
        };
    }

}
