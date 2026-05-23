package com.itemguard.commands;

import com.itemguard.ItemGuard;
import com.itemguard.data.PluginStats;
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
        showStats(sender);
        return true;
    }

    public void showStats(CommandSender sender) {
        PluginStats stats = plugin.getDB().getStats();
        stats.setOnlineTracked(plugin.getDB().getOnlineTrackedCount());

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
        return new ArrayList<>();
    }
}
