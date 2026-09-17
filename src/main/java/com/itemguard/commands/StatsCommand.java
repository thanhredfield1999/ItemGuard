package com.itemguard.commands;

import com.itemguard.ItemGuard;
import com.itemguard.data.PluginStats;
import com.itemguard.gui.UiMainThreadHandoff;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class StatsCommand implements CommandExecutor, TabCompleter {

    private final ItemGuard plugin;

    public StatsCommand(ItemGuard plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("itemguard.stats")) {
            plugin.getMessages().send(sender, "no-permission");
            return true;
        }
        showStats(sender);
        return true;
    }

    public void showStats(CommandSender sender) {
        int onlineTracked = plugin.getDB().getOnlineTrackedCount();
        plugin.getDB().getStatsAsync().whenComplete((stats, failure) ->
            UiMainThreadHandoff.dispatch(plugin, () -> {
                if (failure != null) {
                    plugin.getLogger().log(
                        java.util.logging.Level.SEVERE,
                        "Failed to load ItemGuard stats",
                        failure
                    );
                    sender.sendMessage("§e§l[ItemGuard] §cKhông thể tải thống kê lúc này.");
                    return;
                }
                stats.setOnlineTracked(onlineTracked);
                renderStats(sender, stats);
            })
        );
    }

    private void renderStats(CommandSender sender, PluginStats stats) {
        sender.sendMessage(plugin.getMessages().getRaw("stats-header"));
        sender.sendMessage(plugin.getMessages().getRaw("stats-total-items", Map.of("total", String.valueOf(stats.getTotalItems()))));
        sender.sendMessage(plugin.getMessages().getRaw("stats-total-history", Map.of("history", String.valueOf(stats.getTotalHistory()))));
        sender.sendMessage(plugin.getMessages().getRaw("stats-online-tracked", Map.of("online", String.valueOf(stats.getOnlineTracked()))));
        sender.sendMessage(plugin.getMessages().getRaw("stats-duplicates", Map.of("duplicates", String.valueOf(stats.getDuplicatesDetected()))));
        sender.sendMessage(plugin.getMessages().getRaw("stats-database", Map.of("db", stats.getDatabaseType())));

        if (plugin.getConfigs().isDebug()) {
            sender.sendMessage("§8[DEBUG] DB Status: " + stats.getDatabaseStatus());
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("itemguard.stats")) {
            return List.of();
        }
        return new ArrayList<>();
    }
}
