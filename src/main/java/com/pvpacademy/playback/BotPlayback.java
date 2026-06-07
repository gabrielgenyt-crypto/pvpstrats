package com.pvpacademy.playback;

import com.pvpacademy.PvPAcademy;
import com.pvpacademy.model.BlockTarget;
import com.pvpacademy.model.Recording;
import com.pvpacademy.model.TickFrame;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Replays a {@link Recording} using a Zombie entity as the demo bot.
 *
 * <h3>How playback works</h3>
 * <ul>
 *   <li>A Zombie (AI-disabled) is spawned at the playback origin.</li>
 *   <li>Every server tick, the bot is teleported to the next recorded position
 *       (relative coords + playback origin).</li>
 *   <li>Block-placement frames execute a real {@code setType} call so viewers
 *       see the blocks appear in real time.</li>
 *   <li>Attack frames trigger particle and sound effects.</li>
 *   <li>When playback finishes (or is stopped early), placed blocks are
 *       restored to their original material.</li>
 * </ul>
 */
public final class BotPlayback {

    private final PvPAcademy      plugin;
    private final UUID            ownerUuid;
    private final Recording       recording;
    private final World           world;
    /** Called with the owner UUID when playback ends (natural or manual). */
    private final Consumer<UUID>  onComplete;

    /** Absolute world coordinates of the playback start point. */
    private final double originX;
    private final double originY;
    private final double originZ;

    private Zombie     botEntity;
    private BukkitTask task;
    private boolean    active      = false;
    private int        currentTick = 0;

    /**
     * Blocks placed during playback: {@code "x,y,z" → original Material}.
     * Used to restore the world when playback ends.
     */
    private final Map<String, Material> placedBlocks = new HashMap<>();

    // ── Constructor ───────────────────────────────────────────────────────────

    public BotPlayback(PvPAcademy plugin, UUID ownerUuid,
                       Recording recording, Location playbackOrigin,
                       Consumer<UUID> onComplete) {
        this.plugin      = plugin;
        this.ownerUuid   = ownerUuid;
        this.recording   = recording;
        this.world       = playbackOrigin.getWorld();
        this.originX     = playbackOrigin.getX();
        this.originY     = playbackOrigin.getY();
        this.originZ     = playbackOrigin.getZ();
        this.onComplete  = onComplete;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Spawns the bot and begins the tick-by-tick playback loop. */
    public void start() {
        if (recording.getTicks().isEmpty()) return;

        active = true;

        TickFrame first = recording.getTicks().get(0);
        Location  spawn = toWorldLocation(first);

        botEntity = world.spawn(spawn, Zombie.class, z -> {
            z.setAI(false);
            z.setSilent(true);
            z.setBaby(false);
            z.setRemoveWhenFarAway(false);

            z.customName(Component.text("► " + recording.getName(), NamedTextColor.AQUA)
                          .decorate(TextDecoration.BOLD));
            z.setCustomNameVisible(true);

            AttributeInstance maxHp = z.getAttribute(Attribute.MAX_HEALTH);
            if (maxHp != null) maxHp.setBaseValue(2000.0);
            z.setHealth(2000.0);

            EntityEquipment eq = z.getEquipment();
            if (eq != null) {
                eq.setHelmet(new ItemStack(Material.LEATHER_HELMET));
                eq.setChestplate(new ItemStack(Material.LEATHER_CHESTPLATE));
                eq.setLeggings(new ItemStack(Material.LEATHER_LEGGINGS));
                eq.setBoots(new ItemStack(Material.LEATHER_BOOTS));
                eq.setHelmetDropChance(0f);
                eq.setChestplateDropChance(0f);
                eq.setLeggingsDropChance(0f);
                eq.setBootsDropChance(0f);
            }
        });

        task = Bukkit.getScheduler().runTaskTimer(plugin, this::replayTick, 0L, 1L);
    }

    /**
     * Stops playback, optionally restores placed blocks, and notifies the manager.
     *
     * @param restoreBlocks whether to undo blocks placed during the demo
     */
    public void stop(boolean restoreBlocks) {
        if (!active) return;
        active = false;

        if (task != null && !task.isCancelled()) task.cancel();
        if (botEntity != null && botEntity.isValid()) botEntity.remove();

        if (restoreBlocks) restorePlacedBlocks();

        onComplete.accept(ownerUuid);
    }

    // ── Per-tick replay ───────────────────────────────────────────────────────

    private void replayTick() {
        if (!active) return;

        if (currentTick >= recording.getTotalTicks()) {
            stop(true);
            return;
        }

        TickFrame frame = recording.getTicks().get(currentTick);

        // Move bot
        botEntity.teleport(toWorldLocation(frame));

        // Execute action visual
        switch (frame.action()) {
            case LEFT_CLICK_AIR, LEFT_CLICK_BLOCK -> playAttackEffect();
            case RIGHT_CLICK_BLOCK -> {
                if (frame.blockTarget() != null) placeRecordedBlock(frame.blockTarget());
            }
            case RIGHT_CLICK_AIR ->
                world.spawnParticle(Particle.SWEEP_ATTACK,
                    botEntity.getLocation().add(0, 1, 0), 3, 0.2, 0.2, 0.2, 0);
            default -> { /* NONE / SLOT_CHANGE — no effect */ }
        }

        currentTick++;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void playAttackEffect() {
        Location loc = botEntity.getLocation().add(0, 1, 0);
        world.spawnParticle(Particle.CRIT, loc, 8, 0.3, 0.3, 0.3, 0);
        world.playSound(botEntity.getLocation(),
            Sound.ENTITY_PLAYER_ATTACK_CRIT, SoundCategory.PLAYERS, 0.8f, 1.0f);
    }

    private void placeRecordedBlock(BlockTarget target) {
        int bx = (int) Math.floor(originX + target.relX());
        int by = (int) Math.floor(originY + target.relY());
        int bz = (int) Math.floor(originZ + target.relZ());

        var block = world.getBlockAt(bx, by, bz);
        String key = bx + "," + by + "," + bz;

        // Save original so we can restore later
        placedBlocks.putIfAbsent(key, block.getType());

        try {
            Material mat = Material.valueOf(target.material());
            if (mat.isBlock()) block.setType(mat, false);
        } catch (IllegalArgumentException ignored) {
            // Unknown material name — skip silently
        }
    }

    private void restorePlacedBlocks() {
        placedBlocks.forEach((key, original) -> {
            String[] p = key.split(",");
            world.getBlockAt(
                Integer.parseInt(p[0]),
                Integer.parseInt(p[1]),
                Integer.parseInt(p[2])
            ).setType(original, false);
        });
        placedBlocks.clear();
    }

    private Location toWorldLocation(TickFrame frame) {
        return new Location(
            world,
            originX + frame.relX(),
            originY + frame.relY(),
            originZ + frame.relZ(),
            frame.yaw(),
            frame.pitch()
        );
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public boolean isActive() { return active; }
}
