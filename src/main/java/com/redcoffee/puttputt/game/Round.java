package com.redcoffee.puttputt.game;

import com.redcoffee.puttputt.config.TurnOrderMode;
import com.redcoffee.puttputt.course.Course;
import com.redcoffee.puttputt.course.Hole;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * One party playing one course, turn by turn (RC-SPEC-PUTTPUTT-001 v2 s8).
 *
 * <p>Unlike v1's independent balls, every player is on the <em>same</em> hole at once and takes
 * turns on it. The hole advances only when everyone has finished it, which is what makes the
 * leader-punishing re-sort between holes meaningful.
 */
public final class Round {

    private final UUID roundId;
    private final Course course;
    private final UUID partyId;
    private final Map<UUID, Scorecard> scorecards = new LinkedHashMap<>();
    private final Map<UUID, Ball> balls = new LinkedHashMap<>();
    private final Set<UUID> finishedThisHole = new LinkedHashSet<>();
    private final long startedAt;

    private List<UUID> turnOrder;
    private int turnIndex;
    private int currentHoleNumber;
    private RoundState state = RoundState.IN_PROGRESS;

    public Round(Course course, UUID partyId, Collection<UUID> members, Random random) {
        this(UUID.randomUUID(), course, partyId, members, System.currentTimeMillis());
        this.turnOrder = TurnOrder.initial(members, random);
    }

    /** Constructor used when restoring from a snapshot; turn order is set separately. */
    public Round(UUID roundId, Course course, UUID partyId, Collection<UUID> members, long startedAt) {
        this.roundId = roundId;
        this.course = course;
        this.partyId = partyId;
        this.startedAt = startedAt;
        for (UUID member : members) {
            scorecards.put(member, new Scorecard());
        }
        this.turnOrder = List.copyOf(members);
        this.currentHoleNumber = course.holes().isEmpty() ? 1 : course.holes().getFirst().number();
    }

    public UUID roundId() {
        return roundId;
    }

    public Course course() {
        return course;
    }

    public UUID partyId() {
        return partyId;
    }

    public long startedAt() {
        return startedAt;
    }

    public RoundState state() {
        return state;
    }

    public void markComplete() {
        this.state = RoundState.COMPLETE;
    }

    public Set<UUID> players() {
        return Set.copyOf(scorecards.keySet());
    }

    public boolean contains(UUID playerId) {
        return scorecards.containsKey(playerId);
    }

    public Scorecard scorecard(UUID playerId) {
        return scorecards.get(playerId);
    }

    public Map<UUID, Scorecard> scorecards() {
        return Map.copyOf(scorecards);
    }

    public List<UUID> turnOrder() {
        return turnOrder;
    }

    public void setTurnOrder(List<UUID> order) {
        this.turnOrder = List.copyOf(order);
    }

    public int turnIndex() {
        return turnIndex;
    }

    public void setTurnIndex(int turnIndex) {
        this.turnIndex = turnIndex;
    }

    public int currentHoleNumber() {
        return currentHoleNumber;
    }

    public void setCurrentHoleNumber(int number) {
        this.currentHoleNumber = number;
    }

    public Optional<Hole> currentHole() {
        return course.hole(currentHoleNumber);
    }

    public Set<UUID> finishedThisHole() {
        return Set.copyOf(finishedThisHole);
    }

    public void markFinishedThisHole(UUID playerId) {
        finishedThisHole.add(playerId);
    }

    public boolean hasFinishedThisHole(UUID playerId) {
        return finishedThisHole.contains(playerId);
    }

    // ------------------------------------------------------------------ turns

    /** Whose turn it is, or empty if everyone on this hole is done. */
    public Optional<UUID> currentPlayer() {
        for (int offset = 0; offset < turnOrder.size(); offset++) {
            UUID candidate = turnOrder.get((turnIndex + offset) % turnOrder.size());
            if (scorecards.containsKey(candidate) && !finishedThisHole.contains(candidate)) {
                if (offset > 0) {
                    turnIndex = (turnIndex + offset) % turnOrder.size();
                }
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** Moves to the next player still playing this hole. */
    public Optional<UUID> advanceTurn() {
        if (turnOrder.isEmpty()) {
            return Optional.empty();
        }
        turnIndex = (turnIndex + 1) % turnOrder.size();
        return currentPlayer();
    }

    public boolean isHoleComplete() {
        return scorecards.keySet().stream().allMatch(finishedThisHole::contains);
    }

    /**
     * Banks the hole for everyone, re-sorts the order for the next one and moves on.
     *
     * @return the new hole number, or empty if the course is finished
     */
    public Optional<Integer> advanceHole(TurnOrderMode mode) {
        for (Map.Entry<UUID, Scorecard> entry : scorecards.entrySet()) {
            entry.getValue().completeHole(currentHoleNumber);
        }
        Optional<Hole> next = course.holes().stream()
                .filter(hole -> hole.number() > currentHoleNumber)
                .findFirst();
        if (next.isEmpty()) {
            scorecards.values().forEach(Scorecard::finishRound);
            return Optional.empty();
        }
        Map<UUID, Integer> totals = new LinkedHashMap<>();
        scorecards.forEach((id, card) -> totals.put(id, card.runningTotal()));
        turnOrder = TurnOrder.nextHole(turnOrder, totals, mode);
        turnIndex = 0;
        finishedThisHole.clear();
        currentHoleNumber = next.get().number();
        return Optional.of(currentHoleNumber);
    }

    // ------------------------------------------------------------------ balls

    public Ball ball(UUID playerId) {
        return balls.get(playerId);
    }

    public void setBall(UUID playerId, Ball ball) {
        Ball previous = balls.put(playerId, ball);
        if (previous != null) {
            previous.remove();
        }
    }

    public Map<UUID, Ball> balls() {
        return Map.copyOf(balls);
    }

    /** Every ball on the hole, for the collision pass. */
    public List<BallState> ballStates() {
        List<BallState> states = new ArrayList<>();
        balls.values().forEach(ball -> states.add(ball.state()));
        return states;
    }

    /** True while any ball is still rolling - the turn cannot advance until this is false. */
    public boolean anyBallMoving() {
        return balls.values().stream().anyMatch(ball -> !ball.state().atRest());
    }

    public void clearBalls() {
        balls.values().forEach(Ball::remove);
        balls.clear();
    }

    /** Drops a player mid-round: their ball goes, their scorecard goes, the round carries on. */
    public void removePlayer(UUID playerId) {
        Ball ball = balls.remove(playerId);
        if (ball != null) {
            ball.remove();
        }
        scorecards.remove(playerId);
        finishedThisHole.remove(playerId);
        List<UUID> trimmed = new ArrayList<>(turnOrder);
        trimmed.remove(playerId);
        turnOrder = List.copyOf(trimmed);
        if (turnIndex >= turnOrder.size()) {
            turnIndex = 0;
        }
    }

    public boolean everyoneFinished() {
        return scorecards.isEmpty() || scorecards.values().stream().allMatch(Scorecard::finishedRound);
    }

    public int parDiffFor(UUID playerId) {
        Scorecard card = scorecards.get(playerId);
        if (card == null) {
            return 0;
        }
        int playedPar = card.strokesByHole().keySet().stream()
                .map(course::hole)
                .filter(Optional::isPresent)
                .mapToInt(hole -> hole.get().par())
                .sum();
        return card.totalStrokes() - playedPar;
    }
}
