package com.redcoffee.puttputt;

import com.redcoffee.puttputt.command.BuilderSessions;
import com.redcoffee.puttputt.command.PuttPuttCommand;
import com.redcoffee.puttputt.config.ConfigMigrator;
import com.redcoffee.puttputt.config.Messages;
import com.redcoffee.puttputt.config.PluginConfig;
import com.redcoffee.puttputt.course.CourseManager;
import com.redcoffee.puttputt.game.PhysicsEngine;
import com.redcoffee.puttputt.game.Round;
import com.redcoffee.puttputt.game.RoundManager;
import com.redcoffee.puttputt.snapshot.RoundSnapshot;
import com.redcoffee.puttputt.input.PartyEventListener;
import com.redcoffee.puttputt.input.PuttListener;
import com.redcoffee.puttputt.input.WandListener;
import com.redcoffee.puttputt.item.PuttItems;
import com.redcoffee.puttputt.party.PartyProvider;
import com.redcoffee.puttputt.party.RCPartiesProvider;
import com.redcoffee.puttputt.storage.ScoreDao;
import com.redcoffee.puttputt.storage.SqliteScoreDao;
import com.redcoffee.puttputt.storage.StorageException;
import net.republicraft.rcui.api.RCUI;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.File;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.plugin.java.JavaPlugin;

/** Plugin entry point: wires the config, storage, party seam, physics loop and commands together. */
@SuppressWarnings("UnstableApiUsage")
public final class RCPuttPuttPlugin extends JavaPlugin {

    private final PluginConfig config = new PluginConfig(getLogger());
    private final BuilderSessions builders = new BuilderSessions();
    private CourseManager courses;
    private RoundManager rounds;
    private PuttItems items;
    private PartyProvider parties;
    private ScoreDao scoreDao;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        new ConfigMigrator(this).migrate(getConfig());
        config.load(getConfig());

        courses = new CourseManager(new File(getDataFolder(), "courses"), getLogger());
        courses.loadAll();

        scoreDao = new SqliteScoreDao(new File(getDataFolder(), "scores.db"));
        try {
            scoreDao.initialise();
        } catch (StorageException ex) {
            // Play is still possible without persistence; losing the leaderboard beats losing the plugin.
            getLogger().log(Level.SEVERE, "Score storage is unavailable; rounds will not be recorded.", ex);
            scoreDao = null;
        }

