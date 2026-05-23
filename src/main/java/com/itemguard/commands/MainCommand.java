package com.itemguard.commands;

import com.itemguard.ItemGuard;
import com.itemguard.data.ItemData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class MainCommand implements CommandExecutor, TabCompleter {

    private final ItemGuard plugin;

    public MainCommand(ItemGuard plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "check" -> {
                if (!sender.hasPermission("itemguard.check")) {
                    plugin.getMessages().send(sender, "no-permission");
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    plugin.getMessages().send(sender, "player-only");
                    return true;
                }
                new CheckCommand(plugin).checkItemInHand(player);
            }
            case "history" -> {
                if (!sender.hasPermission("itemguard.history")) {
                    plugin.getMessages().send(sender, "no-permission");
                    return true;
                }
                if (!(sender instanceof Player)) {
                    plugin.getMessages().send(sender, "player-only");
                    return true;
                }
                new HistoryCommand(plugin).openHistoryForPlayer((Player) sender, args);
            }
            case "search" -> {
                if (!sender.hasPermission("itemguard.search")) {
                    plugin.getMessages().send(sender, "no-permission");
                    return true;
                }
                new SearchCommand(plugin).searchItems(sender, args);
            }
            case "stats" -> {
                if (!sender.hasPermission("itemguard.stats")) {
                    plugin.getMessages().send(sender, "no-permission");
                    return true;
                }
                new StatsCommand(plugin).showStats(sender);
            }
            case "browser" -> {
                if (!sender.hasPermission("itemguard.gui")) {
                    plugin.getMessages().send(sender, "no-permission");
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    plugin.getMessages().send(sender, "player-only");
                    return true;
                }
                plugin.getGuiListener().openBrowserPendingFilter(player, args);
            }
            case "reload" -> {
                if (!sender.hasPermission("itemguard.reload")) {
                    plugin.getMessages().send(sender, "no-permission");
                    return true;
                }
                try {
                    plugin.reload();
                    plugin.getMessages().send(sender, "reload-success");
                } catch (Exception e) {
                    plugin.getMessages().send(sender, "reload-fail");
                    plugin.getLogger().severe("Reload failed: " + e.getMessage());
                }
            }
            case "info" -> sendInfo(sender);
            case "help" -> sendHelp(sender);
            default -> {
                plugin.getMessages().sendRaw(sender, "invalid-args", Map.of("usage", "/itemguard <check|history|search|stats|reload|info>"));
            }
        }
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§e§l=== ItemGuard Commands ===");
        sender.sendMessage("§7/itemguard check §f- Kiem tra item trên tay");
        sender.sendMessage("§7/itemguard history [player] [limit] §f- Xem lich su item");
        sender.sendMessage("§7/itemguard search <player> §f- Tim kiem item cua nguoi choi");
        sender.sendMessage("§7/itemguard stats §f- Xem thong ke plugin");
        sender.sendMessage("§7/itemguard reload §f- Tai lai cau hinh");
        sender.sendMessage("§7/itemguard info §f- Thong tin plugin");
    }

    private void sendInfo(CommandSender sender) {
        sender.sendMessage("§e§l=== ItemGuard ===");
        sender.sendMessage("§7Version: §f" + plugin.getDescription().getVersion());
        sender.sendMessage("§7Author: §fAI.WORK");
        sender.sendMessage("§7Minecraft: §f1.21 - 1.21.11");
        sender.sendMessage("§7Database: §f" + plugin.getDB().getStats().getDatabaseType());
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>();
            if (sender.hasPermission("itemguard.check")) subs.add("check");
            if (sender.hasPermission("itemguard.history")) subs.add("history");
            if (sender.hasPermission("itemguard.search")) subs.add("search");
            if (sender.hasPermission("itemguard.stats")) subs.add("stats");
            if (sender.hasPermission("itemguard.reload")) subs.add("reload");
            if (sender.hasPermission("itemguard.gui")) subs.add("browser");
            subs.addAll(List.of("info", "help"));
            return filter(subs, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("history")) {
            return filter(plugin.getServer().getOnlinePlayers().stream()
                .map(Player::getName).collect(Collectors.toList()), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("search")) {
            return filter(plugin.getServer().getOnlinePlayers().stream()
                .map(Player::getName).collect(Collectors.toList()), args[1]);
        }
        return new ArrayList<>();
    }

    private List<String> filter(List<String> list, String input) {
        return list.stream().filter(s -> s.toLowerCase().startsWith(input.toLowerCase())).collect(Collectors.toList());
    }
}
