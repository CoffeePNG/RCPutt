package com.redcoffee.puttputt.game;

import com.redcoffee.puttputt.course.Course;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** One party playing one course (RC-SPEC-PUTTPUTT-001 s6). */
public final class Round {

    private final UUID roundId = UUID.randomUUID();
    private final Course course;
    private final UUID partyId;
    private final Map<UUID, Scorecard> scorecards = new LinkedHashMap<>();
    private final Map<UUID, Ball> balls = new LinkedHashMap<>();
    private final long startedAt = System.currentTimeMillis();
    private RoundState state = RoundState.IN_PROGRESS;

    public Round(Course course, UUID partyId, Set<UUID> members) {
        this.course = course;
        this.partyId = partyId;
        int firstHole = course.holes().getFirst().number();
        for (UUID member : members) {
            scorecards.put(member, new Scorecard(firstHole));
        }
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

    /** Drops a player mid-round: their ball goes, their scorecard goes, the round carries on. */
    public void removePlayer(UUID playerId) {
        Ball ball = balls.remove(playerId);
        if (ball != null) {
            ball.remove();
        }
        scorecards.remove(playerId);
    }

    /** The round is done when nobody is left with holes to play. */
    public boolean everyoneFinished() {
        return scorecards.isEmpty() || scorecards.values().stream().allMatch(Scorecard::finished);
    }

    public int parDiffFor(UUID playerId) {
        Scorecard card = scorecards.get(playerId);
        if (card == null) {
            return 0;
        }
        int playedPar = card.strokesByHole().keySet().stream()
                .map(course::hole)
                .filter(java.util.Optional::isPresent)
                .mapToInt(hole -> hole.get().par())
                .sum();
        return card.totalStrokes() - playedPar;
    }
}
