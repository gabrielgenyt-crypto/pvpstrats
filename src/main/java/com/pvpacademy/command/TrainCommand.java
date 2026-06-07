package com.pvpacademy.command;

import com.pvpacademy.PvPAcademy;
import com.pvpacademy.model.Recording;
import com.pvpacademy.model.Strategy;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Handles the {@code /pvpa} command and all its sub-commands.
 *
 * <pre>
 *   Player commands:
 *     /pvpa train              — W-Tap training session
 *     /pvpa train &lt;strategy&gt;  — Evaluation session against a recording
 *     /pvpa stop               — End the current session
 *     /pvpa help               — Show help
 *
 *   Admin commands (pvpacademy.admin):
 *     /pvpa strat create &lt;name&gt; [displayName] [description]
 *     /pvpa record start &lt;name&gt;
 *     /pvpa record stop
 *     /pvpa bot create &lt;strategy&gt; &lt;recording&gt;
 *     /pvpa play &lt;recording&gt;
 * </pre>
 */
public final class TrainCommand implements CommandExecutor, TabCompleter {

    private final PvPAcademy plugin;

    public TrainCommand(PvPAcademy plugin) {
        this.plugin = plugin;
    }

    // ── CommandExecutor ───────────────────────────────────────────────────────

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             @NotNull String[] args) {

        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text(
                "[PvP Academy] Only players can use this command.", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0) { sendHelp(player, label); return true; }

        return switch (args[0].toLowerCase()) {
            case "train"  -> handleTrain(player, args);
            case "stop"   -> handleStop(player);
            case "strat"  -> handleStrat(player, label, args);
            case "record" -> handleRecord(player, label, args);
            case "bot"    -> handleBot(player, label, args);
            case "play"   -> handlePlay(player, args);
            case "help"   -> { sendHelp(player, label); yield true; }
            default       -> { sendUnknown(player, label, args[0]); yield true; }
        };
    }

    // ── /pvpa train ───────────────────────────────────────────────────────────

    /**
     * Without arguments: starts a W-Tap session.
     * With a strategy name: starts a recording-based evaluation session.
     */
    private boolean handleTrain(Player player, String[] args) {
        if (!player.hasPermission("pvpacademy.train")) {
            return denied(player);
        }

        // Guard: cannot start if another session or recording is active
        if (anyActiveSession(player)) {
            player.sendMessage(Component.text(
                "You already have an active session. Use /pvpa stop first.",
                NamedTextColor.YELLOW));
            return true;
        }

        // /pvpa train — default W-Tap module
        if (args.length < 2) {
            plugin.getTrainingManager().startWTap(player);
            return true;
        }

        // /pvpa train <strategy>
        String stratName = args[1];
        Optional<Strategy> stratOpt = plugin.getStrategyManager().get(stratName);
        if (stratOpt.isEmpty()) {
            player.sendMessage(Component.text(
                "Unknown strategy: '" + stratName + "'. Use /pvpa strat create first.",
                NamedTextColor.RED));
            return true;
        }

        Strategy strategy = stratOpt.get();
        if (!strategy.hasRecording()) {
            player.sendMessage(Component.text(
                "Strategy '" + stratName + "' has no recording linked. "
                + "Use /pvpa bot create " + stratName + " <recording>.",
                NamedTextColor.RED));
            return true;
        }

        Optional<Recording> recOpt = plugin.getRecordingManager().load(strategy.recordingName());
        if (recOpt.isEmpty()) {
            player.sendMessage(Component.text(
                "Recording '" + strategy.recordingName() + "' not found on disk.",
                NamedTextColor.RED));
            return true;
        }

        plugin.getEvaluationManager().start(player, strategy, recOpt.get());
        return true;
    }

    // ── /pvpa stop ────────────────────────────────────────────────────────────

    /** Universal stop — ends whatever session is currently active. */
    private boolean handleStop(Player player) {
        boolean any = false;
        if (plugin.getTrainingManager().stop(player))   any = true;
        if (plugin.getEvaluationManager().stop(player)) any = true;
        if (plugin.getPlaybackManager().stop(player))   any = true;

        Optional<Recording> stopped =
            plugin.getRecordingManager().stopSession(player, true);
        if (stopped.isPresent()) {
            saveRecording(player, stopped.get());
            any = true;
        }

        if (!any) {
            player.sendMessage(Component.text(
                "You have no active session.", NamedTextColor.YELLOW));
        }
        return true;
    }

    // ── /pvpa strat ───────────────────────────────────────────────────────────

    private boolean handleStrat(Player player, String label, String[] args) {
        if (!player.hasPermission("pvpacademy.admin")) return denied(player);
        if (args.length < 2) {
            return usage(player, label, "strat create <name> [displayName] [description]");
        }

        if (args[1].equalsIgnoreCase("create")) {
            if (args.length < 3) {
                return usage(player, label, "strat create <name> [displayName] [description]");
            }

            String name        = args[2].toLowerCase();
            String displayName = args.length > 3 ? args[3] : name;
            String description = args.length > 4
                ? String.join(" ", Arrays.copyOfRange(args, 4, args.length))
                : "No description yet.";

            boolean created = plugin.getStrategyManager().create(name, displayName, description);
            if (!created) {
                player.sendMessage(Component.text(
                    "Strategy '" + name + "' already exists.", NamedTextColor.RED));
            } else {
                player.sendMessage(
                    Component.text("Strategy created: ", NamedTextColor.GREEN)
                             .append(Component.text(name, NamedTextColor.WHITE)));
            }
            return true;
        }

        if (args[1].equalsIgnoreCase("list")) {
            List<String> names = plugin.getStrategyManager().listNames();
            if (names.isEmpty()) {
                player.sendMessage(Component.text("No strategies defined yet.", NamedTextColor.GRAY));
            } else {
                player.sendMessage(Component.text("Strategies: ", NamedTextColor.AQUA)
                                            .append(Component.text(String.join(", ", names),
                                                                   NamedTextColor.WHITE)));
            }
            return true;
        }

        return usage(player, label, "strat create|list");
    }

    // ── /pvpa record ──────────────────────────────────────────────────────────

    private boolean handleRecord(Player player, String label, String[] args) {
        if (!player.hasPermission("pvpacademy.admin")) return denied(player);
        if (args.length < 2) {
            return usage(player, label, "record start <name>  |  record stop");
        }

        if (args[1].equalsIgnoreCase("start")) {
            if (args.length < 3) {
                return usage(player, label, "record start <name>");
            }

            String name = args[2];
            boolean started = plugin.getRecordingManager().startSession(player, name);
            if (!started) {
                player.sendMessage(Component.text(
                    "You are already recording. Use /pvpa record stop first.",
                    NamedTextColor.YELLOW));
            }
            return true;
        }

        if (args[1].equalsIgnoreCase("stop")) {
            Optional<Recording> recording =
                plugin.getRecordingManager().stopSession(player, true);

            if (recording.isEmpty()) {
                player.sendMessage(Component.text(
                    "You are not currently recording.", NamedTextColor.YELLOW));
            } else {
                saveRecording(player, recording.get());
            }
            return true;
        }

        return usage(player, label, "record start <name>  |  record stop");
    }

    // ── /pvpa bot ─────────────────────────────────────────────────────────────

    private boolean handleBot(Player player, String label, String[] args) {
        if (!player.hasPermission("pvpacademy.admin")) return denied(player);
        if (args.length < 2) {
            return usage(player, label, "bot create <strategy> <recording>");
        }

        if (args[1].equalsIgnoreCase("create")) {
            if (args.length < 4) {
                return usage(player, label, "bot create <strategy> <recording>");
            }

            String stratName = args[2];
            String recName   = args[3];

            if (!plugin.getStrategyManager().exists(stratName)) {
                player.sendMessage(Component.text(
                    "Strategy '" + stratName + "' does not exist.", NamedTextColor.RED));
                return true;
            }
            if (!plugin.getRecordingManager().exists(recName)) {
                player.sendMessage(Component.text(
                    "Recording '" + recName + "' does not exist on disk.", NamedTextColor.RED));
                return true;
            }

            plugin.getStrategyManager().linkRecording(stratName, recName);
            player.sendMessage(
                Component.text("Linked recording ", NamedTextColor.GREEN)
                         .append(Component.text(recName, NamedTextColor.WHITE))
                         .append(Component.text(" → strategy ", NamedTextColor.GREEN))
                         .append(Component.text(stratName, NamedTextColor.WHITE)));
            return true;
        }

        return usage(player, label, "bot create <strategy> <recording>");
    }

    // ── /pvpa play ────────────────────────────────────────────────────────────

    private boolean handlePlay(Player player, String[] args) {
        if (!player.hasPermission("pvpacademy.admin")) return denied(player);
        if (args.length < 2) {
            player.sendMessage(Component.text("Usage: /pvpa play <recording>", NamedTextColor.RED));
            return true;
        }

        String recName = args[1];
        Optional<Recording> recOpt = plugin.getRecordingManager().load(recName);

        if (recOpt.isEmpty()) {
            player.sendMessage(Component.text(
                "Recording '" + recName + "' not found.", NamedTextColor.RED));
            return true;
        }

        boolean started = plugin.getPlaybackManager().start(player, recOpt.get());
        if (!started) {
            player.sendMessage(Component.text(
                "You already have an active playback. Use /pvpa stop first.",
                NamedTextColor.YELLOW));
        } else {
            player.sendMessage(
                Component.text("Playing back: ", NamedTextColor.GREEN)
                         .append(Component.text(recName, NamedTextColor.WHITE))
                         .append(Component.text(
                             "  (" + recOpt.get().getTotalTicks() + " ticks)",
                             NamedTextColor.GRAY)));
        }
        return true;
    }

    // ── TabCompleter ──────────────────────────────────────────────────────────

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender,
                                       @NotNull Command command,
                                       @NotNull String alias,
                                       @NotNull String[] args) {

        boolean isAdmin = sender.hasPermission("pvpacademy.admin");
        boolean isTrain = sender.hasPermission("pvpacademy.train");

        if (args.length == 1) {
            Stream<String> subs = Stream.of("train", "stop", "help");
            if (isAdmin) subs = Stream.concat(subs, Stream.of("strat", "record", "bot", "play"));
            return subs.filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }

        if (args.length >= 2) {
            String sub = args[0].toLowerCase();

            if (sub.equals("train") && isTrain && args.length == 2) {
                return plugin.getStrategyManager().listNames().stream()
                             .filter(n -> n.startsWith(args[1].toLowerCase())).toList();
            }

            if (isAdmin) {
                switch (sub) {
                    case "strat" -> {
                        if (args.length == 2) return completions(args[1], "create", "list");
                    }
                    case "record" -> {
                        if (args.length == 2) return completions(args[1], "start", "stop");
                    }
                    case "bot" -> {
                        if (args.length == 2) return completions(args[1], "create");
                        if (args.length == 3 && args[1].equalsIgnoreCase("create")) {
                            return plugin.getStrategyManager().listNames().stream()
                                         .filter(n -> n.startsWith(args[2].toLowerCase())).toList();
                        }
                        if (args.length == 4 && args[1].equalsIgnoreCase("create")) {
                            return plugin.getRecordingManager().listAll().stream()
                                         .filter(n -> n.startsWith(args[3].toLowerCase())).toList();
                        }
                    }
                    case "play" -> {
                        if (args.length == 2) {
                            return plugin.getRecordingManager().listAll().stream()
                                         .filter(n -> n.startsWith(args[1].toLowerCase())).toList();
                        }
                    }
                }
            }
        }
        return List.of();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void saveRecording(Player player, Recording recording) {
        try {
            plugin.getRecordingManager().save(recording);
            player.sendMessage(
                Component.text("Recording saved: ", NamedTextColor.GREEN)
                         .append(Component.text(recording.getName(), NamedTextColor.WHITE))
                         .append(Component.text(
                             " (" + recording.getTotalTicks() + " ticks, "
                             + recording.getCheckpoints().size() + " checkpoints)",
                             NamedTextColor.GRAY)));
        } catch (IOException e) {
            player.sendMessage(Component.text(
                "Failed to save recording: " + e.getMessage(), NamedTextColor.RED));
            plugin.getLogger().warning("Recording save error: " + e.getMessage());
        }
    }

    /** Returns true if the player has any active session of any kind. */
    private boolean anyActiveSession(Player player) {
        return plugin.getTrainingManager().hasSession(player.getUniqueId())
            || plugin.getEvaluationManager().hasSession(player.getUniqueId())
            || plugin.getRecordingManager().hasSession(player.getUniqueId())
            || plugin.getPlaybackManager().hasPlayback(player.getUniqueId());
    }

    private void sendHelp(Player player, String label) {
        boolean admin = player.hasPermission("pvpacademy.admin");
        String rule   = "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━";

        player.sendMessage(Component.empty());
        player.sendMessage(Component.text(rule, NamedTextColor.GOLD));
        player.sendMessage(Component.text("  PvP Academy", NamedTextColor.GOLD, TextDecoration.BOLD)
                                    .append(Component.text(" — Commands", NamedTextColor.YELLOW)));
        player.sendMessage(Component.text(rule, NamedTextColor.GOLD));
        player.sendMessage(cmdLine(label, "train",             "W-Tap training session."));
        player.sendMessage(cmdLine(label, "train <strategy>",  "Evaluation session."));
        player.sendMessage(cmdLine(label, "stop",              "End the current session."));
        if (admin) {
            player.sendMessage(Component.text(rule, NamedTextColor.DARK_GRAY));
            player.sendMessage(cmdLine(label, "strat create <name> [display] [desc]",
                                              "Register a new strategy."));
            player.sendMessage(cmdLine(label, "record start <name>",
                                              "Start recording your moves."));
            player.sendMessage(cmdLine(label, "record stop",
                                              "Stop recording and save."));
            player.sendMessage(cmdLine(label, "bot create <strategy> <recording>",
                                              "Link a recording to a strategy."));
            player.sendMessage(cmdLine(label, "play <recording>",
                                              "Spawn a bot to demo a recording."));
        }
        player.sendMessage(Component.text(rule, NamedTextColor.GOLD));
        player.sendMessage(Component.empty());
    }

    private static Component cmdLine(String label, String sub, String description) {
        return Component.text("  /" + label + " ", NamedTextColor.GRAY)
                        .append(Component.text(sub, NamedTextColor.GOLD, TextDecoration.BOLD))
                        .append(Component.text("  —  " + description, NamedTextColor.GRAY));
    }

    private boolean denied(Player player) {
        player.sendMessage(Component.text(
            "You do not have permission to use this command.", NamedTextColor.RED));
        return true;
    }

    private boolean usage(Player player, String label, String usage) {
        player.sendMessage(Component.text(
            "Usage: /" + label + " " + usage, NamedTextColor.RED));
        return true;
    }

    private List<String> completions(String partial, String... options) {
        return Arrays.stream(options)
                     .filter(s -> s.startsWith(partial.toLowerCase()))
                     .toList();
    }

    private void sendUnknown(Player player, String label, String input) {
        player.sendMessage(Component.text(
            "Unknown sub-command: " + input + ". Try /" + label + " help",
            NamedTextColor.RED));
    }
}
