package com.redcoffee.puttputt.game;

import com.redcoffee.puttputt.config.TurnOrderMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.ToIntFunction;

/**
 * Derives the order players putt in (RC-SPEC-PUTTPUTT-001 v2 s3.1).
 *
 * <p>Kept as pure static functions over ids and totals so the ordering rule - the part that
 * actually shapes the game - can be tested without a round, a party or a server.
 */
public final class TurnOrder {

    private TurnOrder() {
    }

    /** Hole 1 has no totals to sort on, so it is random and then fixed for that hole. */
    public static List<UUID> initial(Collection<UUID> players, Random random) {
        List<UUID> order = new ArrayList<>(players);
        Collections.shuffle(order, random);
        return List.copyOf(order);
    }

    /**
     * Order for every hole after the first.
     *
     * <p>In {@link TurnOrderMode#ASCENDING} - the default - the player with the <em>lowest</em>
     * total putts first. That is deliberately a penalty: they read the green for everyone else with
     * no information of their own, which hands trailing players a small edge and keeps rounds
     * close. Ties keep the previous hole's order, so the tiebreak never re-randomises mid-round.
     *
     * @param previousOrder the order used on the hole just finished, used as the tiebreak
     * @param totalStrokes  running totals per player
     */
    public static List<UUID> nextHole(List<UUID> previousOrder, Map<UUID, Integer> totalStrokes, TurnOrderMode mode) {
        Map<UUID, Integer> previousIndex = new HashMap<>();
        for (int i = 0; i < previousOrder.size(); i++) {
            previousIndex.put(previousOrder.get(i), i);
        }
        ToIntFunction<UUID> total = id -> totalStrokes.getOrDefault(id, 0);

        Comparator<UUID> byTotal = Comparator.comparingInt(total);
        if (mode == TurnOrderMode.DESCENDING) {
            byTotal = byTotal.reversed();
        }
        Comparator<UUID> comparator = byTotal.thenComparingInt(
                id -> previousIndex.getOrDefault(id, Integer.MAX_VALUE));

        List<UUID> order = new ArrayList<>(previousOrder);
        order.sort(comparator);
        return List.copyOf(order);
    }
}
