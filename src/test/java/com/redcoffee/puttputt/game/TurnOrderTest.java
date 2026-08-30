package com.redcoffee.puttputt.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.redcoffee.puttputt.config.TurnOrderMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The ordering rule is what shapes the whole game, so it is tested on its own. */
class TurnOrderTest {

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID carol = UUID.randomUUID();

    private Map<UUID, Integer> totals(int aliceTotal, int bobTotal, int carolTotal) {
        Map<UUID, Integer> totals = new LinkedHashMap<>();
        totals.put(alice, aliceTotal);
        totals.put(bob, bobTotal);
        totals.put(carol, carolTotal);
        return totals;
    }

    /**
     * The headline rule: lowest total putts FIRST. This is deliberately a penalty - the leader
     * reads the green for everyone else with no information of their own.
     */
    @Test
    void ascendingPutsTheLeaderFirstToPunishThem() {
        List<UUID> previous = List.of(alice, bob, carol);

        List<UUID> order = TurnOrder.nextHole(previous, totals(9, 4, 7), TurnOrderMode.ASCENDING);

        assertEquals(List.of(bob, carol, alice), order,
                "bob leads on 4 strokes, so bob is punished with the first putt");
    }

    @Test
    void descendingRewardsTheLeaderInstead() {
        List<UUID> previous = List.of(alice, bob, carol);

        List<UUID> order = TurnOrder.nextHole(previous, totals(9, 4, 7), TurnOrderMode.DESCENDING);

        assertEquals(List.of(alice, carol, bob), order, "the player furthest behind putts first");
    }

    /** Ties must not re-randomise mid-round, or the order would churn between holes. */
    @Test
    void tiesKeepThePreviousHolesOrder() {
        List<UUID> previous = List.of(carol, alice, bob);

        List<UUID> order = TurnOrder.nextHole(previous, totals(5, 5, 5), TurnOrderMode.ASCENDING);

        assertEquals(previous, order);
    }

    @Test
    void aPlayerWithNoRecordedTotalIsTreatedAsZero() {
        List<UUID> previous = List.of(alice, bob);
        Map<UUID, Integer> partial = new LinkedHashMap<>();
        partial.put(alice, 6);

        List<UUID> order = TurnOrder.nextHole(previous, partial, TurnOrderMode.ASCENDING);

        assertEquals(List.of(bob, alice), order);
    }

    @Test
    void initialOrderIsAPermutationOfTheParty() {
        List<UUID> players = List.of(alice, bob, carol);

        List<UUID> order = TurnOrder.initial(players, new Random(42));

        assertEquals(3, order.size());
        assertTrue(order.containsAll(players), "everyone must appear exactly once");
    }

    /** Same seed, same order - which is what lets a resumed round rebuild the hole-1 order. */
    @Test
    void initialOrderIsDeterministicForAGivenSeed() {
        List<UUID> players = List.of(alice, bob, carol);

        assertEquals(TurnOrder.initial(players, new Random(7)), TurnOrder.initial(players, new Random(7)));
    }
}
