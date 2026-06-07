package com.pvpacademy.training;

import com.pvpacademy.PvPAcademy;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Central registry of active training sessions.
 *
 * <p>Every player can have at most one active session at a time.  Sessions are
 * keyed by player UUID so lookups from event listeners are O(1).</p>
 */
public final class TrainingManager {

    private final PvPAcademy plugin;
    /** Live sessions, keyed by the training player's UUID. */
    private final Map<UUID, WTapSession> activeSessions = new HashMap<>();

    public TrainingManager(PvPAcademy plugin) {
        this.plugin = plugin;
    }

    // ── Session lifecycle ─────────────────────────────────────────────────────

    /**
     * Starts a W-Tap training session for the given player.
     *
     * @return {@code false} if the player already has an active session.
     */
    public boolean startWTap(Player player) {
        if (activeSessions.containsKey(player.getUniqueId())) return false;

        WTapSession session = new WTapSession(plugin, player);
        activeSessions.put(player.getUniqueId(), session);
        session.start();
        return true;
    }

    /**
     * Ends the active session for the given player, if any.
     *
     * @return {@code false} if the player had no active session.
     */
    public boolean stop(Player player) {
        WTapSession session = activeSessions.remove(player.getUniqueId());
        if (session == null) return false;
        session.stop(/* showMessage */ true);
        return true;
    }

    /**
     * Called by {@link WTapSession} when it ends naturally (session complete).
     * Removes the session from the registry without calling {@code stop()} again.
     */
    public void onSessionComplete(UUID playerId) {
        activeSessions.remove(playerId);
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    /** Returns the active session for the given player, or {@code null}. */
    public WTapSession getSession(UUID playerId) {
        return activeSessions.get(playerId);
    }

    public boolean hasSession(UUID playerId) {
        return activeSessions.containsKey(playerId);
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    /**
     * Stops all active sessions.  Called on plugin disable so no dummy entities
     * are left in the world and players are teleported back.
     */
    public void cleanup() {
        // Copy to avoid ConcurrentModificationException during iteration.
        activeSessions.values().forEach(s -> s.stop(false));
        activeSessions.clear();
    }
}
