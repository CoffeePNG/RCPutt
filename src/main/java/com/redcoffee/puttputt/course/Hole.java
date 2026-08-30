package com.redcoffee.puttputt.course;

import com.redcoffee.puttputt.util.Bounds;
import com.redcoffee.puttputt.util.Vec3;
import java.util.HashMap;
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

    /** A hole is only playable once it has both ends and an area to play them in. */
    public boolean isPlayable() {
        return tee != null && cup != null && bounds != null;
    }
}
