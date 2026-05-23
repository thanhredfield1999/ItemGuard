package com.itemguard.commands;

import com.itemguard.ItemGuard;
import com.itemguard.data.ItemHistory;
import com.itemguard.gui.GUIListener;
import com.itemguard.gui.HistoryGUI;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class HistoryCommand implements CommandExecutor, TabCompleter {

    private final ItemGuard plugin;
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

        String code = null;
        int limit = DEFAULT_LIMIT;

        if (args.length >= 1) {
            String arg0 = args[0];
            if (arg0.startsWith("#")) {
                code = arg0.substring(1);
            } else {
                org.bukkit.entity.Player target = org.bukkit.Bukkit.getPlayer(arg0);
                if (target != null) {
                    if (args.length >= 2) {
                        try {
                            limit = Math.min(Integer.parseInt(args[1]), 500);
                        } catch (NumberFormatException ignored) {}
                    }
                    openGUIForPlayer(player, target.getUniqueId().toString(), limit);
                    return true;
                }
                try {
                    limit = Math.min(Integer.parseInt(arg0), 500);
                } catch (NumberFormatException ignored) {}
                if (args.length >= 2) {
                    try {
                        limit = Math.min(Integer.parseInt(args[1]), 500);
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        if (args.length >= 2 && args[1].startsWith("#")) {
            code = args[1].substring(1);
        }

        if (code != null) {
            openGUIForCode(player, code, limit);
        } else {
            openGUIForPlayer(player, player.getUniqueId().toString(), limit);
        }
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
                code = arg0.substring(1);
            } else {
                org.bukkit.entity.Player target = org.bukkit.Bukkit.getPlayer(arg0);
                if (target != null) {
                    if (args.length >= 2) {
                        try {
                            limit = Math.min(Integer.parseInt(args[1]), 500);
                        } catch (NumberFormatException ignored) {}
                    }
                    openGUIForPlayer(player, target.getUniqueId().toString(), limit);
                    return true;
                }
                try {
                    limit = Math.min(Integer.parseInt(arg0), 500);
                } catch (NumberFormatException ignored) {}
                if (args.length >= 2) {
                    try {
                        limit = Math.min(Integer.parseInt(args[1]), 500);
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        if (args.length >= 2 && args[1].startsWith("#")) {
            code = args[1].substring(1);
        }

        if (code != null) {
            openGUIForCode(player, code, limit);
        } else {
            openGUIForPlayer(player, player.getUniqueId().toString(), limit);
        }
        return true;
    }

    private void openGUIForCode(Player player, String code, int limit) {
        List<ItemHistory> histories = plugin.getDB().getHistory(code, limit);
        if (histories.isEmpty()) {
            plugin.getMessages().send(player, "history-empty");
            return;
        }
        HistoryGUI gui = new HistoryGUI(plugin, player, code, histories, null, null);
        plugin.getGuiListener().registerOpenGUI(player, gui);
        gui.open();
    }

    private void openGUIForPlayer(Player player, String playerName, int limit) {
        org.bukkit.entity.Player target = org.bukkit.Bukkit.getPlayer(playerName);
        if (target == null) {
            plugin.getMessages().send(player, "player-not-found", java.util.Map.of("player", playerName));
            return;
        }

        List<ItemHistory> histories = new java.util.ArrayList<>();
        java.util.List<com.itemguard.data.ItemData> items = plugin.getDB().getItemsByPlayer(target.getUniqueId());
        for (com.itemguard.data.ItemData item : items) {
            histories.addAll(plugin.getDB().getHistory(item.getCode(), 20));
        }
        histories.sort((a, b) -> Long.compare(b.getTimestamp(), a.getTimestamp()));

        if (histories.isEmpty()) {
            plugin.getMessages().send(player, "history-empty");
            return;
        }

        HistoryGUI gui = new HistoryGUI(plugin, player, target.getName(), histories, target.getUniqueId(), target.getName());
        plugin.getGuiListener().registerOpenGUI(player, gui);
        gui.open();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> list = new ArrayList<>();
        if (args.length == 1) {
            for (org.bukkit.entity.Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
                list.add(p.getName());
            }
        }
        return list.stream()
            .filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase()))
            .toList();
    }
}
