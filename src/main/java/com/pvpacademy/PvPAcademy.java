package com.pvpacademy;

import com.pvpacademy.command.TrainCommand;
import com.pvpacademy.evaluation.EvaluationManager;
import com.pvpacademy.listener.TrainingListener;
import com.pvpacademy.playback.PlaybackManager;
import com.pvpacademy.recording.RecordingManager;
import com.pvpacademy.strategy.StrategyManager;
import com.pvpacademy.training.TrainingManager;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * PvP Academy — main plugin entry point.
 *
 * <h3>Systems bootstrapped on enable</h3>
 * <ul>
 *   <li><b>Tick counter</b> — a 1-tick repeating task that drives timing evaluation.</li>
 *   <li><b>TrainingManager</b> — built-in W-Tap training sessions.</li>
 *   <li><b>RecordingManager</b> — admin recording sessions + disk I/O.</li>
 *   <li><b>StrategyManager</b> — named strategy registry persisted to JSON.</li>
 *   <li><b>PlaybackManager</b> — bot-driven recording playbacks.</li>
 *   <li><b>EvaluationManager</b> — recording-based player evaluation sessions.</li>
 * </ul>
 */
public final class PvPAcademy extends JavaPlugin {

    // ── Tick counter ──────────────────────────────────────────────────────────
    private long currentTick = 0;
    private BukkitTask tickTask;

    // ── Sub-systems ───────────────────────────────────────────────────────────
    private TrainingManager   trainingManager;
    private RecordingManager  recordingManager;
    private StrategyManager   strategyManager;
    private PlaybackManager   playbackManager;
    private EvaluationManager evaluationManager;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // Tick clock — synchronous 1-tick repeating task used for timing math
        tickTask = Bukkit.getScheduler().runTaskTimer(this, () -> currentTick++, 0L, 1L);

        // Initialise managers in dependency order
        trainingManager   = new TrainingManager(this);
        recordingManager  = new RecordingManager(this);
        strategyManager   = new StrategyManager(this);
        playbackManager   = new PlaybackManager(this);
        evaluationManager = new EvaluationManager(this);

        registerCommands();
        Bukkit.getPluginManager().registerEvents(new TrainingListener(this), this);

        getLogger().info("PvP Academy " + getDescription().getVersion() + " enabled.");
    }

    @Override
    public void onDisable() {
        if (tickTask        != null) tickTask.cancel();
        if (trainingManager   != null) trainingManager.cleanup();
        if (recordingManager  != null) recordingManager.cleanup();
        if (playbackManager   != null) playbackManager.cleanup();
        if (evaluationManager != null) evaluationManager.cleanup();
        getLogger().info("PvP Academy disabled.");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void registerCommands() {
        TrainCommand handler = new TrainCommand(this);
        PluginCommand cmd    = getCommand("pvpa");
        if (cmd != null) {
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        } else {
            getLogger().severe("Could not register /pvpa — check plugin.yml!");
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns the plugin's internal tick counter.
     *
     * <p>Starts at 0 when the plugin enables and increments every server tick.
     * Use tick deltas (not wall-clock time) for all timing comparisons in
     * training and evaluation sessions.</p>
     */
    public long getCurrentTick()              { return currentTick; }
    public TrainingManager   getTrainingManager()   { return trainingManager; }
    public RecordingManager  getRecordingManager()  { return recordingManager; }
    public StrategyManager   getStrategyManager()   { return strategyManager; }
    public PlaybackManager   getPlaybackManager()   { return playbackManager; }
    public EvaluationManager getEvaluationManager() { return evaluationManager; }
}
