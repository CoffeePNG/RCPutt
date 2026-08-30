package com.redcoffee.puttputt.util;

/** Immutable double vector. Kept free of Bukkit types so the physics core stays unit-testable. */
public record Vec3(double x, double y, double z) {

    public static final Vec3 ZERO = new Vec3(0, 0, 0);

    public Vec3 add(Vec3 o) {
        return new Vec3(x + o.x, y + o.y, z + o.z);
    }

    public Vec3 add(double dx, double dy, double dz) {
        return new Vec3(x + dx, y + dy, z + dz);
    }

    public Vec3 subtract(Vec3 o) {
        return new Vec3(x - o.x, y - o.y, z - o.z);
    }

    public Vec3 multiply(double s) {
        return new Vec3(x * s, y * s, z * s);
    }

    public Vec3 withX(double nx) {
        return new Vec3(nx, y, z);
    }

    public Vec3 withY(double ny) {
        return new Vec3(x, ny, z);
    }

    public Vec3 withZ(double nz) {
        return new Vec3(x, y, nz);
    }

    public double dot(Vec3 o) {
        return x * o.x + y * o.y + z * o.z;
    }

    /** Horizontal-only direction from this point to another, normalised. Zero if they coincide. */
    public Vec3 horizontalDirectionTo(Vec3 o) {
        return new Vec3(o.x - x, 0.0, o.z - z).normalize();
    }

    public double lengthSquared() {
        return x * x + y * y + z * z;
    }

    public double length() {
        return Math.sqrt(lengthSquared());
    }

    /** Horizontal distance only; the ball never leaves its green plane in v1. */
    public double horizontalDistance(Vec3 o) {
        double dx = x - o.x;
        double dz = z - o.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    public Vec3 normalize() {
        double len = length();
        return len < 1.0e-9 ? ZERO : multiply(1.0 / len);
    }

    /** Scales the vector down so its length never exceeds {@code max}. Direction is preserved. */
    public Vec3 clampLength(double max) {
        double lenSq = lengthSquared();
        if (lenSq <= max * max) {
            return this;
        }
        return multiply(max / Math.sqrt(lenSq));
    }

    public int blockX() {
        return (int) Math.floor(x);
    }

    public int blockY() {
        return (int) Math.floor(y);
    }

    public int blockZ() {
        return (int) Math.floor(z);
    }
}
