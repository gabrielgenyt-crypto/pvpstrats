package com.pvpacademy.evaluation;

import com.pvpacademy.PvPAcademy;
import com.pvpacademy.model.PlayerAction;
import com.pvpacademy.model.Recording;
import com.pvpacademy.model.Strategy;
import com.pvpacademy.model.TickFrame;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Evaluates a player's move execution against the checkpoints of a recorded strategy.
 *
 * <h3>Checkpoint model</h3>
 * <p>Checkpoints are frames in the recording where
 * {@link TickFrame#action()} is not {@link PlayerAction#NONE}.
 * Each player action is scored against the <em>next expected checkpoint</em>
 * using the relative tick delta between the player's actions and the
 * recording's checkpoint timing.</p>
 *
 * <h3>Scoring</h3>
 * <pre>
 *   |delta| ≤ strategy.toleranceTicks() → PERFECT
 *   |delta| ≤ strategy.goodTolerance()  → GOOD
 *   otherwise                           → MISS
 * </pre>
 *
 * <h3>Rolling baseline</h3>
 * <p>After each checkpoint is processed, the timing baseline resets to that
 * checkpoint — so each move is judged relative to the previous one, not the
 * absolute sequence start.</p>
 */
public final class EvaluationSession {

    // ── State machine ─────────────────────────────────────────────────────────
    private enum State { WAITING, IN_PROGRESS, COMPLETE }

    // ── Core refs ─────────────────────────────────────────────────────────────
    private final PvPAcademy         plugin;
    private final Player             player;
    private final Strategy           strategy;
    private final List<TickFrame>    checkpoints;
    /** Called with the player UUID when the session ends (natural or manual). */
    private final Consumer<java.util.UUID> onComplete;

    // ── Arena ─────────────────────────────────────────────────────────────────
    private Location returnLocation;
    private Zombie   dummy;

    // ── Timing ────────────────────────────────────────────────────────────────
    private long lastCheckpointPlayerTick    = -1;
    private long lastCheckpointRecordingTick = -1;

    // ── Progress ──────────────────────────────────────────────────────────────
    private State state                = State.WAITING;
    private int   currentCpIndex      = 0;
    private int   perfectCount        = 0;
    private int   goodCount           = 0;
    private int   missCount           = 0;
    private final List<CpResult> results = new ArrayList<>();

    // ── Action bar ────────────────────────────────────────────────────────────
    private Component  actionBarOverride    = Component.empty();
    private int        actionBarOverrideTtl = 0;
    private BukkitTask actionBarTask;

    // ── Constructor ───────────────────────────────────────────────────────────

    public EvaluationSession(PvPAcademy plugin, Player player,
                              Strategy strategy, Recording recording,
                              Consumer<java.util.UUID> onComplete) {
        this.plugin      = plugin;
        this.player      = player;
        this.strategy    = strategy;
        this.checkpoints = recording.getCheckpoints();
        this.onComplete  = onComplete;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void start() {
        returnLocation = player.getLocation().clone();

        if (checkpoints.isEmpty()) {
            player.sendMessage(Component.text(
                "[PvP Academy] Strategy '" + strategy.name() + "' has no checkpoints yet.",
                NamedTextColor.RED));
            return;
        }

        World world = setupArena();
        if (world == null) return;   // config error already reported

        spawnDummy(world);
        sendIntro();
        startActionBarLoop();
    }

    /**
     * Stops the session, removes the dummy, and returns the player.
     *
     * @param showMessage whether to print a "session ended" message
     */
    public void stop(boolean showMessage) {
        if (actionBarTask != null && !actionBarTask.isCancelled()) actionBarTask.cancel();
        if (dummy != null && dummy.isValid()) dummy.remove();
        if (returnLocation != null) player.teleport(returnLocation);

        if (showMessage) {
            player.sendMessage(Component.text(
                "Training session ended. Returning to your previous location.",
                NamedTextColor.GRAY));
        }
    }

    // ── Event callback ────────────────────────────────────────────────────────

    /**
     * Called by {@link com.pvpacademy.listener.TrainingListener} when the
     * player performs a left-click or right-click while in an evaluation session.
     *
     * <p>Slot changes and {@link PlayerAction#NONE} are ignored.</p>
     */
    public void onPlayerAction(PlayerAction action) {
        if (action == PlayerAction.NONE || action == PlayerAction.SLOT_CHANGE) return;
        if (state == State.COMPLETE) return;

        long now = plugin.getCurrentTick();

        if (state == State.WAITING) {
            // First action from the player — initialise the rolling baseline
            lastCheckpointPlayerTick    = now;
            lastCheckpointRecordingTick = checkpoints.get(0).tick();
            state = State.IN_PROGRESS;

            // Checkpoint 0 is the trigger — always PERFECT
            recordResult(HitGrade.PERFECT, 0L, "Sequence started!");
            currentCpIndex = 1;

            if (currentCpIndex >= checkpoints.size()) finishSession();
            return;
        }

        if (currentCpIndex >= checkpoints.size()) return;

        TickFrame expected = checkpoints.get(currentCpIndex);

        long playerRel   = now - lastCheckpointPlayerTick;
        long expectedRel = expected.tick() - lastCheckpointRecordingTick;
        long diff        = playerRel - expectedRel; // + = late, - = early
        long absDiff     = Math.abs(diff);

        HitGrade grade;
        String   note;

        if (absDiff <= strategy.toleranceTicks()) {
            grade = HitGrade.PERFECT;
            note  = diff == 0 ? "Exactly on time!"
                  : diff >  0 ? diff + " tick" + (absDiff == 1 ? "" : "s") + " late."
                              : absDiff + " tick" + (absDiff == 1 ? "" : "s") + " early.";
        } else if (absDiff <= strategy.goodTolerance()) {
            grade = HitGrade.GOOD;
            note  = diff > 0
                  ? diff + " ticks late — tighten up."
                  : absDiff + " ticks early — slow down a bit.";
        } else {
            grade = HitGrade.MISS;
            note  = diff > 0
                  ? diff + " ticks late — too slow!"
                  : absDiff + " ticks early — too fast!";
        }

        // Update rolling baseline
        lastCheckpointPlayerTick    = now;
        lastCheckpointRecordingTick = expected.tick();

        recordResult(grade, diff, note);
        sendCpFeedback(grade, note);
        currentCpIndex++;

        if (currentCpIndex >= checkpoints.size()) finishSession();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private World setupArena() {
        String worldName = plugin.getConfig().getString("arena.world", "world");
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            player.sendMessage(Component.text(
                "[PvP Academy] World '" + worldName + "' not found — check config.yml.",
                NamedTextColor.RED));
            return null;
        }

        double ax = plugin.getConfig().getDouble("arena.x", 30_000.5);
        double ay = plugin.getConfig().getDouble("arena.y", 200.0);
        double az = plugin.getConfig().getDouble("arena.z", 30_000.5);
        Location arenaBase = new Location(world, ax, ay, az);

        // Build platform
        int bx = arenaBase.getBlockX(), by = arenaBase.getBlockY(), bz = arenaBase.getBlockZ();
        for (int x = -6; x <= 6; x++) {
            for (int z = -2; z <= 8; z++) {
                world.getBlockAt(bx + x, by - 1, bz + z).setType(Material.SMOOTH_STONE, false);
                for (int y = 0; y <= 4; y++) {
                    world.getBlockAt(bx + x, by + y, bz + z).setType(Material.AIR, false);
                }
            }
        }

        Location playerSpot = arenaBase.clone();
        playerSpot.setYaw(0f);
        playerSpot.setPitch(5f);
        player.teleport(playerSpot);
        return world;
    }

    private void spawnDummy(World world) {
        double ax = plugin.getConfig().getDouble("arena.x", 30_000.5);
        double ay = plugin.getConfig().getDouble("arena.y", 200.0);
        double az = plugin.getConfig().getDouble("arena.z", 30_000.5);
        Location dummyLoc = new Location(world, ax, ay, az + 3);

        dummy = world.spawn(dummyLoc, Zombie.class, z -> {
            z.setAI(false);
            z.setSilent(true);
            z.setBaby(false);
            z.setRemoveWhenFarAway(false);
            z.customName(Component.text("◉ DUMMY", NamedTextColor.RED)
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
    }

    private void sendIntro() {
        int total = checkpoints.size();
        player.sendMessage(Component.empty());
        player.sendMessage(divider(NamedTextColor.GOLD));
        player.sendMessage(Component.text(
            "  " + strategy.displayName().toUpperCase() + " — EVALUATION",
            NamedTextColor.GOLD, TextDecoration.BOLD));
        player.sendMessage(divider(NamedTextColor.GOLD));
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text(strategy.description(), NamedTextColor.YELLOW));
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text(
            "  Checkpoints  ", NamedTextColor.AQUA)
            .append(Component.text(total + " moves to replicate", NamedTextColor.WHITE)));
        player.sendMessage(Component.text(
            "  ⚡ Perfect    ", NamedTextColor.GREEN)
            .append(Component.text("±" + strategy.toleranceTicks() + " ticks", NamedTextColor.WHITE)));
        player.sendMessage(Component.text(
            "  ✓ Good        ", NamedTextColor.YELLOW)
            .append(Component.text("±" + strategy.goodTolerance() + " ticks", NamedTextColor.WHITE)));
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text(
            "Perform your first move to begin the sequence!", NamedTextColor.AQUA));
        player.sendMessage(divider(NamedTextColor.GOLD));
        player.sendMessage(Component.empty());
    }

    private void sendCpFeedback(HitGrade grade, String note) {
        Component msg = switch (grade) {
            case PERFECT -> Component.text("⚡ PERFECT!  ", NamedTextColor.GREEN, TextDecoration.BOLD)
                            .append(Component.text(note, NamedTextColor.GREEN));
            case GOOD    -> Component.text("✓ Good   ", NamedTextColor.YELLOW)
                            .append(Component.text(note, NamedTextColor.YELLOW));
            case MISS    -> Component.text("✗ Miss   ", NamedTextColor.RED)
                            .append(Component.text(note, NamedTextColor.RED));
        };

        actionBarOverride    = msg;
        actionBarOverrideTtl = 20;

        switch (grade) {
            case PERFECT -> {
                player.playSound(player.getLocation(),
                    Sound.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 1f, 1.6f);
                showPerfectTitle(note);
            }
            case GOOD -> player.playSound(player.getLocation(),
                    Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.PLAYERS, 1f, 1.2f);
            case MISS -> player.playSound(player.getLocation(),
                    Sound.BLOCK_NOTE_BLOCK_BASS, SoundCategory.PLAYERS, 1f, 0.5f);
        }
    }

    private void showPerfectTitle(String sub) {
        player.showTitle(Title.title(
            Component.text("PERFECT!", NamedTextColor.GREEN, TextDecoration.BOLD),
            Component.text("Move " + currentCpIndex + "/" + checkpoints.size(), NamedTextColor.GRAY),
            Title.Times.times(Duration.ofMillis(50), Duration.ofMillis(600), Duration.ofMillis(150))
        ));
    }

    private void startActionBarLoop() {
        actionBarTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (actionBarOverrideTtl > 0) {
                player.sendActionBar(actionBarOverride);
                actionBarOverrideTtl -= 2;
            } else {
                player.sendActionBar(buildProgressBar());
            }
        }, 0L, 2L);
    }

    private Component buildProgressBar() {
        if (state == State.WAITING) {
            return Component.text(
                "Perform your first move to start the sequence!",
                NamedTextColor.AQUA);
        }
        TextComponent.Builder b = Component.text();
        for (int i = 0; i < checkpoints.size(); i++) {
            if (i < results.size()) {
                b.append(switch (results.get(i).grade()) {
                    case PERFECT -> Component.text("■", NamedTextColor.GREEN);
                    case GOOD    -> Component.text("■", NamedTextColor.YELLOW);
                    case MISS    -> Component.text("■", NamedTextColor.RED);
                });
            } else {
                b.append(Component.text("□", NamedTextColor.DARK_GRAY));
            }
            if (i < checkpoints.size() - 1) b.append(Component.text(" "));
        }
        b.append(Component.text(
            "  (" + Math.min(currentCpIndex, checkpoints.size()) + "/" + checkpoints.size() + ")",
            NamedTextColor.GRAY));
        return b.build();
    }

    private void recordResult(HitGrade grade, long diff, String note) {
        results.add(new CpResult(grade, diff, note));
        switch (grade) {
            case PERFECT -> perfectCount++;
            case GOOD    -> goodCount++;
            case MISS    -> missCount++;
        }
    }

    private void finishSession() {
        state = State.COMPLETE;
        Bukkit.getScheduler().runTaskLater(plugin, this::showSummary, 40L);
    }

    private void showSummary() {
        if (actionBarTask != null && !actionBarTask.isCancelled()) actionBarTask.cancel();

        int    total = checkpoints.size();
        double score = ((perfectCount * 100.0) + (goodCount * 60.0)) / total;
        String rank  = getRank(score);
        NamedTextColor col = getRankColor(score);

        player.sendMessage(Component.empty());
        player.sendMessage(divider(NamedTextColor.GOLD));
        player.sendMessage(Component.text(
            "  " + strategy.displayName().toUpperCase() + " — COMPLETE",
            NamedTextColor.GOLD, TextDecoration.BOLD));
        player.sendMessage(divider(NamedTextColor.GOLD));
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("  ⚡ Perfect  ", NamedTextColor.GREEN)
                            .append(Component.text(perfectCount + " / " + total, NamedTextColor.WHITE)));
        player.sendMessage(Component.text("  ✓ Good     ", NamedTextColor.YELLOW)
                            .append(Component.text(goodCount + " / " + total, NamedTextColor.WHITE)));
        player.sendMessage(Component.text("  ✗ Miss     ", NamedTextColor.RED)
                            .append(Component.text(missCount + " / " + total, NamedTextColor.WHITE)));
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("  Score   ", NamedTextColor.AQUA)
                            .append(Component.text(String.format("%.0f%%", score), col)));
        player.sendMessage(Component.text("  Rank    ", NamedTextColor.AQUA)
                            .append(Component.text(rank, col, TextDecoration.BOLD)));
        player.sendMessage(divider(NamedTextColor.GOLD));
        player.sendMessage(Component.text(
            "Returning in 5 s — /pvpa stop to go now.", NamedTextColor.GRAY));

        player.playSound(player.getLocation(),
            Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 1f, 1f);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            onComplete.accept(player.getUniqueId());
            stop(false);
        }, 100L);
    }

    private static Component divider(NamedTextColor color) {
        return Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━", color);
    }

    private String getRank(double score) {
        if (score >= 90) return "S  —  Master";
        if (score >= 80) return "A  —  Expert";
        if (score >= 70) return "B  —  Proficient";
        if (score >= 50) return "C  —  Learner";
        return "D  —  Beginner";
    }

    private NamedTextColor getRankColor(double score) {
        if (score >= 90) return NamedTextColor.GREEN;
        if (score >= 70) return NamedTextColor.YELLOW;
        if (score >= 50) return NamedTextColor.GOLD;
        return NamedTextColor.RED;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public Player getPlayer() { return player; }
    public Zombie getDummy()  { return dummy; }

    // ── Nested types ──────────────────────────────────────────────────────────

    public enum HitGrade { PERFECT, GOOD, MISS }

    /**
     * Result of one checkpoint evaluation.
     *
     * @param grade quality classification
     * @param diff  tick delta (positive = late, negative = early)
     * @param note  human-readable explanation
     */
    public record CpResult(HitGrade grade, long diff, String note) {}
}
