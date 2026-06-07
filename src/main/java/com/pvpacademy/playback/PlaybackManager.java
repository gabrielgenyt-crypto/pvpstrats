package com.pvpacademy.playback;

import com.pvpacademy.PvPAcademy;
import com.pvpacademy.model.Recording;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Manages all active {@link BotPlayback} instances.
 *
 * <p>Each player can have at most one active playback at a time.  Sessions are
 * keyed by the UUID of the player who triggered the playback (typically an
 * admin using {@code /pvpa play}).</p>
 */
public final class PlaybackManager {

    private final PvPAcademy plugin;
    private final Map<UUID, BotPlayback> activePlaybacks = new HashMap<>();

    public PlaybackManager(PvPAcademy plugin) {
        this.plugin = plugin;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Starts a new bot playback at the player's current location.
     *
     * @return {@code false} if the player already has an active playback
     */
    public boolean start(Player player, Recording recording) {
        if (activePlaybacks.containsKey(player.getUniqueId())) return false;

        Location origin   = player.getLocation();
        UUID     uuid     = player.getUniqueId();
        BotPlayback playback = new BotPlayback(
            plugin, uuid, recording, origin,
            id -> activePlaybacks.remove(id)   // onComplete callback
        );
        activePlaybacks.put(uuid, playback);
        playback.start();
        return true;
    }

    /**
     * Stops a player's active playback early (without block restoration).
     * Blocks ARE restored — call this for manual cancellation.
     *
     * @return {@code false} if no playback was active
     */
    public boolean stop(Player player) {
        BotPlayback pb = activePlaybacks.remove(player.getUniqueId());
        if (pb == null) return false;
        pb.stop(true);
        return true;
    }

    /**
     * Called by {@link BotPlayback} when its replay finishes naturally.
     * Removes the entry from the map without calling {@code stop()} again.
     */
    public void onPlaybackEnd(UUID playerId) {
        activePlaybacks.remove(playerId);
    }

    /** Returns {@code true} if the player has an active playback. */
    public boolean hasPlayback(UUID playerId) {
        return activePlaybacks.containsKey(playerId);
    }

    /** Stops all active playbacks on plugin disable. */
    public void cleanup() {
        new HashMap<>(activePlaybacks).forEach((uuid, pb) -> pb.stop(true));
        activePlaybacks.clear();
    }
}
