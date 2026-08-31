package com.redcoffee.puttputt.game;

import com.redcoffee.puttputt.RCPuttPuttPlugin;
import com.redcoffee.puttputt.course.Course;
import com.redcoffee.puttputt.course.Hole;
import com.redcoffee.puttputt.event.RCPuttPuttRoundCompleteEvent;
import com.redcoffee.puttputt.item.PuttItems;
import com.redcoffee.puttputt.party.PartyView;
import com.redcoffee.puttputt.snapshot.RoundSnapshot;
import com.redcoffee.puttputt.util.Vec3;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * Owns every live round: starting them, running the turn loop, ticking balls and tearing them down
 * (RC-SPEC-PUTTPUTT-001 v2 s3).
 *
 * <p>Play is turn-based, so the loop is a small state machine per round: exactly one player may
 * charge at a time; once they strike, no new turn begins until <em>every</em> ball on the hole has
 * come to rest, so knock-ons from a collision fully resolve before the next player is up.
 */
public final class RoundManager {

    /** The activity-lock id RCParties sees for a putt-putt round. */
    public static final String ACTIVITY_ID = "rcputtputt";

    private final RCPuttPuttPlugin plugin;
    private final PhysicsEngine engine;
    private final PuttItems items;
    private final Random random = new Random();

