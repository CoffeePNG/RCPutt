package com.redcoffee.puttputt.snapshot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.redcoffee.puttputt.game.Ball;
import com.redcoffee.puttputt.game.Round;
import com.redcoffee.puttputt.game.Scorecard;
import com.redcoffee.puttputt.game.TurnState;
import com.redcoffee.puttputt.util.Vec3;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A point-in-time copy of a live round, written to SQLite so a crash or restart does not lose one
 * (RC-SPEC-PUTTPUTT-001 v2 s9).
 *
 * <p>Gson comes from the server's own classpath rather than a bundled copy - Paper ships it, so
 * shading a second one would be waste. Only what is needed to put players back where they were is
 * stored: scorecards, order, whose turn it is, the hole, and where each ball came to rest.
 */
public final class RoundSnapshot {

    private static final Gson GSON = new GsonBuilder().create();

    private String courseId;
    private int currentHole;
    private List<String> turnOrder = new ArrayList<>();
    private int turnIndex;
    private String currentPlayer;
    private List<String> finishedThisHole = new ArrayList<>();
    private Map<String, CardData> cards = new LinkedHashMap<>();
    private Map<String, double[]> balls = new LinkedHashMap<>();

    /** Per-player card data. A nested class keeps the JSON readable when someone opens the row. */
    static final class CardData {
        Map<String, Integer> holes = new LinkedHashMap<>();
        int currentStrokes;
        int consecutiveTimeouts;
    }

    public static RoundSnapshot capture(Round round, TurnState state) {
        RoundSnapshot snapshot = new RoundSnapshot();
        snapshot.courseId = round.course().id();
        snapshot.currentHole = round.currentHoleNumber();
        snapshot.turnIndex = round.turnIndex();
        snapshot.turnOrder = round.turnOrder().stream().map(UUID::toString).toList();
        snapshot.currentPlayer = state == null || state.currentPlayer() == null
                ? null : state.currentPlayer().toString();
        snapshot.finishedThisHole = round.finishedThisHole().stream().map(UUID::toString).toList();

        for (Map.Entry<UUID, Scorecard> entry : round.scorecards().entrySet()) {
            Scorecard card = entry.getValue();
            CardData data = new CardData();
            card.strokesByHole().forEach((hole, strokes) -> data.holes.put(String.valueOf(hole), strokes));
            data.currentStrokes = card.currentStrokes();
            data.consecutiveTimeouts = card.consecutiveTimeouts();
            snapshot.cards.put(entry.getKey().toString(), data);
        }
        for (Map.Entry<UUID, Ball> entry : round.balls().entrySet()) {
            Vec3 at = entry.getValue().state().position();
            snapshot.balls.put(entry.getKey().toString(), new double[]{at.x(), at.y(), at.z()});
        }
        return snapshot;
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    /** Returns null rather than throwing: a corrupt snapshot should archive the round, not crash. */
    public static RoundSnapshot fromJson(String json) {
        try {
            return GSON.fromJson(json, RoundSnapshot.class);
        } catch (JsonSyntaxException ex) {
            return null;
        }
    }

    public String courseId() {
        return courseId;
    }

    public int currentHole() {
        return currentHole;
    }

    public List<UUID> memberIds() {
        return cards.keySet().stream().map(RoundSnapshot::parse).filter(java.util.Objects::nonNull).toList();
    }

    public Map<UUID, Vec3> ballPositions() {
        Map<UUID, Vec3> out = new LinkedHashMap<>();
        balls.forEach((id, xyz) -> {
            UUID uuid = parse(id);
            if (uuid != null && xyz != null && xyz.length == 3) {
                out.put(uuid, new Vec3(xyz[0], xyz[1], xyz[2]));
            }
        });
        return out;
    }

    /** Pours the snapshot back into a freshly constructed round. */
    public void applyTo(Round round) {
        round.setCurrentHoleNumber(currentHole);
        List<UUID> order = turnOrder.stream().map(RoundSnapshot::parse)
                .filter(java.util.Objects::nonNull)
                .filter(round::contains)
                .toList();
        if (!order.isEmpty()) {
            round.setTurnOrder(order);
        }
        round.setTurnIndex(Math.max(0, Math.min(turnIndex, Math.max(0, order.size() - 1))));
        for (String raw : finishedThisHole) {
            UUID id = parse(raw);
            if (id != null && round.contains(id)) {
                round.markFinishedThisHole(id);
            }
        }
        cards.forEach((raw, data) -> {
            UUID id = parse(raw);
            Scorecard card = id == null ? null : round.scorecard(id);
            if (card == null) {
                return;
            }
            Map<Integer, Integer> holes = new LinkedHashMap<>();
            data.holes.forEach((hole, strokes) -> {
                try {
                    holes.put(Integer.parseInt(hole), strokes);
                } catch (NumberFormatException ignored) {
                    // Skip a corrupt hole key rather than losing the whole card.
                }
            });
            card.restore(holes, data.currentStrokes, data.consecutiveTimeouts);
        });
    }

    /** Party member list stored on the round row, used to match a resume against who reconnects. */
    public static String encodeMembers(List<UUID> members) {
        return GSON.toJson(members.stream().map(UUID::toString).toList());
    }

    public static List<UUID> decodeMembers(String json) {
        try {
            String[] raw = GSON.fromJson(json, String[].class);
            if (raw == null) {
                return List.of();
            }
            List<UUID> out = new ArrayList<>();
            for (String value : raw) {
                UUID id = parse(value);
                if (id != null) {
                    out.add(id);
                }
            }
            return out;
        } catch (JsonSyntaxException ex) {
            return List.of();
        }
    }

    private static UUID parse(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException | NullPointerException ex) {
            return null;
        }
    }
}
