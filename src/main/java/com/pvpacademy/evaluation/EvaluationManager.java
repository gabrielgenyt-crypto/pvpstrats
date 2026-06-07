package com.pvpacademy.evaluation;

import com.pvpacademy.PvPAcademy;
import com.pvpacademy.model.Recording;
import com.pvpacademy.model.Strategy;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Manages active {@link EvaluationSession} instances (one per player).
 *
 * <p>Distinct from {@link com.pvpacademy.training.TrainingManager} which handles
 * the hard-coded W-Tap module.  This manager handles recording-based evaluation
 * sessions started via {@code /pvpa train <strategy>}.</p>
 */
public final class EvaluationManager {

    private final PvPAcademy plugin;
    private final Map<UUID, EvaluationSession> activeSessions = new HashMap<>();

    public EvaluationManager(PvPAcademy plugin) {
        this.plugin = plugin;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Starts an evaluation session for {@code player} against the given strategy
     * and its recording.
     *
     * @return {@code false} if the player already has an active session
     */
    public boolean start(Player player, Strategy strategy, Recording recording) {
        if (activeSessions.containsKey(player.getUniqueId())) return false;

        UUID uuid = player.getUniqueId();
        EvaluationSession session = new EvaluationSession(
            plugin, player, strategy, recording,
            id -> activeSessions.remove(id)    // onComplete callback
        );
        activeSessions.put(uuid, session);
        session.start();
        return true;
    }

    /**
     * Stops the player's active evaluation session.
     *
     * @return {@code false} if the player had no active session
     */
    public boolean stop(Player player) {
        EvaluationSession session = activeSessions.remove(player.getUniqueId());
        if (session == null) return false;
        session.stop(true);
        return true;
    }

    /**
     * Called by {@link EvaluationSession} when the session completes naturally.
     * Removes the entry without calling {@code stop()} a second time.
     */
    public void onSessionComplete(UUID playerId) {
        activeSessions.remove(playerId);
    }

    /** Returns the active session for {@code playerId}, or {@code null}. */
    public EvaluationSession getSession(UUID playerId) {
        return activeSessions.get(playerId);
    }

    public boolean hasSession(UUID playerId) {
        return activeSessions.containsKey(playerId);
    }

    /** Stops all active sessions on plugin disable. */
    public void cleanup() {
        activeSessions.values().forEach(s -> s.stop(false));
        activeSessions.clear();
    }
}
