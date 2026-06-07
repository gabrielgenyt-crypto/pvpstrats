package com.pvpacademy;

import com.pvpacademy.command.TrainCommand;
import com.pvpacademy.listener.TrainingListener;
import com.pvpacademy.training.TrainingManager;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * PvP Academy — main plugin entry point.
 *
 * <p>Bootstraps the tick counter, training manager, command handler, and event
 * listeners on enable; tears everything down cleanly on disable.</p>
 */
public final class PvPAcademy extends JavaPlugin {

    // ── Internal tick counter ─────────────────────────────────────────────────
    // Incremented every server tick (50 ms) so that training modules can measure
    // timing deltas without relying on System.currentTimeMillis() drift.
    private long currentTick = 0;
    private BukkitTask tickTask;

    // ── Sub-systems ───────────────────────────────────────────────────────────
    private TrainingManager trainingManager;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // Start the internal tick clock — runs synchronously every game tick.
        tickTask = Bukkit.getScheduler().runTaskTimer(this, () -> currentTick++, 0L, 1L);

        trainingManager = new TrainingManager(this);

        registerCommands();
        Bukkit.getPluginManager().registerEvents(new TrainingListener(this), this);

        getLogger().info("PvP Academy " + getDescription().getVersion() + " enabled.");
    }

    @Override
    public void onDisable() {
        if (tickTask != null) tickTask.cancel();
        if (trainingManager != null) trainingManager.cleanup();
        getLogger().info("PvP Academy disabled.");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void registerCommands() {
        TrainCommand handler = new TrainCommand(this);
        PluginCommand cmd = getCommand("pvpa");
        if (cmd != null) {
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        } else {
            getLogger().severe("Could not register /pvpa — check plugin.yml!");
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns the current value of the internal tick counter.
     *
     * <p>The counter starts at 0 when the plugin enables and increments by 1
     * every server tick.  Use this for timing deltas in training modules.</p>
     */
    public long getCurrentTick() {
        return currentTick;
    }

    public TrainingManager getTrainingManager() {
        return trainingManager;
    }
}
