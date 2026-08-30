package com.redcoffee.puttputt.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.redcoffee.puttputt.RCPuttPuttPlugin;
import com.redcoffee.puttputt.course.Course;
import com.redcoffee.puttputt.course.Hole;
import com.redcoffee.puttputt.game.Round;
import com.redcoffee.puttputt.game.RoundManager;
import com.redcoffee.puttputt.game.Scorecard;
import com.redcoffee.puttputt.storage.LeaderboardEntry;
import com.redcoffee.puttputt.util.Vec3;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * The whole {@code /puttputt} tree, built on Paper's Brigadier API.
 *
 * <p>Admin subcommands edit a course the admin has "selected" ({@code admin create} or
 * {@code admin select}), which is why {@code settee 3} needs no course argument - it matches how
 * someone actually builds a course: pick it once, then walk the holes.
 */
@SuppressWarnings("UnstableApiUsage")
public final class PuttPuttCommand {

    private static final String PLAY_PERMISSION = "rcputtputt.play";
    private static final String ADMIN_PERMISSION = "rcputtputt.admin";

    private final RCPuttPuttPlugin plugin;
    private final Map<UUID, BuilderSession> sessions = new HashMap<>();

    public PuttPuttCommand(RCPuttPuttPlugin plugin) {
        this.plugin = plugin;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("puttputt")
                .requires(source -> source.getSender().hasPermission(PLAY_PERMISSION))
                .then(Commands.literal("start")
                        .then(Commands.argument("course", StringArgumentType.word())
                                .suggests(courseSuggestions())
                                .executes(this::start)))
                .then(Commands.literal("leave").executes(this::leave))
                .then(Commands.literal("scorecard").executes(this::scorecard))
                .then(Commands.literal("courses").executes(this::courses))
                .then(Commands.literal("finish").executes(this::finish))
                .then(Commands.literal("leaderboard")
                        .then(Commands.argument("course", StringArgumentType.word())
                                .suggests(courseSuggestions())
                                .executes(this::leaderboard)))
                .then(adminTree())
                .build();
    }

    // ------------------------------------------------------------------ player commands

    private int start(CommandContext<CommandSourceStack> context) {
        Player player = requirePlayer(context);
        if (player == null) {
            return 0;
        }
        String courseId = StringArgumentType.getString(context, "course");
        Course course = plugin.courses().course(courseId).orElse(null);
        if (course == null) {
            plugin.messages().send(player, "course.unknown", "course", courseId);
            return 0;
        }
        if (plugin.rounds().roundOf(player.getUniqueId()).isPresent()) {
            plugin.messages().send(player, "round.already-playing");
            return 0;
        }
        RoundManager.StartResult result = plugin.rounds().startRound(player, course);
        if (result instanceof RoundManager.StartResult.Failed failed) {
            plugin.messages().send(player, failed.messageKey(), failed.placeholders());
            return 0;
        }
        Round round = ((RoundManager.StartResult.Started) result).round();
        for (UUID memberId : round.players()) {
            Player member = plugin.getServer().getPlayer(memberId);
            if (member != null) {
                plugin.messages().send(member, "round.started",
                        "course", course.displayComponent(),
                        "holes", String.valueOf(course.holeCount()),
                        "par", String.valueOf(course.totalPar()));
            }
        }
        return 1;
    }

    private int leave(CommandContext<CommandSourceStack> context) {
        Player player = requirePlayer(context);
        if (player == null) {
            return 0;
        }
        if (!plugin.rounds().leave(player)) {
            plugin.messages().send(player, "round.not-playing");
            return 0;
        }
        return 1;
    }

    private int finish(CommandContext<CommandSourceStack> context) {
        Player player = requirePlayer(context);
        if (player == null) {
            return 0;
        }
        Round round = plugin.rounds().roundOf(player.getUniqueId()).orElse(null);
        if (round == null) {
            plugin.messages().send(player, "round.not-playing");
            return 0;
        }
        // Only the party leader may end a round for everyone.
        if (!plugin.parties().partyOf(player).isLeader(player.getUniqueId())) {
            plugin.messages().send(player, "round.not-leader");
            return 0;
        }
        plugin.rounds().closeRound(round, true);
        return 1;
    }

