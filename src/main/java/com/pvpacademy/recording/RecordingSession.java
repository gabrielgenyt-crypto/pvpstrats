package com.pvpacademy.recording;

import com.pvpacademy.PvPAcademy;
import com.pvpacademy.model.BlockTarget;
import com.pvpacademy.model.PlayerAction;
import com.pvpacademy.model.Recording;
import com.pvpacademy.model.TickFrame;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

/**
 * Records an admin's movement and actions tick-by-tick.
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>Created and started by {@link RecordingManager#startSession}.</li>
 *   <li>Runs a 1-tick repeating task that snapshots the player state each tick.</li>
 *   <li>{@link com.pvpacademy.listener.TrainingListener} calls
 *       {@link #setPendingAction} between ticks to inject interaction events.</li>
 *   <li>Stopped by {@link RecordingManager#stopSession}, which returns the
 *       completed {@link Recording}.</li>
 * </ol>
 *
 * <h3>Relative positions</h3>
 * <p>The admin's location at tick 0 is stored as the <em>origin</em>.  All
 * subsequent positions are stored as deltas from that origin so the recording
 * is portable to any arena location.</p>
 */
public final class RecordingSession {

    private final PvPAcademy plugin;
    private final Player     player;
    private final Recording  recording;

    private final double originX;
    private final double originY;
    private final double originZ;

    private int        currentTick  = 0;
    private boolean    active       = false;
    private BukkitTask task;

    // Set by TrainingListener between ticks; read and cleared each tick frame.
    // Block info is stored as ABSOLUTE world coordinates; converted to relative in captureTick.
    private PlayerAction   pendingAction     = PlayerAction.NONE;
    private @Nullable Location pendingBlockLoc  = null;   // absolute world location of the block
    private @Nullable BlockFace pendingBlockFace = null;
    private @Nullable String    pendingBlockMat  = null;  // Material.name() of the placed/hit block

    // Tracks hotbar slot to detect changes
    private int lastSlot = -1;

    // ── Constructor ───────────────────────────────────────────────────────────

    public RecordingSession(PvPAcademy plugin, Player player, String name) {
        this.plugin  = plugin;
        this.player  = player;

        Location origin = player.getLocation();
        this.originX = origin.getX();
        this.originY = origin.getY();
        this.originZ = origin.getZ();

        this.recording = new Recording(
            name,
            originX, originY, originZ,
            origin.getWorld().getName()
        );
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Starts the tick-capture loop and notifies the admin. */
    void start() {
        active   = true;
        lastSlot = player.getInventory().getHeldItemSlot();

        player.sendMessage(
            Component.text("● REC  ", NamedTextColor.RED)
                     .append(Component.text("Recording '", NamedTextColor.GREEN))
                     .append(Component.text(recording.getName(), NamedTextColor.WHITE))
                     .append(Component.text("' started — /pvpa record stop when done.",
                                            NamedTextColor.GREEN)));

        task = Bukkit.getScheduler().runTaskTimer(plugin, this::captureTick, 0L, 1L);
    }

    /**
     * Stops the tick-capture loop and returns the completed recording.
     *
     * @param notify whether to send a completion message to the admin
     */
    Recording stop(boolean notify) {
        if (!active) return recording;
        active = false;

        if (task != null && !task.isCancelled()) task.cancel();

        if (notify) {
            player.sendMessage(
                Component.text("■ STOP  ", NamedTextColor.GRAY)
                         .append(Component.text("Recording '", NamedTextColor.GREEN))
                         .append(Component.text(recording.getName(), NamedTextColor.WHITE))
                         .append(Component.text("' saved — ", NamedTextColor.GREEN))
                         .append(Component.text(recording.getTotalTicks() + " ticks.",
                                                NamedTextColor.WHITE)));
        }

        return recording;
    }

    // ── Per-tick capture ──────────────────────────────────────────────────────

    private void captureTick() {
        Location loc  = player.getLocation();
        int      slot = player.getInventory().getHeldItemSlot();

        double relX = loc.getX() - originX;
        double relY = loc.getY() - originY;
        double relZ = loc.getZ() - originZ;

        // If the slot changed this tick and no other action was recorded, mark it
        PlayerAction action = pendingAction;
        if (action == PlayerAction.NONE && slot != lastSlot) {
            action = PlayerAction.SLOT_CHANGE;
        }
        lastSlot = slot;

        // Convert absolute block location to relative coords (if present)
        BlockTarget blockTarget = null;
        if (pendingBlockLoc != null) {
            blockTarget = new BlockTarget(
                pendingBlockLoc.getX() - originX,
                pendingBlockLoc.getY() - originY,
                pendingBlockLoc.getZ() - originZ,
                pendingBlockFace != null ? pendingBlockFace : BlockFace.SELF,
                pendingBlockMat != null ? pendingBlockMat : "AIR"
            );
        }

        recording.addFrame(new TickFrame(
            currentTick,
            relX, relY, relZ,
            loc.getYaw(), loc.getPitch(),
            action,
            slot,
            blockTarget
        ));

        // Reset for next tick
        pendingAction     = PlayerAction.NONE;
        pendingBlockLoc   = null;
        pendingBlockFace  = null;
        pendingBlockMat   = null;
        currentTick++;
    }

    // ── Event callback ────────────────────────────────────────────────────────

    /**
     * Called by {@link com.pvpacademy.listener.TrainingListener} when the
     * player interacts with a block or the air while recording.
     *
     * <p>If multiple events fire within the same tick (rare but possible),
     * the higher-ordinal {@link PlayerAction} takes precedence (block
     * interactions win over air interactions).</p>
     *
     * @param action        the interaction type
     * @param absoluteBlock absolute world location of the block (null for air interactions)
     * @param face          the block face that was targeted (null for air interactions)
     * @param material      Bukkit {@link org.bukkit.Material#name()} string (null for air)
     */
    public void setPendingAction(PlayerAction action,
                                  @Nullable Location absoluteBlock,
                                  @Nullable BlockFace face,
                                  @Nullable String material) {
        if (action.ordinal() > pendingAction.ordinal()) {
            pendingAction    = action;
            pendingBlockLoc  = absoluteBlock;
            pendingBlockFace = face;
            pendingBlockMat  = material;
        }
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public Player  getPlayer()        { return player; }
    public boolean isActive()         { return active; }
    public String  getRecordingName() { return recording.getName(); }
}
