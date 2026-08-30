package com.redcoffee.puttputt.game;

import java.util.LinkedHashMap;
import java.util.Map;

/** Per-player stroke record for one round. */
public final class Scorecard {

    private final Map<Integer, Integer> strokesByHole = new LinkedHashMap<>();
    private int currentStrokes;
    private int consecutiveTimeouts;
    private boolean finishedRound;

    public int currentStrokes() {
        return currentStrokes;
    }

    public void addStrokes(int count) {
        currentStrokes += count;
    }

    public int consecutiveTimeouts() {
        return consecutiveTimeouts;
    }

    public void recordTimeout() {
        consecutiveTimeouts++;
    }

    /** Any completed putt clears the AFK counter - only unbroken timeouts should cap a hole. */
    public void clearTimeouts() {
        consecutiveTimeouts = 0;
    }

    /** Banks the hole just played. */
    public void completeHole(int holeNumber) {
        strokesByHole.put(holeNumber, currentStrokes);
        currentStrokes = 0;
        consecutiveTimeouts = 0;
    }

    public void finishRound() {
        finishedRound = true;
    }

    public boolean finishedRound() {
        return finishedRound;
    }

    public Map<Integer, Integer> strokesByHole() {
        return Map.copyOf(strokesByHole);
    }

    /** Completed holes only - a hole still in progress would make the running total jump around. */
    public int totalStrokes() {
        return strokesByHole.values().stream().mapToInt(Integer::intValue).sum();
    }

    /**
     * Total including the hole in progress. This is what turn order sorts on: using banked-only
     * totals would let a player who has already taken six shots on the current hole still be
     * treated as the leader.
     */
    public int runningTotal() {
        return totalStrokes() + currentStrokes;
    }

    public Integer strokesFor(int hole) {
        return strokesByHole.get(hole);
    }

    /** Restores a card from a snapshot. */
    public void restore(Map<Integer, Integer> holes, int currentStrokes, int consecutiveTimeouts) {
        strokesByHole.clear();
        strokesByHole.putAll(holes);
        this.currentStrokes = currentStrokes;
        this.consecutiveTimeouts = consecutiveTimeouts;
    }
}