    private int scorecard(CommandContext<CommandSourceStack> context) {
        Player player = requirePlayer(context);
        if (player == null) {
            return 0;
        }
        Round round = plugin.rounds().roundOf(player.getUniqueId()).orElse(null);
        if (round == null) {
            plugin.messages().send(player, "round.not-playing");
            return 0;
        }
        Scorecard card = round.scorecard(player.getUniqueId());
        plugin.messages().send(player, "scorecard.header", "course", round.course().displayComponent());
        for (Hole hole : round.course().holes()) {
            Integer strokes = card.strokesFor(hole.number());
            String value = strokes != null
                    ? String.valueOf(strokes)
                    : (hole.number() == card.currentHole() ? card.currentStrokes() + "*" : "-");
            plugin.messages().send(player, "scorecard.row",
                    "hole", String.valueOf(hole.number()),
                    "par", String.valueOf(hole.par()),
                    "strokes", value);
        }
        plugin.messages().send(player, "scorecard.total",
                "strokes", String.valueOf(card.totalStrokes()),
                "diff", RoundManager.formatDiff(round.parDiffFor(player.getUniqueId())));
        return 1;
    }

    private int courses(CommandContext<CommandSourceStack> context) {
        var sender = context.getSource().getSender();
        List<Course> playable = plugin.courses().courses().stream().filter(Course::isPlayable).toList();
        if (playable.isEmpty()) {
            sender.sendMessage(plugin.messages().prefixed("course.none"));
            return 0;
        }
        sender.sendMessage(plugin.messages().prefixed("course.list-header"));
        for (Course course : playable) {
            sender.sendMessage(plugin.messages().render("course.list-row",
                    "id", course.id(),
                    "display", course.displayComponent(),
                    "holes", String.valueOf(course.holeCount()),
                    "par", String.valueOf(course.totalPar())));
        }
        return 1;
    }

    private int leaderboard(CommandContext<CommandSourceStack> context) {
        var sender = context.getSource().getSender();
        String courseId = StringArgumentType.getString(context, "course");
        Course course = plugin.courses().course(courseId).orElse(null);
        if (course == null) {
            sender.sendMessage(plugin.messages().prefixed("course.unknown", "course", courseId));
            return 0;
        }
        // Read off-thread, render back on the main thread.
        plugin.queryStorage(
                dao -> dao.leaderboard(course.id(), plugin.config().leaderboardSize()),
                entries -> {
                    if (entries.isEmpty()) {
                        sender.sendMessage(plugin.messages().prefixed("leaderboard.empty",
                                "course", course.displayComponent()));
                        return;
                    }
                    sender.sendMessage(plugin.messages().prefixed("leaderboard.header",
                            "course", course.displayComponent()));
                    int rank = 1;
                    for (LeaderboardEntry entry : entries) {
                        sender.sendMessage(plugin.messages().render("leaderboard.row",
                                "rank", String.valueOf(rank++),
                                "player", entry.playerName(),
                                "strokes", String.valueOf(entry.totalStrokes()),
                                "diff", RoundManager.formatDiff(entry.parDiff())));
                    }
                },
                error -> sender.sendMessage(plugin.messages().prefixed("leaderboard.error")));
        return 1;
    }

    // ------------------------------------------------------------------ admin commands

