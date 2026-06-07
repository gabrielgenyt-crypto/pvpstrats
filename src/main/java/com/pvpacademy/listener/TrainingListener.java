package com.pvpacademy.listener;

import com.pvpacademy.PvPAcademy;
import com.pvpacademy.evaluation.EvaluationSession;
import com.pvpacademy.model.PlayerAction;
import com.pvpacademy.recording.RecordingSession;
import com.pvpacademy.training.WTapSession;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Routes game events to the appropriate active session type.
 *
 * <h3>Event matrix</h3>
 * <table>
 *   <tr><th>Event</th><th>RecordingSession</th><th>WTapSession</th><th>EvaluationSession</th></tr>
 *   <tr><td>PlayerInteractEvent</td><td>setPendingAction</td><td>—</td><td>onPlayerAction</td></tr>
 *   <tr><td>EntityDamageByEntityEvent</td><td>setPendingAction (LEFT_CLICK)</td>
 *       <td>onAttack</td><td>onPlayerAction (LEFT_CLICK)</td></tr>
 *   <tr><td>PlayerToggleSprintEvent</td><td>—</td><td>onSprintToggle</td><td>—</td></tr>
 *   <tr><td>PlayerQuitEvent</td><td>cleanup</td><td>cleanup</td><td>cleanup</td></tr>
 * </table>
 */
public final class TrainingListener implements Listener {

    private final PvPAcademy plugin;

    public TrainingListener(PvPAcademy plugin) {
        this.plugin = plugin;
    }

    // ── EntityDamageByEntityEvent ─────────────────────────────────────────────

    /**
     * Intercepts direct player melee hits.
     *
     * <ul>
     *   <li>W-Tap session: forwards to {@link WTapSession#onAttack}.</li>
     *   <li>Recording session: records a LEFT_CLICK action for this tick.</li>
     *   <li>Evaluation session: notifies {@link EvaluationSession#onPlayerAction}.</li>
     * </ul>
     *
     * <p>Priority HIGH so this runs after most protection plugins (NORMAL) but
     * before HIGHEST.  The event is NOT cancelled — knockback feedback is intentional.</p>
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;

        // W-Tap training
        WTapSession wSession = plugin.getTrainingManager().getSession(attacker.getUniqueId());
        if (wSession != null) wSession.onAttack(event.getEntity());

        // Evaluation session
        EvaluationSession evalSession =
            plugin.getEvaluationManager().getSession(attacker.getUniqueId());
        if (evalSession != null) evalSession.onPlayerAction(PlayerAction.LEFT_CLICK_AIR);

        // Recording session — register a LEFT_CLICK_AIR for this tick
        RecordingSession recSession =
            plugin.getRecordingManager().getSession(attacker.getUniqueId());
        if (recSession != null) {
            recSession.setPendingAction(PlayerAction.LEFT_CLICK_AIR, null, null, null);
        }
    }

    // ── PlayerInteractEvent ───────────────────────────────────────────────────

    /**
     * Intercepts left- and right-click interactions for recording and evaluation.
     *
     * <p>We listen on MONITOR (after all cancellations) so we record what the
     * game actually processed.  However, for recording we do NOT require the event
     * to be uncancelled — we want to record the intent, even if a protection plugin
     * blocked the action.</p>
     *
     * <p>Only the MAIN_HAND fires this handler (we skip OFF_HAND to avoid duplicates).</p>
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerInteract(PlayerInteractEvent event) {
        // Skip off-hand to avoid double-counting
        if (event.getHand() != EquipmentSlot.HAND) return;

        Player player = event.getPlayer();
        Action action = event.getAction();

        PlayerAction pa = toPlayerAction(action);
        if (pa == PlayerAction.NONE) return;

        // ── Recording session ──────────────────────────────────────────────
        RecordingSession recSession =
            plugin.getRecordingManager().getSession(player.getUniqueId());
        if (recSession != null) {
            Location blockLoc = null;
            BlockFace face    = null;
            String    mat     = null;

            if (event.getClickedBlock() != null) {
                Block clicked = event.getClickedBlock();
                face = event.getBlockFace();

                if (action == Action.RIGHT_CLICK_BLOCK) {
                    // The block being PLACED is on the face of the clicked block
                    Block placedAt = clicked.getRelative(face);
                    blockLoc = centreOf(placedAt);

                    // Material being placed = item in main hand (if it's a block)
                    ItemStack held = player.getInventory().getItemInMainHand();
                    if (held.getType().isBlock()) mat = held.getType().name();
                    else mat = Material.AIR.name();

                } else if (action == Action.LEFT_CLICK_BLOCK) {
                    // Record the block that was struck
                    blockLoc = centreOf(clicked);
                    mat      = clicked.getType().name();
                }
            }

            recSession.setPendingAction(pa, blockLoc, face, mat);
        }

        // ── Evaluation session ─────────────────────────────────────────────
        EvaluationSession evalSession =
            plugin.getEvaluationManager().getSession(player.getUniqueId());
        if (evalSession != null) {
            evalSession.onPlayerAction(pa);
        }
    }

    // ── PlayerToggleSprintEvent ───────────────────────────────────────────────

    /**
     * Forwards sprint state changes to the W-Tap session.
     * MONITOR priority ensures we see the final state.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSprintToggle(PlayerToggleSprintEvent event) {
        WTapSession session =
            plugin.getTrainingManager().getSession(event.getPlayer().getUniqueId());
        if (session != null) session.onSprintToggle(event.isSprinting());
    }

    // ── PlayerQuitEvent ───────────────────────────────────────────────────────

    /**
     * Cleans up ALL session types when a player disconnects so no dummy entities
     * or orphaned tasks are left in the world.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        // W-Tap
        WTapSession wSession = plugin.getTrainingManager().getSession(player.getUniqueId());
        if (wSession != null) {
            plugin.getTrainingManager().onSessionComplete(player.getUniqueId());
            wSession.stop(false);
        }

        // Evaluation
        EvaluationSession evalSession =
            plugin.getEvaluationManager().getSession(player.getUniqueId());
        if (evalSession != null) {
            plugin.getEvaluationManager().onSessionComplete(player.getUniqueId());
            evalSession.stop(false);
        }

        // Recording — save on disconnect to avoid losing data
        plugin.getRecordingManager().stopSession(player, false).ifPresent(rec -> {
            try {
                plugin.getRecordingManager().save(rec);
                plugin.getLogger().info("Auto-saved recording '" + rec.getName()
                    + "' after " + player.getName() + " disconnected.");
            } catch (Exception e) {
                plugin.getLogger().warning(
                    "Could not auto-save recording for " + player.getName() + ": " + e.getMessage());
            }
        });

        // Playback
        plugin.getPlaybackManager().stop(player);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static PlayerAction toPlayerAction(Action action) {
        return switch (action) {
            case LEFT_CLICK_AIR   -> PlayerAction.LEFT_CLICK_AIR;
            case LEFT_CLICK_BLOCK -> PlayerAction.LEFT_CLICK_BLOCK;
            case RIGHT_CLICK_AIR  -> PlayerAction.RIGHT_CLICK_AIR;
            case RIGHT_CLICK_BLOCK -> PlayerAction.RIGHT_CLICK_BLOCK;
            default               -> PlayerAction.NONE;
        };
    }

    /** Returns the centre of a block's top face — good for block target coordinates. */
    private static Location centreOf(Block block) {
        return new Location(
            block.getWorld(),
            block.getX() + 0.5,
            block.getY(),          // Y is bottom of block; relative coords stay consistent
            block.getZ() + 0.5
        );
    }
}
