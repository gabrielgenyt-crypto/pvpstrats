package com.pvpacademy.training;

import com.pvpacademy.PvPAcademy;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.*;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * A single W-Tap training session.
 *
 * <h3>What is a W-Tap?</h3>
 * <p>Sprint toward a target, release W (stop sprinting) 0–2 ticks before your
 * sword connects, then immediately re-press W.  The brief sprint-reset applies
 * the full sprint-knockback multiplier to the hit.</p>
 *
 * <h3>How scoring works</h3>
 * <ul>
 *   <li><b>PERFECT</b> — W released 0–{perfectMaxTicks} ticks before attack</li>
 *   <li><b>GOOD</b> — W released {perfectMaxTicks+1}–{goodMaxTicks} ticks before attack</li>
 *   <li><b>MISS</b> — still sprinting at attack time, or released W too early</li>
 * </ul>
 */
public final class WTapSession {

    // ── Config constants ──────────────────────────────────────────────────────
    private final int    totalHits;
    private final int    perfectMaxTicks;
    private final int    goodMaxTicks;
    private static final double DUMMY_MAX_HEALTH = 2000.0;

    // ── Core references ───────────────────────────────────────────────────────
    private final PvPAcademy plugin;
    private final Player      player;

    // ── Arena state ───────────────────────────────────────────────────────────
    private Zombie   dummy;
    private Location returnLocation;

    /** Guards against double-stop (e.g. auto-return races with /pvpa stop). */
    private boolean stopped = false;

    // ── Sprint timing ─────────────────────────────────────────────────────────
    /** Tick when the player last started sprinting.  Long.MIN_VALUE = never. */
    private long lastSprintStartTick = Long.MIN_VALUE;
    /** Tick when the player last stopped sprinting.  Long.MIN_VALUE = never. */
    private long lastSprintStopTick  = Long.MIN_VALUE;

    // ── Hit tracking ──────────────────────────────────────────────────────────
    private int                 hitCount     = 0;
    private int                 perfectCount = 0;
    private int                 goodCount    = 0;
    private int                 missCount    = 0;
    private final List<HitResult> results    = new ArrayList<>();

    // ── Action bar ────────────────────────────────────────────────────────────
    private Component  actionBarOverride    = null;
    private int        actionBarOverrideTtl = 0;   // ticks remaining for override
    private BukkitTask actionBarTask;

    // ── Constructor ───────────────────────────────────────────────────────────

    public WTapSession(PvPAcademy plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
        this.totalHits       = plugin.getConfig().getInt("wtap.total-hits", 10);
        this.perfectMaxTicks = plugin.getConfig().getInt("wtap.perfect-tick-range", 2);
        this.goodMaxTicks    = plugin.getConfig().getInt("wtap.good-tick-range", 5);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Sets up the arena, teleports the player, and starts the session. */
    public void start() {
        returnLocation = player.getLocation().clone();

        String worldName = plugin.getConfig().getString("arena.world", "world");
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            player.sendMessage(Component.text(
                "[PvP Academy] World '" + worldName + "' not found — check config.yml.",
                NamedTextColor.RED));
            return;
        }

        double ax = plugin.getConfig().getDouble("arena.x", 30_000.5);
        double ay = plugin.getConfig().getDouble("arena.y", 200.0);
        double az = plugin.getConfig().getDouble("arena.z", 30_000.5);
        Location arenaBase = new Location(world, ax, ay, az);

        buildPlatform(world, arenaBase);
        spawnDummy(world, arenaBase.clone().add(0, 0, 3));

        // Place player at the arena base, facing +Z toward the dummy
        Location playerSpot = arenaBase.clone();
        playerSpot.setYaw(0f);
        playerSpot.setPitch(5f);
        player.teleport(playerSpot);

        sendIntro();
        startActionBarLoop();
    }

    /**
     * Ends the session: cancels tasks, removes the dummy, teleports the player back.
     *
     * @param showMessage print a short info message to the player when {@code true}.
     *                    Pass {@code false} on server shutdown to avoid spam.
     */
    public void stop(boolean showMessage) {
        if (stopped) return;
        stopped = true;

        if (actionBarTask != null && !actionBarTask.isCancelled()) actionBarTask.cancel();
        if (dummy != null && dummy.isValid()) dummy.remove();
        if (returnLocation != null) player.teleport(returnLocation);

        if (showMessage) {
            player.sendMessage(Component.text(
                "Training stopped. Returning to your previous location.",
                NamedTextColor.GRAY));
        }
    }

    // ── Event callbacks ───────────────────────────────────────────────────────