    private LiteralArgumentBuilder<CommandSourceStack> adminTree() {
        return Commands.literal("admin")
                .requires(source -> source.getSender().hasPermission(ADMIN_PERMISSION))
                .then(Commands.literal("create")
                        .then(Commands.argument("course", StringArgumentType.word())
                                .executes(this::adminCreate)))
                .then(Commands.literal("select")
                        .then(Commands.argument("course", StringArgumentType.word())
                                .suggests(courseSuggestions())
                                .executes(this::adminSelect)))
                .then(Commands.literal("settee")
                        .then(Commands.argument("hole", IntegerArgumentType.integer(1))
                                .executes(this::adminSetTee)))
                .then(Commands.literal("setcup")
                        .then(Commands.argument("hole", IntegerArgumentType.integer(1))
                                .executes(this::adminSetCup)))
                .then(Commands.literal("setpar")
                        .then(Commands.argument("hole", IntegerArgumentType.integer(1))
                                .then(Commands.argument("par", IntegerArgumentType.integer(1, 20))
                                        .executes(this::adminSetPar))))
                .then(Commands.literal("pos1").executes(context -> adminCorner(context, true)))
                .then(Commands.literal("pos2").executes(context -> adminCorner(context, false)))
                .then(Commands.literal("setbounds")
                        .then(Commands.argument("hole", IntegerArgumentType.integer(1))
                                .executes(this::adminSetBounds)))
                .then(Commands.literal("delhole")
                        .then(Commands.argument("hole", IntegerArgumentType.integer(1))
                                .executes(this::adminDeleteHole)))
                .then(Commands.literal("surface")
                        .then(Commands.argument("material", StringArgumentType.word())
                                .suggests(materialSuggestions())
                                .then(Commands.argument("surface", StringArgumentType.word())
                                        .suggests(surfaceSuggestions())
                                        .executes(context -> adminSurface(context, false))
                                        .then(Commands.argument("hole", IntegerArgumentType.integer(1))
                                                .executes(context -> adminSurface(context, true))))))
                .then(Commands.literal("tphole")
                        .then(Commands.argument("course", StringArgumentType.word())
                                .suggests(courseSuggestions())
                                .then(Commands.argument("hole", IntegerArgumentType.integer(1))
                                        .executes(this::adminTpHole))))
                .then(Commands.literal("info").executes(this::adminInfo))
                .then(Commands.literal("save").executes(this::adminSave))
                .then(Commands.literal("reload").executes(this::adminReload))
                .then(Commands.literal("delete")
                        .then(Commands.argument("course", StringArgumentType.word())
                                .suggests(courseSuggestions())
                                .executes(this::adminDelete)));
    }

    private int adminCreate(CommandContext<CommandSourceStack> context) {
        Player player = requirePlayer(context);
        if (player == null) {
            return 0;
        }
        String courseId = StringArgumentType.getString(context, "course").toLowerCase(Locale.ROOT);
        if (plugin.courses().exists(courseId)) {
            plugin.messages().send(player, "admin.course-exists", "course", courseId);
            return 0;
        }
        Course course = plugin.courses().create(courseId, player.getWorld().getName());
        session(player).selectCourse(course.id());
        plugin.messages().send(player, "admin.course-created",
                "course", course.id(), "world", course.world());
        return 1;
    }

    private int adminSelect(CommandContext<CommandSourceStack> context) {
        Player player = requirePlayer(context);
        if (player == null) {
            return 0;
        }
        String courseId = StringArgumentType.getString(context, "course");
        Course course = plugin.courses().course(courseId).orElse(null);
        if (course == null) {
            plugin.messages().send(player, "course.unknown", "course", courseId);
            return 0;
        }
        session(player).selectCourse(course.id());
        plugin.messages().send(player, "admin.course-selected", "course", course.id());
        return 1;
    }

    private int adminSetTee(CommandContext<CommandSourceStack> context) {
        return editHole(context, (player, course, hole) -> {
            hole.setTee(feetOf(player.getLocation()));
            plugin.messages().send(player, "admin.tee-set", "hole", String.valueOf(hole.number()));
        });
    }

    private int adminSetCup(CommandContext<CommandSourceStack> context) {
        return editHole(context, (player, course, hole) -> {
            hole.setCup(feetOf(player.getLocation()));
            plugin.messages().send(player, "admin.cup-set", "hole", String.valueOf(hole.number()));
        });
    }

    private int adminSetPar(CommandContext<CommandSourceStack> context) {
        int par = IntegerArgumentType.getInteger(context, "par");
        return editHole(context, (player, course, hole) -> {
            hole.setPar(par);
            plugin.messages().send(player, "admin.par-set",
                    "hole", String.valueOf(hole.number()), "par", String.valueOf(par));
        });
    }

    private int adminCorner(CommandContext<CommandSourceStack> context, boolean first) {
        Player player = requirePlayer(context);
        if (player == null) {
            return 0;
        }
        Vec3 corner = feetOf(player.getLocation());
        BuilderSession session = session(player);
        if (first) {
            session.setCorner1(corner);
        } else {
            session.setCorner2(corner);
        }
        plugin.messages().send(player, "admin.corner-set",
                "corner", first ? "1" : "2",
                "x", String.valueOf(corner.blockX()),
                "y", String.valueOf(corner.blockY()),
                "z", String.valueOf(corner.blockZ()));
        return 1;
    }

