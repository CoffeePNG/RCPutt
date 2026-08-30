package com.redcoffee.puttputt.game;

/** Finds the teleport pad on a block cell, if there is one. */
@FunctionalInterface
public interface TeleportLookup {

    TeleportLookup NONE = (x, y, z) -> null;

    /** The pad anchored at this cell, or null. */
    Teleport at(int x, int y, int z);
}