    private final Map<UUID, Round> roundsById = new LinkedHashMap<>();
    private final Map<UUID, UUID> roundIdByPlayer = new HashMap<>();
    private final Map<UUID, TurnState> turnStates = new HashMap<>();
    private BukkitTask tickTask;
    private BukkitTask snapshotTask;

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
        if (snapshotTask == null) {
            long interval = plugin.config().snapshots().intervalTicks();
            snapshotTask = plugin.getServer().getScheduler()
                    .runTaskTimer(plugin, this::snapshotAll, interval, interval);
        }
    }

    public void shutdown() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        if (snapshotTask != null) {
            snapshotTask.cancel();
            snapshotTask = null;
        }
        // A clean shutdown still snapshots, so a planned restart can be resumed like a crash.
        snapshotAll();
        for (Round round : List.copyOf(roundsById.values())) {
            teardown(round, false, false);
        }
    }

    public Optional<Round> roundOf(UUID playerId) {
        UUID roundId = roundIdByPlayer.get(playerId);
        return roundId == null ? Optional.empty() : Optional.ofNullable(roundsById.get(roundId));
    }

    /** Every live round belonging to a party. Normally at most one. */
    public List<Round> roundsOfParty(UUID partyId) {
        return roundsById.values().stream().filter(round -> round.partyId().equals(partyId)).toList();
    }

    public int activeRounds() {
        return roundsById.size();
    }

    public Optional<TurnState> turnState(UUID roundId) {
        return Optional.ofNullable(turnStates.get(roundId));
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

        Round round;
        try {
            round = new Round(course, party.partyId(), new LinkedHashSet<>(members), random);
            register(round, members);
        } catch (RuntimeException ex) {
            // Never leave a lock behind on a failed start - a stuck lock traps the party until an
            // admin runs /party admin clearlocks.
            plugin.parties().releaseActivityLock(party.partyId(), ACTIVITY_ID);
            throw ex;
        }
        plugin.runStorage(dao -> dao.recordRoundStart(round.roundId(), course.id(),
                RoundSnapshot.encodeMembers(members), round.startedAt()));
        beginHole(round);
        return new StartResult.Started(round);
    }

    private void register(Round round, List<UUID> members) {
        roundsById.put(round.roundId(), round);
        turnStates.put(round.roundId(), new TurnState());
        for (UUID memberId : members) {
            roundIdByPlayer.put(memberId, round.roundId());
        }
    }

    // ------------------------------------------------------------------ holes and turns

    /** Warps everyone to the tee, gives them a ball and starts the first turn. */
    private void beginHole(Round round) {
        Hole hole = round.currentHole().orElse(null);
        if (hole == null) {
            teardown(round, true, true);
            return;
        }
        World world = plugin.getServer().getWorld(round.course().world());
        if (world == null) {
            return;
        }
        round.clearBalls();
        for (UUID playerId : round.players()) {
            // Balls are fanned out slightly so several tee shots do not start overlapping - with
            // ball-ball collision on, co-located balls would shove each other on the first stroke.
            round.setBall(playerId, spawnBall(world, teeSpotFor(hole, round, playerId)));
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null) {
                teleportTo(player, hole.tee(), hole.cup(), world);
                plugin.messages().send(player, "round.hole-start",
                        "hole", String.valueOf(hole.number()),
                        "par", String.valueOf(hole.par()));
            }
        }
        announceOrder(round);
        beginTurn(round);
    }

    /**
     * Spreads tee shots around the tee position. The offset is deterministic per player so a
     * resumed round puts everyone back where they were.
     */
    private Vec3 teeSpotFor(Hole hole, Round round, UUID playerId) {
        int index = Math.max(0, round.turnOrder().indexOf(playerId));
        double angle = index * (Math.PI * 2.0 / Math.max(1, round.turnOrder().size()));
        double spread = plugin.config().ballCollision().contactDistance() * 1.5;
        return hole.tee().add(Math.cos(angle) * spread, 0.0, Math.sin(angle) * spread);
    }

    private void announceOrder(Round round) {
        List<String> names = round.turnOrder().stream().map(this::nameOf).toList();
        for (UUID playerId : round.players()) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null) {
                plugin.messages().send(player, "turn.order", "order", String.join(", ", names));
            }
        }
    }

    /** Puts the next player on the clock. */
    private void beginTurn(Round round) {
        TurnState state = turnStates.get(round.roundId());
        if (state == null) {
            return;
        }
        state.clearCharge(plugin, round);

        Optional<UUID> next = round.currentPlayer();
        if (next.isEmpty()) {
            finishHole(round);
            return;
        }
        UUID playerId = next.get();
        state.beginTurn(playerId, plugin.config().turns().shotClockTicks());

        Player player = plugin.getServer().getPlayer(playerId);
        Ball ball = round.ball(playerId);
        if (player == null) {
            // Offline mid-round: burn their turn rather than stalling everyone else.
            timeoutTurn(round, playerId);
            return;
        }
        if (ball != null) {
            World world = plugin.getServer().getWorld(round.course().world());
            Hole hole = round.currentHole().orElse(null);
            if (world != null && hole != null) {
                // Teleported to their ball, facing the cup: the turn should start ready to aim.
                teleportTo(player, ball.state().position(), hole.cup(), world);
            }
        }
        ensurePutter(player);
        plugin.messages().send(player, "turn.yours",
                "seconds", String.valueOf(plugin.config().turns().shotClockSeconds()));
        for (UUID other : round.players()) {
            if (!other.equals(playerId)) {
                Player watcher = plugin.getServer().getPlayer(other);
                if (watcher != null) {
                    plugin.messages().send(watcher, "turn.other", "player", player.getName());
                }
            }
        }
    }

    private void finishHole(Round round) {
        Optional<Integer> next = round.advanceHole(plugin.config().turns().mode());
        if (next.isEmpty()) {
            teardown(round, true, true);
            return;
        }
        beginHole(round);
    }

    // ------------------------------------------------------------------ tick

    private void tick() {
        for (Round round : List.copyOf(roundsById.values())) {
            TurnState state = turnStates.get(round.roundId());
            if (state == null || round.state() != RoundState.IN_PROGRESS) {
                continue;
            }
            if (round.anyBallMoving()) {
                tickBalls(round);
                if (!round.anyBallMoving()) {
                    // Everything has settled: resolve the stroke and hand over the turn.
                    afterBallsSettled(round);
                }
                continue;
            }
            tickCharge(round, state);
        }
    }

    /** Drives the power meter for whoever is on the clock, and enforces the shot clock. */
    private void tickCharge(Round round, TurnState state) {
        UUID playerId = state.currentPlayer();
        if (playerId == null) {
            beginTurn(round);
            return;
        }
        Player player = plugin.getServer().getPlayer(playerId);
        if (player == null) {
            timeoutTurn(round, playerId);
            return;
        }

        boolean holding = items.isPutter(player.getInventory().getItemInMainHand()) && player.isHandRaised();
        if (holding) {
            state.startChargeIfNeeded(plugin, round, playerId);
            PowerMeter meter = state.meter();
            if (meter != null) {
                double power = meter.tick();
                meter.updateTitle(plugin.messages().render("power.title",
                        "player", player.getName(),
                        "percent", String.valueOf((int) Math.round(power * 100))));
            }
            return;
        }
        if (state.meter() != null) {
            // Released: lock in whatever the bar read at this instant.
            strike(round, state, player);
            return;
        }

        if (state.tickClock()) {
            timeoutTurn(round, playerId);
        } else if (state.shouldWarn()) {
            plugin.messages().sendActionBar(player, "turn.clock",
                    "seconds", String.valueOf(state.secondsLeft()));
        }
    }

    private void strike(Round round, TurnState state, Player player) {
        PowerMeter meter = state.meter();
        Ball ball = round.ball(player.getUniqueId());
        Scorecard card = round.scorecard(player.getUniqueId());
        double speed = meter.velocity();
        state.clearCharge(plugin, round);

        if (ball == null || card == null || !ball.state().atRest()) {
            return;
        }
        var direction = player.getLocation().getDirection();
        ball.state().strike(engine.puttVelocity(new Vec3(direction.getX(), 0, direction.getZ()), speed));
        card.addStrokes(1);
        card.clearTimeouts();
        state.markStruck();
        plugin.messages().sendActionBar(player, "putt.stroke",
                "strokes", String.valueOf(card.currentStrokes()),
                "hole", String.valueOf(round.currentHoleNumber()));
    }

    private void tickBalls(Round round) {
        Hole hole = round.currentHole().orElse(null);
        if (hole == null || hole.cup() == null || hole.bounds() == null) {
            round.balls().values().forEach(ball -> ball.state().comeToRest());
            return;
        }
        HoleContext context = contextFor(round, hole);
        List<BallState> all = round.ballStates();

        for (Map.Entry<UUID, Ball> entry : List.copyOf(round.balls().entrySet())) {
            UUID playerId = entry.getKey();
            Ball ball = entry.getValue();
            if (ball.state().atRest() || round.hasFinishedThisHole(playerId)) {
                continue;
            }
            StepOutcome outcome = engine.step(ball.state(), context, all);
            Player player = plugin.getServer().getPlayer(playerId);

            // Outside the hole's AABB the block-based collision model no longer holds, so treat it
            // exactly like a hazard rather than letting the ball roll off into the world.
            if (outcome.result() != StepResult.SUNK && !hole.bounds().contains(ball.state().position())) {
                ball.snapTo(ball.state().lastRest());
                round.scorecard(playerId).addStrokes(plugin.config().outOfBoundsPenalty());
                if (player != null) {
                    plugin.messages().send(player, "putt.out-of-bounds",
                            "penalty", String.valueOf(plugin.config().outOfBoundsPenalty()));
                }
                continue;
            }

            switch (outcome.result()) {
                case MOVING, CAME_TO_REST -> ball.syncDisplay();
                case HAZARD -> {
                    round.scorecard(playerId).addStrokes(outcome.penaltyStrokes());
                    ball.snapTo(ball.state().position());
                    if (player != null) {
                        plugin.messages().send(player, "putt.hazard",
                                "surface", outcome.surface().id(),
                                "penalty", String.valueOf(outcome.penaltyStrokes()));
                    }
                }
                case SUNK -> {
                    ball.syncDisplay();
                    sink(round, playerId, hole);
                }
            }
        }
    }

    /** A ball dropped. Whether that counts depends on whether its owner actually hit it. */
    private void sink(Round round, UUID playerId, Hole hole) {
        TurnState state = turnStates.get(round.roundId());
        boolean ownStroke = state == null || playerId.equals(state.currentPlayer());
        if (!ownStroke && !plugin.config().ballCollision().allowKnockIn()) {
            // Purist mode: a knocked-in ball is put back rather than counted.
            Ball ball = round.ball(playerId);
            if (ball != null) {
                ball.snapTo(ball.state().lastRest());
            }
            return;
        }
        if (round.hasFinishedThisHole(playerId)) {
            return;
        }
        round.markFinishedThisHole(playerId);
        Scorecard card = round.scorecard(playerId);
        int strokes = card.currentStrokes();
        Player player = plugin.getServer().getPlayer(playerId);
        if (player != null) {
            plugin.messages().send(player, ownStroke ? "putt.sunk" : "putt.knocked-in",
                    "hole", String.valueOf(hole.number()),
                    "strokes", String.valueOf(strokes),
                    "par", String.valueOf(hole.par()),
                    "result", scoreName(strokes, hole.par()));
        }
        Ball ball = round.ball(playerId);
        if (ball != null) {
            ball.remove();
        }
    }

    /** Called once every ball has stopped: apply the stroke cap, then hand over the turn. */
    private void afterBallsSettled(Round round) {
        int cap = plugin.config().turns().maxStrokesPerHole();
        for (UUID playerId : round.players()) {
            if (round.hasFinishedThisHole(playerId)) {
                continue;
            }
            if (round.scorecard(playerId).currentStrokes() >= cap) {
                round.markFinishedThisHole(playerId);
                Player player = plugin.getServer().getPlayer(playerId);
                if (player != null) {
                    plugin.messages().send(player, "turn.capped", "strokes", String.valueOf(cap));
                }
            }
        }
        if (round.isHoleComplete()) {
            finishHole(round);
            return;
        }
        round.advanceTurn();
        beginTurn(round);
    }

    private void timeoutTurn(Round round, UUID playerId) {
        Scorecard card = round.scorecard(playerId);
        TurnState state = turnStates.get(round.roundId());
        if (card == null) {
            if (state != null) {
                state.clearCharge(plugin, round);
            }
            round.advanceTurn();
            beginTurn(round);
            return;
        }
        card.addStrokes(plugin.config().turns().timeoutPenalty());
        card.recordTimeout();
        Player player = plugin.getServer().getPlayer(playerId);
        if (player != null) {
            plugin.messages().send(player, "turn.timeout",
                    "penalty", String.valueOf(plugin.config().turns().timeoutPenalty()));
        }
        // Repeated forfeits mean an AFK player; cap them out so the hole can finish.
        if (card.consecutiveTimeouts() >= plugin.config().turns().maxConsecutiveTimeouts()) {
            card.addStrokes(Math.max(0, plugin.config().turns().maxStrokesPerHole() - card.currentStrokes()));
            round.markFinishedThisHole(playerId);
            Ball ball = round.ball(playerId);
            if (ball != null) {
                ball.remove();
            }
        }
        if (round.isHoleComplete()) {
            finishHole(round);
            return;
        }
        round.advanceTurn();
        beginTurn(round);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Builds the read window for a hole: the world sampler, confined to the hole's own region so a
     * ball can never read a block that is not part of the course.
     */
    private HoleContext contextFor(Round round, Hole hole) {
        SurfaceSampler sampler = new WorldSurfaceSampler(
                plugin.getServer().getWorld(round.course().world()),
                plugin.config().surfaces(), hole.materialOverrides());
        return new HoleContext(sampler, hole.cup(), hole.bounds(),
                plugin.config().outsideBoundsSurface(), hole.teleportLookup());
    }

    private void teleportTo(Player player, Vec3 target, Vec3 lookAt, World world) {
        Location location = new Location(world, target.x(), target.y(), target.z());
        if (lookAt != null) {
            location.setDirection(new Location(world, lookAt.x(), lookAt.y(), lookAt.z())
                    .toVector().subtract(location.toVector()));
        }
        player.teleport(location);
    }

    private void ensurePutter(Player player) {
        if (!items.hasPutter(player)) {
            player.getInventory().addItem(items.createPutter(plugin.config().putterItem()));
        }
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
        UUID playerId = player.getUniqueId();
        TurnState state = turnStates.get(round.roundId());
        boolean wasTheirTurn = state != null && playerId.equals(state.currentPlayer());
        removeFromRound(round, playerId);
        plugin.messages().send(player, "round.left");
        if (round.players().isEmpty()) {
            teardown(round, false, true);
            return true;
        }
        if (wasTheirTurn) {
            // Never leave the round waiting on someone who has gone.
            state.clearCharge(plugin, round);
            round.advanceTurn();
            beginTurn(round);
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

    /** Ends the round for everyone. */
    public void closeRound(Round round, boolean persist) {
        teardown(round, persist, true);
    }

    private void teardown(Round round, boolean persist, boolean clearSnapshot) {
        if (!roundsById.containsKey(round.roundId())) {
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
                if (!card.finishedRound()) {
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
        plugin.runStorage(dao -> dao.recordRoundEnd(round.roundId(), endedAt,
                persist ? "COMPLETE" : "ARCHIVED"));
        if (clearSnapshot) {
            plugin.runStorage(dao -> dao.clearSnapshot(round.roundId()));
        }

        TurnState state = turnStates.remove(round.roundId());
        if (state != null) {
            state.clearCharge(plugin, round);
        }
        for (UUID playerId : round.players()) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null) {
                plugin.messages().send(player, "round.complete", "course", round.course().displayComponent());
            }
            removeFromRound(round, playerId);
        }
        round.clearBalls();
        roundsById.remove(round.roundId());
        plugin.parties().releaseActivityLock(round.partyId(), ACTIVITY_ID);

        // Fired last, once the round is fully torn down, so a wager layer settling on it sees final state.
        plugin.getServer().getPluginManager().callEvent(
                new RCPuttPuttRoundCompleteEvent(round.roundId(), round.course().id(), round.partyId(), totals, diffs));
    }

    // ------------------------------------------------------------------ snapshots

    private void snapshotAll() {
        for (Round round : List.copyOf(roundsById.values())) {
            if (round.state() != RoundState.IN_PROGRESS) {
                continue;
            }
            TurnState state = turnStates.get(round.roundId());
            RoundSnapshot snapshot = RoundSnapshot.capture(round, state);
            plugin.runStorage(dao -> dao.saveSnapshot(round.roundId(), snapshot.toJson(), System.currentTimeMillis()));
        }
    }

    /** Restores a round read back from a snapshot and puts its players back on the clock. */
    public void resume(Round round, RoundSnapshot snapshot, List<UUID> members) {
        register(round, members);
        snapshot.applyTo(round);
        World world = plugin.getServer().getWorld(round.course().world());
        Hole hole = round.currentHole().orElse(null);
        if (world == null || hole == null) {
            teardown(round, false, true);
            return;
        }
        for (UUID playerId : round.players()) {
            Vec3 position = snapshot.ballPositions().getOrDefault(playerId, hole.tee());
            round.setBall(playerId, spawnBall(world, position));
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null) {
                teleportTo(player, position, hole.cup(), world);
                plugin.messages().send(player, "round.resumed",
                        "course", round.course().displayComponent(),
                        "hole", String.valueOf(round.currentHoleNumber()));
            }
        }
        beginTurn(round);
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