        // RCParties is a hard dependency declared in paper-plugin.yml, so a missing service means
        // something is genuinely wrong. Refusing to enable beats running in a half-broken state.
        // RCUI owns the message catalog and the shared prefix. Declared required in
        // paper-plugin.yml, so a failure here is a genuine misconfiguration, not a soft fallback.
        try {
            config.messages().bind(RCUI.messages(this).register(this, "rcputtputt", "messages.yml"));
        } catch (RuntimeException ex) {
            getLogger().log(Level.SEVERE, "Could not register the RCUI message catalog; "
                    + "RCPuttPutt cannot run without it.", ex);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        parties = RCPartiesProvider.bind(getLogger()).orElse(null);
        if (parties == null) {
            getLogger().severe("RCParties registered no PartyService; RCPuttPutt cannot run without it.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getLogger().info("Party backend: " + parties.name());

        items = new PuttItems(this);
        rounds = new RoundManager(this, new PhysicsEngine(config.physics(), config.ballCollision()), items);
        rounds.start();

        getServer().getPluginManager().registerEvents(new PuttListener(this), this);
        getServer().getPluginManager().registerEvents(new WandListener(this), this);
        getServer().getPluginManager().registerEvents(new PartyEventListener(this), this);

        PuttPuttCommand commands = new PuttPuttCommand(this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(commands.build(), "Play putt-putt.", java.util.List.of("pp", "golf")));

        // Deferred a tick so worlds and other plugins are fully up before we rebuild rounds.
        getServer().getScheduler().runTaskLater(this, this::restoreRounds, 20L);
    }

    /**
     * Rebuilds rounds that were live when the server stopped (RC-SPEC v2 s9).
     *
     * <p>A round is only resumed once every one of its original members is back online inside the
     * resume window - a half-restored round with missing players creates more edge cases than it
     * solves. Anything else is archived so it stops being offered.
     */
    private void restoreRounds() {
        long cutoff = System.currentTimeMillis() - config.snapshots().resumeWindowMillis();
        queryStorage(dao -> dao.resumableRounds(cutoff), resumable -> {
            for (var row : resumable) {
                var snapshot = RoundSnapshot.fromJson(row.snapshotJson());
                var members = RoundSnapshot.decodeMembers(row.partyJson());
                var course = courses.course(row.courseId()).orElse(null);
                boolean everyoneBack = !members.isEmpty() && members.stream()
                        .allMatch(id -> getServer().getPlayer(id) != null);
                if (snapshot == null || course == null || !everyoneBack) {
                    runStorage(dao -> {
                        dao.archiveRound(row.roundId());
                        dao.clearSnapshot(row.roundId());
                    });
                    continue;
                }
                Round round = new Round(row.roundId(), course, members.getFirst(), members, System.currentTimeMillis());
                rounds.resume(round, snapshot, members);
                getLogger().info("Resumed round " + row.roundId() + " on " + course.id() + ".");
            }
        }, error -> getLogger().warning("Could not check for resumable rounds: " + error.getMessage()));
    }

    /** Best-effort display name for a player id, online or not. */
    public String nameOf(java.util.UUID playerId) {
        var online = getServer().getPlayer(playerId);
        if (online != null) {
            return online.getName();
        }
        String offline = getServer().getOfflinePlayer(playerId).getName();
        return offline != null ? offline : playerId.toString();
    }

    @Override
    public void onDisable() {
        if (rounds != null) {
            rounds.shutdown();
        }
        if (scoreDao != null) {
            scoreDao.close();
        }
    }

    /** Re-reads config and courses. Live rounds keep the physics constants they started with. */
    public void reloadEverything() {
        reloadConfig();
        new ConfigMigrator(this).migrate(getConfig());
        config.load(getConfig());
        courses.loadAll();
        if (config.messages().isBound()) {
            // Re-read RCUI's catalog too, so /puttputt admin reload picks up message edits.
            RCUI.messages(this).reload();
        }
    }

    public PluginConfig config() {
        return config;
    }

    public Messages messages() {
        return config.messages();
    }

    public CourseManager courses() {
        return courses;
    }

    public RoundManager rounds() {
        return rounds;
    }

    public BuilderSessions builders() {
        return builders;
    }

    public PuttItems items() {
        return items;
    }

    public PartyProvider parties() {
        return parties;
    }

    // ------------------------------------------------------------------ storage plumbing

    /** A DAO call that returns nothing. */
    @FunctionalInterface
    public interface StorageAction {
        void run(ScoreDao dao) throws StorageException;
    }

    /** A DAO call that returns a result. */
    @FunctionalInterface
    public interface StorageQuery<T> {
        T run(ScoreDao dao) throws StorageException;
    }

    /** Runs a write off the main thread. Silently skipped when storage failed to initialise. */
    public void runStorage(StorageAction action) {
        if (scoreDao == null) {
            return;
        }
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                action.run(scoreDao);
            } catch (StorageException ex) {
                getLogger().log(Level.WARNING, "Score write failed", ex);
            }
        });
    }

    /**
     * Runs a read off the main thread and hands the result back on the main thread, so callers can
     * touch players and send messages from the success handler.
     */
    public <T> void queryStorage(StorageQuery<T> query, Consumer<T> onSuccess, Consumer<StorageException> onFailure) {
        if (scoreDao == null) {
            onFailure.accept(new StorageException("Score storage is unavailable", null));
            return;
        }
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                T result = query.run(scoreDao);
                getServer().getScheduler().runTask(this, () -> onSuccess.accept(result));
            } catch (StorageException ex) {
                getLogger().log(Level.WARNING, "Score read failed", ex);
                getServer().getScheduler().runTask(this, () -> onFailure.accept(ex));
            }
        });
    }
}
