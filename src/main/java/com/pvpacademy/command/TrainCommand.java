package com.pvpacademy.command;

import com.pvpacademy.PvPAcademy;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Handles the {@code /pvpa} command and its sub-commands.
 *
 * <pre>
 *   /pvpa train  — start a W-Tap training session
 *   /pvpa stop   — end the current session early
 *   /pvpa help   — print usage information
 * </pre>
 *
 * <p>Tab-completion is provided for the first argument.</p>
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

        // Only players can train
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text(
                "[PvP Academy] Only players can use this command.", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0) {
            sendHelp(player, label);
            return true;
        }

        return switch (args[0].toLowerCase()) {
            case "train" -> handleTrain(player);
            case "stop"  -> handleStop(player);
            case "help"  -> { sendHelp(player, label); yield true; }
            default      -> { sendUnknown(player, label, args[0]); yield true; }
        };
    }

    // ── Sub-command handlers ──────────────────────────────────────────────────

    /**
     * Starts a W-Tap training session for the player.
     *
     * <p>Rejects the request if the player already has an active session.</p>
     */
    private boolean handleTrain(Player player) {
        if (!player.hasPermission("pvpacademy.train")) {
            player.sendMessage(Component.text(
                "You do not have permission to start a training session.",
                NamedTextColor.RED));
            return true;
        }

        boolean started = plugin.getTrainingManager().startWTap(player);

        if (!started) {
            player.sendMessage(Component.text(
                "You already have an active training session. Use ",
                NamedTextColor.YELLOW)
                .append(Component.text("/pvpa stop", NamedTextColor.GOLD, TextDecoration.BOLD))
                .append(Component.text(" to end it first.", NamedTextColor.YELLOW)));
        }
        // If started, the session itself sends the intro message.
        return true;
    }

    /** Ends the player's current session. */
    private boolean handleStop(Player player) {
        boolean stopped = plugin.getTrainingManager().stop(player);

        if (!stopped) {
            player.sendMessage(Component.text(
                "You are not in a training session.", NamedTextColor.YELLOW));
        }
        // If stopped, the session itself sends the "session ended" message.
        return true;
    }

    // ── TabCompleter ──────────────────────────────────────────────────────────

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender,
                                       @NotNull Command command,
                                       @NotNull String alias,
                                       @NotNull String[] args) {
        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            return List.of("train", "stop", "help").stream()
                       .filter(s -> s.startsWith(partial))
                       .toList();
        }
        return List.of();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void sendHelp(Player player, String label) {
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━", NamedTextColor.GOLD));
        player.sendMessage(
            Component.text("  PvP Academy", NamedTextColor.GOLD, TextDecoration.BOLD)
                     .append(Component.text(" — Commands", NamedTextColor.YELLOW)));
        player.sendMessage(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━", NamedTextColor.GOLD));
        player.sendMessage(cmdLine(label, "train", "Start a W-Tap training session."));
        player.sendMessage(cmdLine(label, "stop",  "End your current session early."));
        player.sendMessage(cmdLine(label, "help",  "Show this help message."));
        player.sendMessage(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━", NamedTextColor.GOLD));
        player.sendMessage(Component.empty());
    }

    private static Component cmdLine(String label, String sub, String description) {
        return Component.text("  /" + label + " ", NamedTextColor.GRAY)
                        .append(Component.text(sub, NamedTextColor.GOLD, TextDecoration.BOLD))
                        .append(Component.text("  —  " + description, NamedTextColor.GRAY));
    }

    private void sendUnknown(Player player, String label, String input) {
        player.sendMessage(Component.text(
            "Unknown sub-command: " + input + ". Try /" + label + " help",
            NamedTextColor.RED));
    }
}
