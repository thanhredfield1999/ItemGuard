package com.itemguard.commands;

import com.itemguard.ItemGuard;
import com.itemguard.config.ReloadDecision;
import com.itemguard.config.ReloadStatus;
import com.itemguard.data.ItemData;
import com.itemguard.gui.UiMainThreadHandoff;
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
                new HistoryCommand(plugin).openHistoryForPlayer(
                    (Player) sender,
                    SubcommandArguments.tail(args)
                );
            }
            case "search" -> {
                if (!sender.hasPermission("itemguard.search")) {
                    plugin.getMessages().send(sender, "no-permission");
                    return true;
                }
                new SearchCommand(plugin).searchItems(sender, SubcommandArguments.tail(args));
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
                if (args.length == 1) {
                    plugin.getCatalogUi().open(player);
                } else {
                    plugin.getGuiListener().openBrowserPendingFilter(
                        player,
                        SubcommandArguments.tail(args)
                    );
                }
            }
            case "reload" -> {
                if (!sender.hasPermission("itemguard.reload")) {
                    plugin.getMessages().send(sender, "no-permission");
                    return true;
                }
                try {
                    ReloadDecision decision = plugin.reload();
                    if (decision.status() == ReloadStatus.RESTART_REQUIRED) {
                        sender.sendMessage("§e§l[ItemGuard] §cCan restart de ap dung: §f"
                            + String.join(", ", decision.changedRestartKeys()));
                        return true;
                    }
                    plugin.getMessages().send(sender, "reload-success");
                } catch (Exception e) {
                    plugin.getMessages().send(sender, "reload-fail");
                    plugin.getLogger().severe("Reload failed: " + e.getMessage());
                }
            }
            case "info" -> sendInfo(sender);
            case "help" -> sendHelp(sender);
            default -> {
                plugin.getMessages().sendRaw(sender, "invalid-args", Map.of("usage", "/itemguard <check|history|search|stats|browser|reload|info>"));
            }
        }
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§e§l=== Lệnh ItemGuard ===");
        if (sender.hasPermission("itemguard.check")) {
            sender.sendMessage("§e/itemguard check §7- Kiểm tra vật phẩm đang cầm");
        }
        if (sender.hasPermission("itemguard.history")) {
            sender.sendMessage("§e/itemguard history [player|#code] [limit] §7- Xem lịch sử");
        }
        if (sender.hasPermission("itemguard.search")) {
            sender.sendMessage("§e/itemguard search <player|#code> §7- Tìm vật phẩm");
        }
        if (sender.hasPermission("itemguard.gui")) {
            sender.sendMessage("§e/itemguard browser [player] §7- Mặc định: kho tra cứu (cần quyền search)");
            sender.sendMessage("§e/itemguard browser <player> §7- Tra theo người chơi");
        }
        if (sender.hasPermission("itemguard.stats")) {
            sender.sendMessage("§e/itemguard stats §7- Xem thống kê hệ thống");
        }
        if (sender.hasPermission("itemguard.reload")) {
            sender.sendMessage("§e/itemguard reload §7- Tải lại cấu hình an toàn");
        }
        sender.sendMessage("§e/itemguard info §7- Thông tin plugin");
    }

    private void sendInfo(CommandSender sender) {
        plugin.getDB().getStatsAsync().whenComplete((stats, failure) ->
            UiMainThreadHandoff.dispatch(plugin, () -> {
                sender.sendMessage("§e§l=== ItemGuard ===");
                sender.sendMessage("§7Phiên bản: §f" + plugin.getDescription().getVersion());
                sender.sendMessage("§7Tác giả: §fAI.WORK");
                sender.sendMessage("§7Minecraft: §f1.21 - 1.21.11");
                if (failure != null) {
                    plugin.getLogger().log(
                        java.util.logging.Level.SEVERE,
                        "Failed to load ItemGuard info stats",
                        failure
                    );
                    sender.sendMessage("§7Cơ sở dữ liệu: §cKhông thể đọc trạng thái");
                    return;
                }
                sender.sendMessage("§7Cơ sở dữ liệu: §f" + stats.getDatabaseType());
            })
        );
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
            if (!(sender instanceof Player player)) {
                return List.of();
            }
            List<String> onlineNames = plugin.getServer().getOnlinePlayers().stream()
                .map(Player::getName)
                .collect(Collectors.toList());
            return filter(
                new HistoryAccessPolicy().completionCandidates(
                    player.getName(),
                    onlineNames,
                    player.hasPermission("itemguard.history.others")
                ),
                args[1]
            );
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
