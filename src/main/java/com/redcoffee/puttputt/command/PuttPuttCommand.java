package com.redcoffee.puttputt.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.redcoffee.puttputt.RCPuttPuttPlugin;
import com.redcoffee.puttputt.course.Course;
import com.redcoffee.puttputt.config.ConfigMigrator;
import com.redcoffee.puttputt.course.BoundsMarkers;
import com.redcoffee.puttputt.course.CourseRegion;
import com.redcoffee.puttputt.course.FillVisualizer;
import com.redcoffee.puttputt.course.WorldRegionScanner;
import com.redcoffee.puttputt.course.Hole;
import com.redcoffee.puttputt.util.Bounds;
import com.redcoffee.puttputt.game.Round;
import com.redcoffee.puttputt.game.RoundManager;
import com.redcoffee.puttputt.game.Scorecard;
import com.redcoffee.puttputt.storage.LeaderboardEntry;
import com.redcoffee.puttputt.util.Vec3;
import org.bukkit.scheduler.BukkitTask;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.io.IOException;
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
    /** One live fill preview per player, so a second /ppa showfill replaces the first. */
    private final java.util.Map<java.util.UUID, BukkitTask> fillPreviews = new java.util.HashMap<>();

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
                .then(adminTree("admin"))
                .build();
    }

    /**
     * {@code /ppa ...} - the admin tree as its own command, so building a course does not mean
     * typing {@code /puttputt admin} in front of every edit.
     */
    public LiteralCommandNode<CommandSourceStack> buildAdmin() {
        return adminTree("ppa").build();
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
                    : (hole.number() == round.currentHoleNumber() ? card.currentStrokes() + "*" : "-");
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

    /**
     * The builder half of the admin tree, hung both under {@code /puttputt admin} and on its own as
     * {@code /ppa}. Brigadier nodes belong to exactly one parent, so the tree is built twice under
     * two names rather than shared - a redirect would make the standalone form still expect the
     * {@code admin} token.
     */
    LiteralArgumentBuilder<CommandSourceStack> adminTree(String name) {
        return Commands.literal(name)
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
                        // No hole argument: apply to whichever hole the wand is editing.
                        .executes(this::adminSetBoundsSelected)
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
                .then(Commands.literal("addtp")
                        .executes(context -> adminAddTeleport(context, true))
                        .then(Commands.literal("stop")
                                .executes(context -> adminAddTeleport(context, false))))
                .then(Commands.literal("cleartp").executes(this::adminClearTeleports))
                .then(Commands.literal("wand").executes(this::adminWand))
                .then(Commands.literal("hole")
                        .then(Commands.argument("hole", IntegerArgumentType.integer(1))
                                .executes(this::adminHole)))
                .then(Commands.literal("showfill")
                        .executes(context -> adminShowFill(context, 15))
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 120))
                                .executes(context -> adminShowFill(context,
                                        IntegerArgumentType.getInteger(context, "seconds")))))
                .then(Commands.literal("check").executes(this::adminCheck))
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
        session(player).selectHole(1);
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
        session(player).selectHole(1);
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
        // The block the admin is standing ON, not their feet plane - see BuilderSession.blockOf.
        var standing = player.getLocation();
        Vec3 corner = BuilderSession.blockOf(standing.getBlockX(),
                standing.getBlockY() - 1, standing.getBlockZ());
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
        return editHole(context, (player, course, hole) -> applyBounds(player, hole));
    }

    private int adminSetBoundsSelected(CommandContext<CommandSourceStack> context) {
        Player player = requirePlayer(context);
        if (player == null) {
            return 0;
        }
        Course course = selectedCourse(player);
        if (course == null) {
            return 0;
        }
        applyBounds(player, course.holeOrCreate(session(player).currentHole()));
        return 1;
    }

    /**
     * Sets a hole's bounds, preferring marker blocks placed in the world and falling back to the
     * two-corner selection. Markers win because they stay visible and editable after the fact.
     */
    /**
     * Derives a hole's bounds.
     *
     * <p>Preferred route is a flood fill from the tee, because a bounding box cannot describe a
     * course that bends - an L-shaped or spiral hole has a rectangle far bigger than the fairway.
     * Flooding follows whatever shape was actually built and stops at the boundary materials.
     * Marker blocks and a pos1/pos2 selection remain as fallbacks.
     */
    private void applyBounds(Player player, Hole hole) {
        if (hole.tee() != null && fillFromTee(player, hole)) {
            return;
        }
        Material marker = Material.matchMaterial(plugin.config().boundsMarker());
        if (marker != null) {
            BoundsMarkers.Result result = BoundsMarkers.scan(player.getLocation(), marker,
                    plugin.config().boundsScanRadius(), plugin.config().boundsHeightPadding());
            if (result.isUsable()) {
                hole.setBounds(result.bounds());
                Bounds b = result.bounds();
                plugin.messages().send(player, "admin.bounds-from-markers",
                        "hole", String.valueOf(hole.number()),
                        "count", String.valueOf(result.markersFound()),
                        "material", marker.name(),
                        "min", b.minX() + ", " + b.minY() + ", " + b.minZ(),
                        "max", b.maxX() + ", " + b.maxY() + ", " + b.maxZ());
                return;
            }
        }

        BuilderSession session = session(player);
        if (!session.hasBothCorners()) {
            plugin.messages().send(player, "admin.bounds-none",
                    "material", String.join(", ", plugin.config().boundaryMaterials()));
            return;
        }
        hole.setBounds(session.toBounds());
        Bounds b = session.toBounds();
        plugin.messages().send(player, "admin.bounds-set",
                "hole", String.valueOf(hole.number()),
                "min", b.minX() + ", " + b.minY() + ", " + b.minZ(),
                "max", b.maxX() + ", " + b.maxY() + ", " + b.maxZ());
    }

    /** Floods the fairway from the tee. Returns false if the fill could not produce a usable region. */
    private boolean fillFromTee(Player player, Hole hole) {
        var world = plugin.getServer().getWorld(
                plugin.courses().course(session(player).courseId()).map(Course::world).orElse(null));
        if (world == null) {
            return false;
        }
        Vec3 tee = hole.tee();
        var boundary = plugin.config().boundaryMaterialSet();
        CourseRegion.OpenTest open = WorldRegionScanner.openTest(
                world, plugin.config().surfaces(), hole.materialOverrides(), boundary);

        CourseRegion.Result region = CourseRegion.fill(
                tee.blockX(), (int) Math.floor(tee.y()), tee.blockZ(), open,
                plugin.config().boundsMaxCells(), plugin.config().boundsHeightPadding(),
                plugin.config().boundsMaxDrop());

        if (region.bounds() == null) {
            plugin.messages().send(player, "admin.bounds-tee-blocked");
            return false;
        }
        if (region.exhausted()) {
            // Almost always a gap in the boundary rather than a genuinely enormous hole.
            plugin.messages().send(player, "admin.bounds-leaked",
                    "cells", String.valueOf(region.size()),
                    "material", String.join(", ", plugin.config().boundaryMaterials()));
            return false;
        }
        hole.setBounds(region.bounds());
        Bounds b = region.bounds();
        plugin.messages().send(player, "admin.bounds-from-fill",
                "hole", String.valueOf(hole.number()),
                "cells", String.valueOf(region.size()),
                "min", b.minX() + ", " + b.minY() + ", " + b.minZ(),
                "max", b.maxX() + ", " + b.maxY() + ", " + b.maxZ());
        return true;
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

    /**
     * Links a teleport pad using the existing two-corner selection: corner 1 is the pad you roll
     * onto, corner 2 is where the ball comes out. Reusing pos1/pos2 means the wand needs no extra
     * bindings to place pads.
     */
    private int adminAddTeleport(CommandContext<CommandSourceStack> context, boolean keepVelocity) {
        Player player = requirePlayer(context);
        if (player == null) {
            return 0;
        }
        Course course = selectedCourse(player);
        if (course == null) {
            return 0;
        }
        BuilderSession session = session(player);
        if (!session.hasBothCorners()) {
            plugin.messages().send(player, "admin.no-selection");
            return 0;
        }
        Hole hole = course.holeOrCreate(session.currentHole());
        Vec3 from = session.corner1();
        Vec3 to = session.corner2();
        // corner1 marks the pad's top face, so the block you roll onto is the one below it.
        hole.addTeleport(from.blockX(), (int) Math.floor(from.y()) - 1, from.blockZ(), to, keepVelocity);
        plugin.messages().send(player, "admin.teleport-added",
                "hole", String.valueOf(hole.number()),
                "from", from.blockX() + ", " + ((int) Math.floor(from.y()) - 1) + ", " + from.blockZ(),
                "to", to.blockX() + ", " + (int) Math.floor(to.y()) + ", " + to.blockZ(),
                "mode", keepVelocity ? "keeps speed" : "stops");
        return 1;
    }

    private int adminClearTeleports(CommandContext<CommandSourceStack> context) {
        Player player = requirePlayer(context);
        if (player == null) {
            return 0;
        }
        Course course = selectedCourse(player);
        if (course == null) {
            return 0;
        }
        Hole hole = course.holeOrCreate(session(player).currentHole());
        int removed = hole.teleportCount();
        hole.clearTeleports();
        plugin.messages().send(player, "admin.teleports-cleared",
                "hole", String.valueOf(hole.number()), "count", String.valueOf(removed));
        return 1;
    }

    private int adminWand(CommandContext<CommandSourceStack> context) {
        Player player = requirePlayer(context);
        if (player == null) {
            return 0;
        }
        player.getInventory().addItem(plugin.items().createWand(plugin.config().wandItem()));
        plugin.messages().send(player, "wand.given", "hole", String.valueOf(session(player).currentHole()));
        return 1;
    }

    private int adminHole(CommandContext<CommandSourceStack> context) {
        Player player = requirePlayer(context);
        if (player == null) {
            return 0;
        }
        int hole = IntegerArgumentType.getInteger(context, "hole");
        session(player).selectHole(hole);
        plugin.messages().send(player, "wand.hole-selected", "hole", String.valueOf(hole));
        return 1;
    }

    /**
     * Diagnoses why a hole is not playing as built: what every block in its region actually maps to,
     * and in particular whether anything is acting as a wall at ball height.
     *
     * <p>The usual cause of "the ball rolls straight through my walls" is a wall block that is not
     * in the material map at all - an unmapped material reads as plain green, so the ball treats it
     * as floor. The second cause is a wall built level with the green instead of one block above it:
     * ground is sampled a block below the ball, walls at the ball's own layer.
     */
    /**
     * Paints the traced region so a builder can see the shape the fill found - and, when it leaked,
     * see where it got out. Deliberately renders a leaked fill too: a cell count in chat says
     * something went wrong, but only the picture says where.
     */
    private int adminShowFill(CommandContext<CommandSourceStack> context, int seconds) {
        Player player = requirePlayer(context);
        if (player == null) {
            return 0;
        }
        Course course = selectedCourse(player);
        if (course == null) {
            return 0;
        }
        Hole hole = course.hole(session(player).currentHole()).orElse(null);
        if (hole == null || hole.tee() == null) {
            plugin.messages().send(player, "admin.fill-needs-tee");
            return 0;
        }
        var world = plugin.getServer().getWorld(course.world());
        if (world == null) {
            plugin.messages().send(player, "course.unknown", "course", course.id());
            return 0;
        }

        Vec3 tee = hole.tee();
        int startY = (int) Math.floor(tee.y());
        CourseRegion.Result region = CourseRegion.fill(
                tee.blockX(), startY, tee.blockZ(),
                WorldRegionScanner.openTest(world, plugin.config().surfaces(),
                        hole.materialOverrides(), plugin.config().boundaryMaterialSet()),
                plugin.config().boundsMaxCells(), plugin.config().boundsHeightPadding(),
                plugin.config().boundsMaxDrop());

        if (region.bounds() == null) {
            plugin.messages().send(player, "admin.bounds-tee-blocked");
            return 0;
        }

        BukkitTask previous = fillPreviews.remove(player.getUniqueId());
        if (previous != null) {
            previous.cancel();
        }
        fillPreviews.put(player.getUniqueId(),
                FillVisualizer.show(plugin, player, region, startY, seconds));

        plugin.messages().send(player,
                region.exhausted() ? "admin.fill-preview-leaked" : "admin.fill-preview",
                "hole", String.valueOf(hole.number()),
                "cells", String.valueOf(region.size()),
                "seconds", String.valueOf(seconds),
                "material", String.join(", ", plugin.config().boundaryMaterials()));
        return 1;
    }

    private int adminCheck(CommandContext<CommandSourceStack> context) {
        Player player = requirePlayer(context);
        if (player == null) {
            return 0;
        }
        Course course = selectedCourse(player);
        if (course == null) {
            return 0;
        }
        int number = session(player).currentHole();
        Hole hole = course.hole(number).orElse(null);
        if (hole == null) {
            plugin.messages().send(player, "admin.no-such-hole", "hole", String.valueOf(number));
            return 0;
        }
        var world = plugin.getServer().getWorld(course.world());
        if (world == null) {
            plugin.messages().send(player, "round.world-missing", "world", String.valueOf(course.world()));
            return 0;
        }

        var sender = context.getSource().getSender();
        sender.sendMessage(plugin.messages().prefixed("check.header",
                "course", course.id(), "hole", String.valueOf(number)));
        if (!hole.isPlayable()) {
            sender.sendMessage(plugin.messages().render("check.incomplete",
                    "tee", String.valueOf(hole.tee() != null),
                    "cup", String.valueOf(hole.cup() != null),
                    "bounds", String.valueOf(hole.bounds() != null)));
            return 0;
        }

        Bounds bounds = hole.bounds();
        int ballY = (int) Math.floor(hole.tee().y());
        Map<String, Integer> groundCensus = new java.util.TreeMap<>();
        Map<String, Integer> unmappedAtBallHeight = new java.util.TreeMap<>();
        int wallBlocks = 0;

        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                var ground = world.getBlockAt(x, ballY - 1, z).getType();
                if (!ground.isAir()) {
                    var surface = plugin.config().surfaces()
                            .forMaterial(ground.name(), hole.materialOverrides());
                    groundCensus.merge(ground.name() + " -> " + surface.id(), 1, Integer::sum);
                }
                var atBall = world.getBlockAt(x, ballY, z).getType();
                if (atBall.isAir()) {
                    continue;
                }
                var surface = plugin.config().surfaces()
                        .forMaterial(atBall.name(), hole.materialOverrides());
                if (surface.isWall()) {
                    wallBlocks++;
                } else if (ConfigMigrator.looksLikeAWall(atBall.name())) {
                    unmappedAtBallHeight.merge(atBall.name(), 1, Integer::sum);
                }
            }
        }

        groundCensus.entrySet().stream().limit(8).forEach(entry ->
                sender.sendMessage(plugin.messages().render("check.ground",
                        "mapping", entry.getKey(), "count", String.valueOf(entry.getValue()))));
        sender.sendMessage(plugin.messages().render("check.walls", "count", String.valueOf(wallBlocks)));
        if (wallBlocks == 0) {
            sender.sendMessage(plugin.messages().render("check.no-walls", "y", String.valueOf(ballY)));
        }
        unmappedAtBallHeight.forEach((material, count) ->
                sender.sendMessage(plugin.messages().render("check.unmapped",
                        "material", material, "count", String.valueOf(count))));
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
        return plugin.builders().of(player.getUniqueId());
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
