package com.redcoffee.puttputt.course;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The preview is only worth drawing if its outline is the outline the fill actually saw, so the
 * edge test has to match the fill's own four-way neighbourhood.
 */
class FillVisualizerTest {

    private static Set<String> region(String... rows) {
        Set<String> filled = new HashSet<>();
        for (int z = 0; z < rows.length; z++) {
            for (int x = 0; x < rows[z].length(); x++) {
                if (rows[z].charAt(x) == '.') {
                    filled.add(x + ":" + z);
                }
            }
        }
        return filled;
    }

    @Test
    void theMiddleOfAFairwayIsNotEdge() {
        Set<String> filled = region(
                "...",
                "...",
                "...");
        assertFalse(FillVisualizer.isPerimeter(filled, 1, 1), "fully surrounded");
    }

    @Test
    void aCellAgainstTheWallIsEdge() {
        Set<String> filled = region(
                "...",
                "...",
                "...");
        assertTrue(FillVisualizer.isPerimeter(filled, 0, 1), "nothing to the west");
        assertTrue(FillVisualizer.isPerimeter(filled, 1, 0), "nothing to the north");
    }

    /**
     * A diagonal neighbour must not count as cover: the fill would not have squeezed through there,
     * so the preview must still draw this as an edge.
     */
    @Test
    void aDiagonalNeighbourDoesNotCoverACell() {
        Set<String> filled = region(
                ".#",
                "#.");
        assertTrue(FillVisualizer.isPerimeter(filled, 0, 0));
        assertTrue(FillVisualizer.isPerimeter(filled, 1, 1));
    }
}
