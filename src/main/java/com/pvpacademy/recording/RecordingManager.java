package com.pvpacademy.recording;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.pvpacademy.PvPAcademy;
import com.pvpacademy.model.Recording;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

/**
 * Central hub for recording sessions and persisted {@link Recording} files.
 *
 * <p>Recordings are saved as pretty-printed JSON in
 * {@code plugins/PvPAcademy/recordings/<name>.json}.  Gson serializes/deserializes
 * the {@link Recording} and its nested records transparently.</p>
 */
public final class RecordingManager {

    private final PvPAcademy plugin;
    private final File       recordingsDir;
    private final Gson       gson;

    /** Active recording sessions keyed by player UUID. */
    private final Map<UUID, RecordingSession> activeSessions = new HashMap<>();

    public RecordingManager(PvPAcademy plugin) {
        this.plugin        = plugin;
        this.recordingsDir = new File(plugin.getDataFolder(), "recordings");
        this.gson          = new GsonBuilder().setPrettyPrinting().create();

        if (!recordingsDir.exists() && !recordingsDir.mkdirs()) {
            plugin.getLogger().warning("Could not create recordings directory.");
        }
    }

    // ── Session management ────────────────────────────────────────────────────

    /**
     * Starts a recording session for {@code player} using {@code name} as the
     * recording identifier.
     *
     * @return {@code false} if the player already has an active session
     */
    public boolean startSession(Player player, String name) {
        if (activeSessions.containsKey(player.getUniqueId())) return false;

        RecordingSession session = new RecordingSession(plugin, player, name);
        activeSessions.put(player.getUniqueId(), session);
        session.start();
        return true;
    }

    /**
     * Stops the player's active recording session.
     *
     * @param player the admin who was recording
     * @param notify whether to print the completion message
     * @return the finished {@link Recording}, or {@code empty} if no session was active
     */
    public Optional<Recording> stopSession(Player player, boolean notify) {
        RecordingSession session = activeSessions.remove(player.getUniqueId());
        if (session == null) return Optional.empty();
        return Optional.of(session.stop(notify));
    }

    /** Returns the active session for {@code playerId}, or {@code null}. */
    public RecordingSession getSession(UUID playerId) {
        return activeSessions.get(playerId);
    }

    public boolean hasSession(UUID playerId) {
        return activeSessions.containsKey(playerId);
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    /**
     * Serializes a {@link Recording} and writes it to disk.
     *
     * @throws IOException if the file cannot be written
     */
    public void save(Recording recording) throws IOException {
        File file = new File(recordingsDir, recording.getName() + ".json");
        try (FileWriter writer = new FileWriter(file)) {
            gson.toJson(recording, writer);
        }
    }

    /**
     * Loads and deserializes a recording by name.
     *
     * @param name recording file name (without ".json")
     * @return the recording, or {@code empty} if no file exists or parsing fails
     */
    public Optional<Recording> load(String name) {
        File file = new File(recordingsDir, name + ".json");
        if (!file.exists()) return Optional.empty();

        try (FileReader reader = new FileReader(file)) {
            return Optional.ofNullable(gson.fromJson(reader, Recording.class));
        } catch (IOException e) {
            plugin.getLogger().warning(
                "Failed to load recording '" + name + "': " + e.getMessage());
            return Optional.empty();
        }
    }

    /** Returns {@code true} if a recording file with this name exists on disk. */
    public boolean exists(String name) {
        return new File(recordingsDir, name + ".json").exists();
    }

    /** Returns a sorted list of all saved recording names (without the .json extension). */
    public List<String> listAll() {
        File[] files = recordingsDir.listFiles((d, n) -> n.endsWith(".json"));
        if (files == null) return List.of();
        return Arrays.stream(files)
                     .map(f -> f.getName().replace(".json", ""))
                     .sorted()
                     .toList();
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    /** Stops all active recording sessions on plugin disable. */
    public void cleanup() {
        activeSessions.values().forEach(s -> s.stop(false));
        activeSessions.clear();
    }
}
