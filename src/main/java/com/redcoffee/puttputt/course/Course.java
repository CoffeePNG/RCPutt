package com.redcoffee.puttputt.course;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** A course: an ordered set of holes in one world. Serialised to {@code courses/<id>.yml}. */
public final class Course {

    private final String id;
    private String displayName;
    private String world;
    private final List<Hole> holes = new ArrayList<>();

    public Course(String id, String displayName, String world) {
        this.id = id;
        this.displayName = displayName;
        this.world = world;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName == null || displayName.isBlank() ? id : displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String world() {
        return world;
    }

    public void setWorld(String world) {
        this.world = world;
    }

    /** Holes in play order. */
    public List<Hole> holes() {
        holes.sort(Comparator.comparingInt(Hole::number));
        return holes;
    }

    public Optional<Hole> hole(int number) {
        return holes.stream().filter(h -> h.number() == number).findFirst();
    }

    /** Returns the existing hole with this number, creating it if the builder has not yet. */
    public Hole holeOrCreate(int number) {
        return hole(number).orElseGet(() -> {
            Hole created = new Hole(number);
            holes.add(created);
            return created;
        });
    }

    public boolean removeHole(int number) {
        return holes.removeIf(h -> h.number() == number);
    }

    public int holeCount() {
        return holes.size();
    }

    public int totalPar() {
        return holes.stream().mapToInt(Hole::par).sum();
    }

    /** A course with an unfinished hole is rejected at round start rather than mid-round. */
    public List<Integer> unplayableHoles() {
        return holes().stream().filter(h -> !h.isPlayable()).map(Hole::number).toList();
    }

    public boolean isPlayable() {
        return holeCount() > 0 && unplayableHoles().isEmpty();
    }
}
