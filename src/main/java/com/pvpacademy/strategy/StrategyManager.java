package com.pvpacademy.strategy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.pvpacademy.PvPAcademy;
import com.pvpacademy.model.Strategy;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.*;

/**
 * Manages named PvP strategies and their associated recordings.
 *
 * <h3>Storage</h3>
 * <p>Strategies are persisted as a JSON array in
 * {@code plugins/PvPAcademy/strategies.json}.  The file is loaded on startup
 * and flushed to disk whenever a strategy is created or modified.</p>
 *
 * <h3>Usage in commands</h3>
 * <pre>
 *   /pvpa strat create dtap "D-Tap" "Double-pop a target in mid-air"
 *   /pvpa bot create dtap dtap_perfect
 * </pre>
 */
public final class StrategyManager {

    private final PvPAcademy plugin;
    private final File        storageFile;
    private final Gson        gson;

    /** In-memory strategy map, keyed by lower-case name for fast lookup. */
    private final Map<String, Strategy> strategies = new LinkedHashMap<>();

    // ── Constructor & init ────────────────────────────────────────────────────

    public StrategyManager(PvPAcademy plugin) {
        this.plugin      = plugin;
        this.storageFile = new File(plugin.getDataFolder(), "strategies.json");
        this.gson        = new GsonBuilder().setPrettyPrinting().create();
        load();
    }

    // ── Strategy CRUD ─────────────────────────────────────────────────────────

    /**
     * Creates a new strategy and persists it.
     *
     * @param name        command-level identifier (e.g. {@code "dtap"})
     * @param displayName label shown in the UI (e.g. {@code "D-Tap"})
     * @param description short explanation shown to students
     * @return {@code false} if a strategy with this name already exists
     */
    public boolean create(String name, String displayName, String description) {
        String key = name.toLowerCase();
        if (strategies.containsKey(key)) return false;

        strategies.put(key, new Strategy(
            key,
            displayName,
            description,
            null,
            Strategy.DEFAULT_PERFECT_TICKS,
            Strategy.DEFAULT_GOOD_TICKS
        ));
        save();
        return true;
    }

    /**
     * Links an existing recording to a strategy (sets the demo bot).
     *
     * @param strategyName the strategy to update
     * @param recordingName the recording to link
     * @return {@code false} if the strategy does not exist
     */
    public boolean linkRecording(String strategyName, String recordingName) {
        String key = strategyName.toLowerCase();
        Strategy existing = strategies.get(key);
        if (existing == null) return false;

        strategies.put(key, existing.withRecording(recordingName));
        save();
        return true;
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    /** Returns the strategy with the given name, or {@code empty} if not found. */
    public Optional<Strategy> get(String name) {
        return Optional.ofNullable(strategies.get(name.toLowerCase()));
    }

    public boolean exists(String name) {
        return strategies.containsKey(name.toLowerCase());
    }

    /** Returns an unmodifiable view of all strategies in creation order. */
    public Collection<Strategy> getAll() {
        return Collections.unmodifiableCollection(strategies.values());
    }

    /** Returns all strategy names sorted alphabetically. */
    public List<String> listNames() {
        return strategies.keySet().stream().sorted().toList();
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private void load() {
        if (!storageFile.exists()) return;

        Type listType = new TypeToken<List<Strategy>>() {}.getType();
        try (FileReader reader = new FileReader(storageFile)) {
            List<Strategy> loaded = gson.fromJson(reader, listType);
            if (loaded != null) {
                loaded.forEach(s -> strategies.put(s.name().toLowerCase(), s));
            }
        } catch (IOException e) {
            plugin.getLogger().warning(
                "Could not load strategies.json: " + e.getMessage());
        }
    }

    private void save() {
        try (FileWriter writer = new FileWriter(storageFile)) {
            gson.toJson(new ArrayList<>(strategies.values()), writer);
        } catch (IOException e) {
            plugin.getLogger().warning(
                "Could not save strategies.json: " + e.getMessage());
        }
    }
}
