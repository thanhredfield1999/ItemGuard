package com.itemguard.commands;

import com.itemguard.ItemGuard;
import com.itemguard.data.ItemHistory;
import com.itemguard.gui.HistoryGUI;
import com.itemguard.gui.UiMainThreadHandoff;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

public class HistoryCommand implements CommandExecutor, TabCompleter {

    private final ItemGuard plugin;
    private final HistoryAccessPolicy accessPolicy = new HistoryAccessPolicy();
    private static final int DEFAULT_LIMIT = 100;

    public HistoryCommand(ItemGuard plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getMessages().send(sender, "player-only");
            return true;
        }
        if (!sender.hasPermission("itemguard.history")) {
            plugin.getMessages().send(sender, "no-permission");
            return true;
        }

        openHistoryForPlayer(player, args);
        return true;
    }

    public boolean openHistoryForPlayer(Player player, String[] args) {
        if (!player.hasPermission("itemguard.history")) {
            plugin.getMessages().send(player, "no-permission");
            return true;
        }

        String code = null;
        int limit = DEFAULT_LIMIT;

        if (args.length >= 1) {
            String arg0 = args[0];
            if (arg0.startsWith("#")) {
                code = ItemCodeInput.normalize(arg0);
            } else {
                Player target = Bukkit.getPlayer(arg0);
                if (target != null) {
                    if (!accessPolicy.canViewPlayer(
                        player.getUniqueId(),
                        target.getUniqueId(),
                        player.hasPermission("itemguard.history.others")
                    )) {
                        plugin.getMessages().send(player, "no-permission");
                        return true;
                    }
                    if (args.length >= 2) {
                        limit = parseLimit(args[1], limit);
                    }
                    openGUIForPlayer(player, target.getUniqueId(), target.getName(), limit);
                    return true;
                }
                limit = parseLimit(arg0, limit);
                if (args.length >= 2) {
                    limit = parseLimit(args[1], limit);
                }
            }
        }

        if (args.length >= 2 && args[1].startsWith("#")) {
            code = ItemCodeInput.normalize(args[1]);
        }

        if (code != null) {
            if (!accessPolicy.canViewCode(
                player.hasPermission("itemguard.history.others")
            )) {
                plugin.getMessages().send(player, "no-permission");
                return true;
            }
            openGUIForCode(player, code, limit);
        } else {
            openGUIForPlayer(player, player.getUniqueId(), player.getName(), limit);
        }
        return true;
    }

    private int parseLimit(String input, int fallback) {
        try {
            return Math.max(1, Math.min(Integer.parseInt(input), 500));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private void openGUIForCode(Player player, String code, int limit) {
        plugin.getDB().getHistoryAsync(code, limit).whenComplete((histories, failure) ->
            UiMainThreadHandoff.dispatch(plugin, () -> renderHistory(
                player, code, histories, failure, null, null
            ))
        );
    }

    private void openGUIForPlayer(
        Player player,
        UUID targetPlayerUuid,
        String targetPlayerName,
        int limit
    ) {
        plugin.getDB().getHistoryByPlayerAsync(targetPlayerUuid, limit)
            .whenComplete((histories, failure) -> UiMainThreadHandoff.dispatch(plugin, () ->
                renderHistory(
                    player,
                    targetPlayerName,
                    histories,
                    failure,
                    targetPlayerUuid,
                    targetPlayerName
                )
            ));
    }

    private void renderHistory(
        Player player,
        String title,
        List<ItemHistory> histories,
        Throwable failure,
        UUID parentPlayerUuid,
        String parentPlayerName
    ) {
        if (!player.isOnline()) {
            return;
        }
        if (failure != null) {
            plugin.getLogger().log(
                Level.SEVERE,
                "Failed to load ItemGuard history for " + title,
                failure
            );
            player.sendMessage("§e§l[ItemGuard] §cKhông thể tải lịch sử lúc này. Vui lòng thử lại.");
            return;
        }
        if (histories == null || histories.isEmpty()) {
            plugin.getMessages().send(player, "history-empty");
            return;
        }

        HistoryGUI gui = new HistoryGUI(
            plugin,
            player,
            title,
            histories,
            parentPlayerUuid,
            parentPlayerName
        );
        plugin.getGuiListener().registerOpenGUI(player, gui);
        gui.open();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player) || args.length != 1) {
            return List.of();
        }
        List<String> onlineNames = Bukkit.getOnlinePlayers().stream()
            .map(Player::getName)
            .toList();
        return accessPolicy.completionCandidates(
                player.getName(),
                onlineNames,
                player.hasPermission("itemguard.history.others")
            ).stream()
            .filter(value -> value.toLowerCase().startsWith(args[0].toLowerCase()))
            .toList();
    }
}
