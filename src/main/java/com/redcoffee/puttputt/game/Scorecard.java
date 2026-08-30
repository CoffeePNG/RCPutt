package com.redcoffee.puttputt.game;

import java.util.LinkedHashMap;
import java.util.Map;

/** Per-player stroke record for one round. */
public final class Scorecard {

    private final Map<Integer, Integer> strokesByHole = new LinkedHashMap<>();
    private int currentHole;
    private int currentStrokes;
    private boolean finished;

    public Scorecard(int firstHole) {
        this.currentHole = firstHole;
    }

    public int currentHole() {
        return currentHole;
    }

    public int currentStrokes() {
        return currentStrokes;
    }

    public boolean finished() {
        return finished;
    }

    public void addStrokes(int count) {
        currentStrokes += count;
    }

    /** Banks the current hole and moves on. */
    public void completeHole(int nextHole) {
        strokesByHole.put(currentHole, currentStrokes);
        currentStrokes = 0;
        currentHole = nextHole;
    }

    /** Banks the final hole; no hole follows. */
    public void finish() {
        strokesByHole.put(currentHole, currentStrokes);
        currentStrokes = 0;
        finished = true;
    }

    public Map<Integer, Integer> strokesByHole() {
        return Map.copyOf(strokesByHole);
    }

    /** Completed holes only - a hole still in progress would make the running total jump around. */
    public int totalStrokes() {
        return strokesByHole.values().stream().mapToInt(Integer::intValue).sum();
    }

    public Integer strokesFor(int hole) {
        return strokesByHole.get(hole);
    }
}
