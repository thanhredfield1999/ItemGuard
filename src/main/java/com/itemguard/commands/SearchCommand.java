package com.itemguard.commands;

import com.itemguard.ItemGuard;
import com.itemguard.data.ItemData;
import com.itemguard.services.ItemTrackingService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.stream.Collectors;

public class SearchCommand implements CommandExecutor, TabCompleter {

    private final ItemGuard plugin;
    private static final int MAX_RESULTS = 50;

    public SearchCommand(ItemGuard plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        searchItems(sender, args);
        return true;
    }

    public void searchItems(CommandSender sender, String[] args) {
        Player player = sender instanceof Player p ? p : null;

        // Neu khong co args, lay code tu item tren tay
        if (args.length == 0) {
            if (player == null) {
                plugin.getMessages().sendRaw(sender, "invalid-args", Map.of("usage", "/itemguard search <player|code>"));
                return;
            }
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand == null || hand.getType() == Material.AIR) {
                plugin.getMessages().sendRaw(sender, "invalid-args", Map.of("usage", "/itemguard search <player|code>"));
                return;
            }
            String code = plugin.getTrackingService().getCodeFromItem(hand);
            if (code == null) {
                plugin.getMessages().sendRaw(sender, "invalid-args", Map.of("usage", "/itemguard search <player|code>"));
                return;
            }
            Optional<ItemData> itemData = plugin.getDB().getItem(code);
            if (itemData.isPresent()) {
                sender.sendMessage("§e§l=== Tim Kiem: #" + code + " ===");
                sender.sendMessage("  §7- §f" + itemData.get().getDisplayName() + " §8| §7Chu: §f" +
                    (itemData.get().getOwnerName() != null ? itemData.get().getOwnerName() : "Unknown") +
                    " §8| §7Phat hien: §f" + itemData.get().getDetectionCount() + "§7 lan");
            } else {
                plugin.getMessages().sendRaw(sender, "search-empty");
            }
            return;
        }

        String query = args[0];

        // Neu la code (bat dau bang #)
        if (query.startsWith("#")) {
            String code = query.substring(1);
            Optional<ItemData> itemData = plugin.getDB().getItem(code);
            if (itemData.isEmpty()) {
                plugin.getMessages().sendRaw(sender, "search-empty");
                return;
            }
            ItemData item = itemData.get();
            sender.sendMessage("§e§l=== Tim Kiem: #" + code + " ===");
                sender.sendMessage("  §7- §f" + item.getDisplayName() + " §8| §7Chu: §f" +
                    (item.getOwnerName() != null ? item.getOwnerName() : "Unknown") +
                    " §8| §7Phat hien: §f" + item.getDetectionCount() + "§7 lan");
            return;
        }

        // Tim theo ten nguoi choi
        Player target = Bukkit.getPlayer(query);
        if (target != null) {
            List<ItemData> items = plugin.getDB().getItemsByPlayer(target.getUniqueId());
            if (items.isEmpty()) {
                plugin.getMessages().sendRaw(sender, "search-empty");
                return;
            }
            sendPlayerItems(sender, target.getName(), items);
            return;
        }

        // Tim kiem text
        List<ItemData> results = plugin.getDB().searchItems(query);
        if (results.isEmpty()) {
            plugin.getMessages().sendRaw(sender, "search-empty");
            return;
        }
        sendSearchResults(sender, query, results);
    }

    private void sendPlayerItems(CommandSender sender, String playerName, List<ItemData> items) {
        sender.sendMessage(plugin.getMessages().getRaw("search-header", Map.of("player", playerName)));
        sender.sendMessage("  §7Tong so item: §f" + items.size());

        int count = 0;
        for (ItemData item : items) {
            if (count >= MAX_RESULTS) {
                sender.sendMessage("§7... va §f" + (items.size() - count) + " §7item khac");
                break;
            }
            String itemName = item.getDisplayName();
            String material = item.getMaterial() != null ? item.getMaterial().name() : "Unknown";
            sender.sendMessage(plugin.getMessages().getRaw("search-result",
                Map.of("item", itemName, "amount", String.valueOf(item.getDetectionCount()), "code", item.getCode())));
            count++;
        }
    }

    private void sendSearchResults(CommandSender sender, String query, List<ItemData> results) {
        sender.sendMessage("§6§l=== Tim Kiem: " + query + " ===");
        sender.sendMessage("§7Tim thay: §f" + results.size() + " §7ket qua");

        int count = 0;
        for (ItemData item : results) {
            if (count >= MAX_RESULTS) {
                sender.sendMessage("§7... va §f" + (results.size() - count) + " §7ket qua khac");
                break;
            }
            String owner = item.getOwnerName() != null ? item.getOwnerName() : "Unknown";
            sender.sendMessage("  §7- §f" + item.getDisplayName() + " §8| §7Chu: §f" + owner +
                " §8| §7Code: §e" + item.getCode());
            count++;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(plugin.getServer().getOnlinePlayers().stream()
                .map(Player::getName).collect(Collectors.toList()), args[0]);
        }
        return new ArrayList<>();
    }

    private List<String> filter(List<String> list, String input) {
        return list.stream().filter(s -> s.toLowerCase().startsWith(input.toLowerCase())).collect(Collectors.toList());
    }
}
