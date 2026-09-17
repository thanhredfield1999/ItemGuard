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
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<String> messages;
            try {
                messages = task.execute(action, playerUuid, actorName);
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
                "clearfinding"
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
