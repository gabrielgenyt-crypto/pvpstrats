package com.pvpacademy.listener;

import com.pvpacademy.PvPAcademy;
import com.pvpacademy.training.WTapSession;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;

/**
 * Routes game events to the active {@link WTapSession} for the relevant player.
 *
 * <p>Only three events matter for the W-Tap module:</p>
 * <ul>
 *   <li>{@link EntityDamageByEntityEvent} — player lands a melee hit on the dummy</li>
 *   <li>{@link PlayerToggleSprintEvent}   — player starts/stops sprinting</li>
 *   <li>{@link PlayerQuitEvent}           — clean up session if player disconnects</li>
 * </ul>
 */
public final class TrainingListener implements Listener {

    private final PvPAcademy plugin;

    public TrainingListener(PvPAcademy plugin) {
        this.plugin = plugin;
    }

    // ── Event handlers ────────────────────────────────────────────────────────

    /**
     * Intercepts melee hits and forwards them to the active session.
     *
     * <p>We use {@link EventPriority#HIGH} to run after most protection plugins
     * (which typically use NORMAL priority) but before HIGHEST-priority listeners.
     * The event is NOT cancelled — we want the hit to register in the game world
     * so the player gets the knockback animation as visual feedback.</p>
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        // Only care about direct player attacks (no arrows, no splash potions)
        if (!(event.getDamager() instanceof Player attacker)) return;

        WTapSession session = plugin.getTrainingManager().getSession(attacker.getUniqueId());
        if (session == null) return;

        session.onAttack(event.getEntity());
    }

    /**
     * Tracks sprint toggles so the session can evaluate W-Tap timing.
     *
     * <p>We use MONITOR priority so we record the final, unmodified sprint state
     * after all other plugins have processed the event.</p>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSprintToggle(PlayerToggleSprintEvent event) {
        WTapSession session = plugin.getTrainingManager().getSession(event.getPlayer().getUniqueId());
        if (session == null) return;

        session.onSprintToggle(event.isSprinting());
    }

    /**
     * Cleans up sessions when a player disconnects mid-training.
     *
     * <p>This prevents ghost sessions from lingering in the manager's map and
     * dummy entities from remaining in the world after the player leaves.</p>
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        WTapSession session = plugin.getTrainingManager().getSession(player.getUniqueId());
        if (session == null) return;

        // Remove from manager before stopping so onSessionComplete doesn't double-remove
        plugin.getTrainingManager().onSessionComplete(player.getUniqueId());
        session.stop(false); // silent — player has already disconnected
    }
}
