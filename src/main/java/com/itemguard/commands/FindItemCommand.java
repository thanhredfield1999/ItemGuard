package com.itemguard.commands;

import com.itemguard.ItemGuard;
import com.itemguard.search.FindItemService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class FindItemCommand implements CommandExecutor, TabCompleter {

    private final ItemGuard plugin;
    private final FindItemCommandParser parser = new FindItemCommandParser();
    private final FindItemCommandTask task;

    public FindItemCommand(ItemGuard plugin, FindItemService service) {
        this.plugin = plugin;
        this.task = new FindItemCommandTask(service, System::currentTimeMillis);
    }

    @Override
    public boolean onCommand(
        CommandSender sender,
        Command command,
        String label,
        String[] args
    ) {
        if (!sender.hasPermission("itemguard.finditem.admin")) {
            plugin.getMessages().send(sender, "no-permission");
            return true;
        }

        FindItemCommandAction action;
        try {
            action = parser.parse(args);
        } catch (IllegalArgumentException invalid) {
            sender.sendMessage("§e§l[ItemGuard] §c" + invalid.getMessage());
            sendHelp(sender);
            return true;
        }

        UUID playerUuid = sender instanceof Player player ? player.getUniqueId() : null;
        String actorName = sender.getName();
        // Player lookups are server state, so the snapshot is taken here on the command thread and
        // handed to the report task; the database reads that follow happen off it.
        Map<String, UUID> onlinePlayers = new java.util.HashMap<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            onlinePlayers.put(online.getName().toLowerCase(java.util.Locale.ROOT), online.getUniqueId());
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<String> messages;
            try {
                messages = execute(action, playerUuid, actorName, onlinePlayers);
            } catch (RuntimeException failure) {
                plugin.getLogger().log(
                    java.util.logging.Level.SEVERE,
                    "FindItem command database operation failed",
                    failure
                );
                messages = List.of(
                    "§e§l[ItemGuard] §cThao tac database that bai; khong co thay doi nao duoc bao thanh cong."
                );
            }
            List<String> immutableMessages = List.copyOf(messages);
            Bukkit.getScheduler().runTask(
                plugin,
                () -> render(playerUuid, immutableMessages)
            );
        });
        return true;
    }

    /**
     * Runs the parsed action on the worker thread.
     *
     * <p>The reporting actions read the plugin's own records through
     * {@link FindItemReportTask}; everything else goes to the search-request task that already
     * existed. Both return the message list the render step sends on the server thread.
     */
    private List<String> execute(
        FindItemCommandAction action,
        UUID playerUuid,
        String actorName,
        Map<String, UUID> onlinePlayers
    ) {
        if (action instanceof FindItemCommandAction.CheckTps) {
            return reportTask(onlinePlayers).checkTps(scanSnapshot());
        }
        if (action instanceof FindItemCommandAction.InfoItem item) {
            return reportTask(onlinePlayers).infoItem(item.code());
        }
        if (action instanceof FindItemCommandAction.InfoPlayer player) {
            return reportTask(onlinePlayers).infoPlayer(player.playerName());
        }
        if (action instanceof FindItemCommandAction.InfoDupe dupe) {
            return reportTask(onlinePlayers).infoDupe(dupe.code());
        }
        if (action instanceof FindItemCommandAction.ReadFinding finding) {
            return reportTask(onlinePlayers).readFinding(finding.code());
        }
        if (action instanceof FindItemCommandAction.AcknowledgeDupe dupe) {
            return reportTask(onlinePlayers).acknowledgeDupe(
                dupe.code(),
                actorName,
                System.currentTimeMillis()
            );
        }
        return task.execute(action, playerUuid, actorName);
    }

    private FindItemReportTask reportTask(Map<String, UUID> onlinePlayers) {
        return new FindItemReportTask(new FindItemReportTask.Data() {
            @Override
            public java.util.Optional<com.itemguard.data.ItemData> item(String code) {
                return plugin.getDB().getItem(code);
            }

            @Override
            public int historyCount(String code) {
                return plugin.getDB().getHistoryCount(code);
            }

            @Override
            public boolean snapshotPresent(String code) {
                return plugin.getDB().getSnapshot(code).isPresent();
            }

            @Override
            public List<com.itemguard.dupe.FindingRecord> findings(String code, int limit) {
                return plugin.getDB().findingsFor(code, limit);
            }

            @Override
            public com.itemguard.dupe.FindingAcknowledgement acknowledge(
                String code,
                String actor,
                long acknowledgedAt
            ) {
                return plugin.getDB().acknowledgeFindings(code, actor, acknowledgedAt);
            }

            @Override
            public java.util.Optional<UUID> onlinePlayer(String name) {
                return java.util.Optional.ofNullable(
                    onlinePlayers.get(name.toLowerCase(java.util.Locale.ROOT))
                );
            }

            @Override
            public List<com.itemguard.data.ItemData> itemsByPlayer(UUID uuid) {
                return plugin.getDB().getItemsByPlayer(uuid);
            }
        });
    }

    /** The scan's counters, read once so every line of the report describes the same moment. */
    private FindItemReportTask.ScanSnapshot scanSnapshot() {
        var metrics = plugin.getScanMetrics();
        var task = plugin.getInventoryScanTask();
        return new FindItemReportTask.ScanSnapshot(
            metrics.scanCount(),
            metrics.epochCount(),
            metrics.findingCount(),
            metrics.alertCount(),
            metrics.sweepPassCount(),
            metrics.skippedBusyScans(),
            metrics.sampleCount(),
            metrics.lastScanMillis(),
            metrics.averageScanMillis(),
            metrics.percentileScanMillis(50),
            metrics.percentileScanMillis(95),
            metrics.sweepInFlight(),
            plugin.getConfigs().getInventoryScanInterval(),
            plugin.getConfigs().isAntiDupeEnabled(),
            plugin.getConfigs().isNotifyStaff(),
            task != null
        );
    }

    private void render(UUID playerUuid, List<String> messages) {
        CommandSender target;
        if (playerUuid == null) {
            ConsoleCommandSender console = Bukkit.getConsoleSender();
            target = console;
        } else {
            Player player = Bukkit.getPlayer(playerUuid);
            if (player == null || !player.isOnline()) {
                return;
            }
            target = player;
        }
        for (String message : messages) {
            target.sendMessage(message);
        }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§e/finditem startfinding <id> <time>");
        sender.sendMessage("§e/finditem starttaking <id> <time> §7(intent only)");
        sender.sendMessage("§e/finditem stopfinding <id>");
        sender.sendMessage("§e/finditem listfinding [page]");
        sender.sendMessage("§e/finditem removefinding <id>");
        sender.sendMessage("§e/finditem clearfinding confirm");
        sender.sendMessage("§e/finditem infoitem <id> §7- record, history depth, snapshot");
        sender.sendMessage("§e/finditem infoplayer <player> §7- what the records say they hold");
        sender.sendMessage("§e/finditem infodupe <id> §7- duplicate evidence");
        sender.sendMessage("§e/finditem readfinding <id> §7- finding rows and their read state");
        sender.sendMessage("§e/finditem readdupe <id> §7- mark findings read (MySQL)");
        sender.sendMessage("§e/finditem checktps §7- this plugin's scan metrics");
    }

    @Override
    public List<String> onTabComplete(
        CommandSender sender,
        Command command,
        String alias,
        String[] args
    ) {
        if (!sender.hasPermission("itemguard.finditem.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            return filter(List.of(
                "startfinding",
                "starttaking",
                "stopfinding",
                "listfinding",
                "removefinding",
                "clearfinding",
                "infoitem",
                "infoplayer",
                "infodupe",
                "readfinding",
                "readdupe",
                "checktps"
            ), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("clearfinding")) {
            return filter(List.of("confirm"), args[1]);
        }
        return List.of();
    }

    private List<String> filter(List<String> values, String input) {
        String normalized = input.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String value : values) {
            if (value.startsWith(normalized)) {
                matches.add(value);
            }
        }
        return matches;
    }
}