    /**
     * Called by {@link com.pvpacademy.listener.TrainingListener} when the player
     * deals melee damage to an entity.
     *
     * @param target the entity that was hit
     */
    public void onAttack(Entity target) {
        if (dummy == null || !target.getUniqueId().equals(dummy.getUniqueId())) return;
        if (hitCount >= totalHits) return;

        long attackTick = plugin.getCurrentTick();

        // Keep the dummy alive in case it somehow lost health
        if (dummy.getHealth() < DUMMY_MAX_HEALTH / 2) dummy.setHealth(DUMMY_MAX_HEALTH);

        HitResult result = evaluateTiming(attackTick);
        results.add(result);
        hitCount++;

        switch (result.grade()) {
            case PERFECT -> perfectCount++;
            case GOOD    -> goodCount++;
            case MISS    -> missCount++;
        }

        sendHitFeedback(result);

        if (hitCount >= totalHits) {
            // Wait 2 s so the last hit-feedback message is visible, then show summary
            Bukkit.getScheduler().runTaskLater(plugin, this::showSummary, 40L);
        }
    }

    /**
     * Called by {@link com.pvpacademy.listener.TrainingListener} when the player's
     * sprint state changes.
     *
     * @param sprinting {@code true} when the player just started sprinting
     */
    public void onSprintToggle(boolean sprinting) {
        long tick = plugin.getCurrentTick();
        if (sprinting) {
            lastSprintStartTick = tick;
        } else {
            lastSprintStopTick = tick;
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Builds a flat 13×11 platform of Smooth Stone below the arena base. */
    private void buildPlatform(World world, Location base) {
        int bx = base.getBlockX();
        int by = base.getBlockY();
        int bz = base.getBlockZ();

        for (int x = -6; x <= 6; x++) {
            for (int z = -2; z <= 8; z++) {
                // Floor one block below the player's feet
                world.getBlockAt(bx + x, by - 1, bz + z)
                     .setType(Material.SMOOTH_STONE, false);
                // Clear 5 blocks of headroom
                for (int y = 0; y <= 4; y++) {
                    world.getBlockAt(bx + x, by + y, bz + z)
                         .setType(Material.AIR, false);
                }
            }
        }
    }

    private void spawnDummy(World world, Location loc) {
        dummy = world.spawn(loc, Zombie.class, z -> {
            z.setAI(false);
            z.setSilent(true);
            z.setBaby(false);
            z.setRemoveWhenFarAway(false);

            z.customName(Component.text("◉ DUMMY", NamedTextColor.RED)
                          .decorate(TextDecoration.BOLD));
            z.setCustomNameVisible(true);

            // High max-health so the dummy never dies during a session.
            // Attribute.MAX_HEALTH is the correct name in Paper 1.21.4.
            AttributeInstance maxHp = z.getAttribute(Attribute.MAX_HEALTH);
            if (maxHp != null) maxHp.setBaseValue(DUMMY_MAX_HEALTH);
            z.setHealth(DUMMY_MAX_HEALTH);

            // Leather armour — makes it look like a training dummy
            EntityEquipment eq = z.getEquipment();
            if (eq != null) {
                eq.setHelmet(new ItemStack(Material.LEATHER_HELMET));
                eq.setChestplate(new ItemStack(Material.LEATHER_CHESTPLATE));
                eq.setLeggings(new ItemStack(Material.LEATHER_LEGGINGS));
                eq.setBoots(new ItemStack(Material.LEATHER_BOOTS));
                // Prevent drops if the dummy ever dies
                eq.setHelmetDropChance(0f);
                eq.setChestplateDropChance(0f);
                eq.setLeggingsDropChance(0f);
                eq.setBootsDropChance(0f);
            }
        });
    }

    /**
     * Core timing evaluation for a single hit.
     *
     * <p>Compares {@code attackTick} against the tick when the player last released
     * W to produce a PERFECT/GOOD/MISS result with a human-readable note.</p>
     */
    private HitResult evaluateTiming(long attackTick) {
        // If most recent sprint event was a start, the player is still sprinting
        boolean currentlySprinting = lastSprintStartTick > lastSprintStopTick
                                     || lastSprintStopTick == Long.MIN_VALUE;

        if (currentlySprinting) {
            return new HitResult(HitGrade.MISS, -1L,
                "Still sprinting — release W right before hitting!");
        }

        long diff = attackTick - lastSprintStopTick;

        if (diff < 0) {
            // Shouldn't happen, but guard against clock oddities
            return new HitResult(HitGrade.MISS, diff, "Timing anomaly — try again.");
        }

        if (diff <= perfectMaxTicks) {
            String note = diff == 0
                ? "Same-tick release — razor sharp!"
                : diff + " tick" + (diff == 1 ? "" : "s") + " before hit.";
            return new HitResult(HitGrade.PERFECT, diff, note);
        }

        if (diff <= goodMaxTicks) {
            return new HitResult(HitGrade.GOOD, diff,
                "Released " + diff + " ticks early — tighten the gap.");
        }

        return new HitResult(HitGrade.MISS, diff,
            "Released W " + diff + " ticks early — you lost sprint momentum.");
    }

    private void sendHitFeedback(HitResult result) {
        Component msg = switch (result.grade()) {
            case PERFECT -> Component.text("⚡ PERFECT!  ", NamedTextColor.GREEN, TextDecoration.BOLD)
                            .append(Component.text(result.note(), NamedTextColor.GREEN));
            case GOOD    -> Component.text("✓ Good   ", NamedTextColor.YELLOW)
                            .append(Component.text(result.note(), NamedTextColor.YELLOW));
            case MISS    -> Component.text("✗ Miss   ", NamedTextColor.RED)
                            .append(Component.text(result.note(), NamedTextColor.RED));
        };

        // Show in action bar for 1 second; the loop will revert to the progress bar after
        actionBarOverride    = msg;
        actionBarOverrideTtl = 20;

        // Sounds and particles
        switch (result.grade()) {
            case PERFECT -> {
                player.playSound(player.getLocation(),
                    Sound.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 1f, 1.6f);
                spawnParticles(Particle.CRIT, 16);
                showPerfectTitle();
            }
            case GOOD -> {
                player.playSound(player.getLocation(),
                    Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.PLAYERS, 1f, 1.2f);
                spawnParticles(Particle.CRIT, 6);
            }
            case MISS -> {
                player.playSound(player.getLocation(),
                    Sound.BLOCK_NOTE_BLOCK_BASS, SoundCategory.PLAYERS, 1f, 0.5f);
                spawnParticles(Particle.SMOKE, 6);
            }
        }
    }

    private void showPerfectTitle() {
        player.showTitle(Title.title(
            Component.text("PERFECT!", NamedTextColor.GREEN, TextDecoration.BOLD),
            Component.text("Hit " + hitCount + " / " + totalHits, NamedTextColor.GRAY),
            Title.Times.times(
                Duration.ofMillis(50),
                Duration.ofMillis(600),
                Duration.ofMillis(150))));
    }

    private void spawnParticles(Particle particle, int count) {
        if (dummy == null || !dummy.isValid()) return;
        Location loc = dummy.getLocation().add(0, 1.2, 0);
        player.getWorld().spawnParticle(particle, loc, count, 0.3, 0.4, 0.3, 0);
    }

    /** Starts the 2-tick repeating task that keeps the action bar visible. */
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

    /**
     * Builds the hit-by-hit progress bar displayed between attacks.
     *
     * <p>Green ■ = PERFECT, Yellow ■ = GOOD, Red ■ = MISS, Dark-grey □ = not yet hit.</p>
     */
    private Component buildProgressBar() {
        TextComponent.Builder builder = Component.text();

        for (int i = 0; i < totalHits; i++) {
            if (i < results.size()) {
                builder.append(switch (results.get(i).grade()) {
                    case PERFECT -> Component.text("■", NamedTextColor.GREEN);
                    case GOOD    -> Component.text("■", NamedTextColor.YELLOW);
                    case MISS    -> Component.text("■", NamedTextColor.RED);
                });
            } else {
                builder.append(Component.text("□", NamedTextColor.DARK_GRAY));
            }
            if (i < totalHits - 1) builder.append(Component.text(" "));
        }

        builder.append(Component.text(
            "  (" + hitCount + "/" + totalHits + ")", NamedTextColor.GRAY));

        return builder.build();
    }

    /** Computes the score, renders the results table, and schedules auto-return. */
    private void showSummary() {
        if (actionBarTask != null && !actionBarTask.isCancelled()) actionBarTask.cancel();

        double score        = ((perfectCount * 100.0) + (goodCount * 60.0)) / totalHits;
        String rank         = getRank(score);
        NamedTextColor col  = getRankColor(score);

        player.sendMessage(Component.empty());
        player.sendMessage(divider(NamedTextColor.GOLD));
        player.sendMessage(
            Component.text("  SESSION COMPLETE", NamedTextColor.GOLD, TextDecoration.BOLD));
        player.sendMessage(divider(NamedTextColor.GOLD));
        player.sendMessage(Component.empty());
        player.sendMessage(
            Component.text("  ⚡ Perfect  ", NamedTextColor.GREEN)
                     .append(Component.text(perfectCount + " / " + totalHits, NamedTextColor.WHITE)));
        player.sendMessage(
            Component.text("  ✓ Good     ", NamedTextColor.YELLOW)
                     .append(Component.text(goodCount + " / " + totalHits, NamedTextColor.WHITE)));
        player.sendMessage(
            Component.text("  ✗ Miss     ", NamedTextColor.RED)
                     .append(Component.text(missCount + " / " + totalHits, NamedTextColor.WHITE)));
        player.sendMessage(Component.empty());
        player.sendMessage(
            Component.text("  Score   ", NamedTextColor.AQUA)
                     .append(Component.text(String.format("%.0f%%", score), col)));
        player.sendMessage(
            Component.text("  Rank    ", NamedTextColor.AQUA)
                     .append(Component.text(rank, col, TextDecoration.BOLD)));
        player.sendMessage(Component.empty());
        sendTip(score);
        player.sendMessage(divider(NamedTextColor.GOLD));
        player.sendMessage(
            Component.text("Returning in 5 s — or /pvpa stop to go now.", NamedTextColor.GRAY));

        player.playSound(player.getLocation(),
            Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 1f, 1f);

        // Remove from manager map and clean up after 5 seconds
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            plugin.getTrainingManager().onSessionComplete(player.getUniqueId());
            stop(false);
        }, 100L);
    }