    private int adminSetBounds(CommandContext<CommandSourceStack> context) {
        return editHole(context, (player, course, hole) -> {
            BuilderSession session = session(player);
            if (!session.hasBothCorners()) {
                plugin.messages().send(player, "admin.no-selection");
                return;
            }
            hole.setBounds(session.toBounds());
            plugin.messages().send(player, "admin.bounds-set", "hole", String.valueOf(hole.number()));
        });
    }

    private int adminDeleteHole(CommandContext<CommandSourceStack> context) {
        Player player = requirePlayer(context);
        if (player == null) {
            return 0;
        }
        Course course = selectedCourse(player);
        if (course == null) {
            return 0;
        }
        int number = IntegerArgumentType.getInteger(context, "hole");
        if (!course.removeHole(number)) {
            plugin.messages().send(player, "admin.no-such-hole", "hole", String.valueOf(number));
            return 0;
        }
        plugin.messages().send(player, "admin.hole-deleted", "hole", String.valueOf(number));
        return 1;
    }

    private int adminSurface(CommandContext<CommandSourceStack> context, boolean perHole) {
        Player player = requirePlayer(context);
        if (player == null) {
            return 0;
        }
        String materialName = StringArgumentType.getString(context, "material").toUpperCase(Locale.ROOT);
        String surfaceId = StringArgumentType.getString(context, "surface");
        if (Material.matchMaterial(materialName) == null) {
            plugin.messages().send(player, "admin.unknown-material", "material", materialName);
            return 0;
        }
        if (plugin.config().surfaces().byId(surfaceId) == null) {
            plugin.messages().send(player, "admin.unknown-surface", "surface", surfaceId);
            return 0;
        }
        if (!perHole) {
            // Global mapping: written to config.yml so it survives a restart, not just this session.
            plugin.getConfig().set("material_map." + materialName, surfaceId);
            plugin.saveConfig();
            plugin.config().surfaces().mapMaterial(materialName, surfaceId);
            plugin.messages().send(player, "admin.surface-global",
                    "material", materialName, "surface", surfaceId);
            return 1;
        }
        Course course = selectedCourse(player);
        if (course == null) {
            return 0;
        }
        int number = IntegerArgumentType.getInteger(context, "hole");
        Hole hole = course.holeOrCreate(number);
        hole.materialOverrides().put(materialName, surfaceId.toUpperCase(Locale.ROOT));
        plugin.messages().send(player, "admin.surface-hole",
                "material", materialName, "surface", surfaceId, "hole", String.valueOf(number));
        return 1;
    }

    private int adminTpHole(CommandContext<CommandSourceStack> context) {
        Player player = requirePlayer(context);
        if (player == null) {
            return 0;
        }
        String courseId = StringArgumentType.getString(context, "course");
        int number = IntegerArgumentType.getInteger(context, "hole");
        Course course = plugin.courses().course(courseId).orElse(null);
        if (course == null) {
            plugin.messages().send(player, "course.unknown", "course", courseId);
            return 0;
        }
        Optional<Hole> hole = course.hole(number);
        if (hole.isEmpty() || hole.get().tee() == null) {
            plugin.messages().send(player, "admin.no-such-hole", "hole", String.valueOf(number));
            return 0;
        }
        var world = plugin.getServer().getWorld(course.world());
        if (world == null) {
            plugin.messages().send(player, "round.world-missing", "world", String.valueOf(course.world()));
            return 0;
        }
        Vec3 tee = hole.get().tee();
        player.teleport(new Location(world, tee.x(), tee.y(), tee.z()));
        session(player).selectCourse(course.id());
        return 1;
    }

    private int adminInfo(CommandContext<CommandSourceStack> context) {
        var sender = context.getSource().getSender();
        sender.sendMessage(plugin.messages().prefixed("admin.info",
                "parties", plugin.parties().name(),
                "courses", String.valueOf(plugin.courses().courses().size()),
                "rounds", String.valueOf(plugin.rounds().activeRounds()),
                "economy", String.valueOf(plugin.config().economyEnabled())));
        return 1;
    }

    private int adminSave(CommandContext<CommandSourceStack> context) {
        var sender = context.getSource().getSender();
        try {
            plugin.courses().saveAll();
            sender.sendMessage(plugin.messages().prefixed("admin.saved"));
            return 1;
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not save courses", ex);
            sender.sendMessage(plugin.messages().prefixed("admin.save-failed"));
            return 0;
        }
    }

