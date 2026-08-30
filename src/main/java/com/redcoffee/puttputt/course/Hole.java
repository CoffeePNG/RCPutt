package com.redcoffee.puttputt.course;

import com.redcoffee.puttputt.game.Teleport;
import com.redcoffee.puttputt.game.TeleportLookup;
import com.redcoffee.puttputt.util.Bounds;
import com.redcoffee.puttputt.util.Vec3;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One hole. Mutable because the in-world builder edits holes a command at a time, and a
 * half-configured hole has to survive between {@code /puttputt admin settee} and {@code setcup}.
 */
public final class Hole {

    private final int number;
    private int par = 3;
    private Vec3 tee;
    private Vec3 cup;
    private Bounds bounds;
    private final Map<String, String> materialOverrides = new HashMap<>();
    /** Pads keyed by the block cell you roll onto. */
    private final Map<String, Teleport> teleports = new LinkedHashMap<>();

    public Hole(int number) {
        this.number = number;
    }

    public int number() {
        return number;
    }

    public int par() {
        return par;
    }

    public void setPar(int par) {
        this.par = par;
    }

    public Vec3 tee() {
        return tee;
    }

    public void setTee(Vec3 tee) {
        this.tee = tee;
    }

    public Vec3 cup() {
        return cup;
    }

    public void setCup(Vec3 cup) {
        this.cup = cup;
    }

    public Bounds bounds() {
        return bounds;
    }

    public void setBounds(Bounds bounds) {
        this.bounds = bounds;
    }

    public Map<String, String> materialOverrides() {
        return materialOverrides;
    }

    /** Adds a pad: rolling onto {@code from} moves the ball to {@code to}. */
    public void addTeleport(int fromX, int fromY, int fromZ, Vec3 to, boolean keepVelocity) {
        teleports.put(key(fromX, fromY, fromZ), new Teleport(to, keepVelocity));
    }

    public void clearTeleports() {
        teleports.clear();
    }

    public int teleportCount() {
        return teleports.size();
    }

    /** Pads as (cell, teleport) pairs, for serialisation. */
    public List<Map.Entry<int[], Teleport>> teleportEntries() {
        List<Map.Entry<int[], Teleport>> out = new ArrayList<>();
        teleports.forEach((cell, teleport) -> {
            String[] parts = cell.split(",");
            out.add(Map.entry(new int[]{
                    Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])},
                    teleport));
        });
        return out;
    }

    public TeleportLookup teleportLookup() {
        return teleports.isEmpty() ? TeleportLookup.NONE : (x, y, z) -> teleports.get(key(x, y, z));
    }

    private static String key(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    /** A hole is only playable once it has both ends and an area to play them in. */
    public boolean isPlayable() {
        return tee != null && cup != null && bounds != null;
    }
}