    private void sendTip(double score) {
        String tip;
        if      (score < 30) tip = "Sprint toward the dummy, release W just before clicking, then re-press W.";
        else if (score < 55) tip = "You're releasing W a bit too early — wait until you are right in range.";
        else if (score < 75) tip = "Solid timing! Match your W-release with the swing animation frame.";
        else if (score < 90) tip = "Very consistent! Shave 1 tick off your release timing for perfects.";
        else                 tip = "Elite W-Tap. You're ready for Strafe Tracking next.";

        player.sendMessage(
            Component.text("  Tip  ", NamedTextColor.AQUA, TextDecoration.BOLD)
                     .append(Component.text(tip, NamedTextColor.GRAY)));
    }

    private String getRank(double score) {
        if (score >= 90) return "S  —  Master";
        if (score >= 80) return "A  —  Expert";
        if (score >= 70) return "B  —  Proficient";
        if (score >= 50) return "C  —  Learner";
        return              "D  —  Beginner";
    }

    private NamedTextColor getRankColor(double score) {
        if (score >= 90) return NamedTextColor.GREEN;
        if (score >= 70) return NamedTextColor.YELLOW;
        if (score >= 50) return NamedTextColor.GOLD;
        return               NamedTextColor.RED;
    }

    private void sendIntro() {
        player.sendMessage(Component.empty());
        player.sendMessage(divider(NamedTextColor.GOLD));
        player.sendMessage(
            Component.text("  ⚔  W-TAP TRAINING  ⚔", NamedTextColor.GOLD, TextDecoration.BOLD));
        player.sendMessage(divider(NamedTextColor.GOLD));
        player.sendMessage(Component.empty());
        player.sendMessage(
            Component.text("Goal: Sprint → release W → attack → re-sprint.", NamedTextColor.YELLOW));
        player.sendMessage(Component.empty());
        player.sendMessage(
            Component.text("  ⚡ Perfect  ", NamedTextColor.GREEN)
                     .append(Component.text(
                         "Release W 0–" + perfectMaxTicks + " ticks before the hit",
                         NamedTextColor.WHITE)));
        player.sendMessage(
            Component.text("  ✓ Good     ", NamedTextColor.YELLOW)
                     .append(Component.text(
                         "Release W " + (perfectMaxTicks + 1) + "–" + goodMaxTicks + " ticks before the hit",
                         NamedTextColor.WHITE)));
        player.sendMessage(
            Component.text("  ✗ Miss     ", NamedTextColor.RED)
                     .append(Component.text(
                         "Still sprinting, or released W more than " + goodMaxTicks + " ticks early",
                         NamedTextColor.WHITE)));
        player.sendMessage(Component.empty());
        player.sendMessage(
            Component.text("Hit the dummy " + totalHits + " times to finish. Good luck!",
                NamedTextColor.AQUA));
        player.sendMessage(divider(NamedTextColor.GOLD));
        player.sendMessage(Component.empty());
    }

    /** A decorative horizontal rule. */
    private static Component divider(NamedTextColor color) {
        return Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━", color);
    }

    // ── Public accessors ──────────────────────────────────────────────────────

    public Player getPlayer() { return player; }

    /** The training dummy — {@code null} until {@link #start()} has been called. */
    public Zombie getDummy() { return dummy; }

    // ── Records and enums ─────────────────────────────────────────────────────

    /** Quality classification for a single W-Tap attempt. */
    public enum HitGrade { PERFECT, GOOD, MISS }

    /**
     * Immutable result of a single hit evaluation.
     *
     * @param grade    quality classification
     * @param tickDiff ticks between sprint-stop and attack  (−1 = was still sprinting)
     * @param note     human-readable feedback shown to the player
     */
    public record HitResult(HitGrade grade, long tickDiff, String note) {}
}