    private int adminReload(CommandContext<CommandSourceStack> context) {
        plugin.reloadEverything();
        context.getSource().getSender().sendMessage(plugin.messages().prefixed("admin.reloaded"));
        return 1;
    }

    private int adminDelete(CommandContext<CommandSourceStack> context) {
        var sender = context.getSource().getSender();
        String courseId = StringArgumentType.getString(context, "course");
        // Refuse while anyone is on it - deleting a course out from under a live round would strand players.
        boolean inUse = plugin.courses().course(courseId)
                .map(course -> plugin.rounds().activeRounds() > 0 && isCourseInUse(course))
                .orElse(false);
        if (inUse) {
            sender.sendMessage(plugin.messages().prefixed("admin.course-in-use", "course", courseId));
            return 0;
        }
        if (!plugin.courses().delete(courseId)) {
            sender.sendMessage(plugin.messages().prefixed("course.unknown", "course", courseId));
            return 0;
        }
        sender.sendMessage(plugin.messages().prefixed("admin.course-deleted", "course", courseId));
        return 1;
    }

    private boolean isCourseInUse(Course course) {
        return plugin.getServer().getOnlinePlayers().stream()
                .map(player -> plugin.rounds().roundOf(player.getUniqueId()))
                .flatMap(Optional::stream)
                .anyMatch(round -> round.course().id().equals(course.id()));
    }

    // ------------------------------------------------------------------ helpers

    /** Shared shape for the "edit a hole on the selected course" admin commands. */
    @FunctionalInterface
    private interface HoleEdit {
        void apply(Player player, Course course, Hole hole);
    }

    private int editHole(CommandContext<CommandSourceStack> context, HoleEdit edit) {
        Player player = requirePlayer(context);
        if (player == null) {
            return 0;
        }
        Course course = selectedCourse(player);
        if (course == null) {
            return 0;
        }
        int number = IntegerArgumentType.getInteger(context, "hole");
        edit.apply(player, course, course.holeOrCreate(number));
        return 1;
    }

    private Course selectedCourse(Player player) {
        String courseId = session(player).courseId();
        if (courseId == null) {
            plugin.messages().send(player, "admin.no-course-selected");
            return null;
        }
        Course course = plugin.courses().course(courseId).orElse(null);
        if (course == null) {
            plugin.messages().send(player, "course.unknown", "course", courseId);
        }
        return course;
    }

    private BuilderSession session(Player player) {
        return sessions.computeIfAbsent(player.getUniqueId(), id -> new BuilderSession());
    }

    private Player requirePlayer(CommandContext<CommandSourceStack> context) {
        if (context.getSource().getSender() instanceof Player player) {
            return player;
        }
        context.getSource().getSender().sendMessage(Component.text("This command is for players."));
        return null;
    }

    /**
     * Ball position for a location: the player's feet, which is the top face of the block they are
     * standing on - exactly the plane the physics expects a ball to roll along.
     */
    private static Vec3 feetOf(Location location) {
        return new Vec3(
                Math.floor(location.getX()) + 0.5,
                location.getY(),
                Math.floor(location.getZ()) + 0.5);
    }

    private SuggestionProvider<CommandSourceStack> courseSuggestions() {
        return (context, builder) -> {
            String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
            plugin.courses().courseIds().stream()
                    .filter(id -> id.toLowerCase(Locale.ROOT).startsWith(remaining))
                    .forEach(builder::suggest);
            return builder.buildFuture();
        };
    }

    private SuggestionProvider<CommandSourceStack> surfaceSuggestions() {
        return (context, builder) -> {
            String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
            plugin.config().surfaces().surfaces().values().stream()
                    .map(surface -> surface.id())
                    .filter(id -> id.toLowerCase(Locale.ROOT).startsWith(remaining))
                    .sorted()
                    .forEach(builder::suggest);
            return builder.buildFuture();
        };
    }

    private SuggestionProvider<CommandSourceStack> materialSuggestions() {
        return (context, builder) -> {
            String remaining = builder.getRemaining().toUpperCase(Locale.ROOT);
            java.util.Arrays.stream(Material.values())
                    .filter(Material::isBlock)
                    .map(Enum::name)
                    .filter(name -> name.startsWith(remaining))
                    .limit(60)
                    .forEach(builder::suggest);
            return builder.buildFuture();
        };
    }
}
