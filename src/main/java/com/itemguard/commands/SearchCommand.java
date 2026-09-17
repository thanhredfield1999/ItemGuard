package com.itemguard.commands;

import com.itemguard.ItemGuard;
import com.itemguard.data.ItemData;
import com.itemguard.gui.UiMainThreadHandoff;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class SearchCommand implements CommandExecutor, TabCompleter {

    private static final int MAX_RESULTS = 50;

    private final ItemGuard plugin;

    public SearchCommand(ItemGuard plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("itemguard.search")) {
            plugin.getMessages().send(sender, "no-permission");
            return true;
        }
        searchItems(sender, args);
        return true;
    }

    public void searchItems(CommandSender sender, String[] args) {
        Player player = sender instanceof Player onlinePlayer ? onlinePlayer : null;

        if (args.length == 0) {
            if (player == null) {
                sendUsage(sender);
                return;
            }
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand == null || hand.getType() == Material.AIR) {
                sendUsage(sender);
                return;
            }
            String code = plugin.getTrackingService().getCodeFromItem(hand);
            if (code == null) {
                sendUsage(sender);
                return;
            }
            loadCode(sender, code);
            return;
        }

        String query = args[0];
        if (query.startsWith("#")) {
            try {
                loadCode(sender, ItemCodeInput.normalize(query));
            } catch (IllegalArgumentException invalid) {
                sendUsage(sender);
            }
            return;
        }

        Player target = Bukkit.getPlayer(query);
        if (target != null) {
            UUIDPlayer targetPlayer = new UUIDPlayer(target.getUniqueId(), target.getName());
            completeOnMain(
                sender,
                plugin.getDB().getItemsByPlayerAsync(targetPlayer.uuid()),
                items -> {
                    if (items.isEmpty()) {
                        plugin.getMessages().sendRaw(sender, "search-empty");
                        return;
                    }
                    sendPlayerItems(sender, targetPlayer.name(), items);
                }
            );
            return;
        }

        completeOnMain(
            sender,
            plugin.getDB().searchItemsAsync(query),
            results -> {
                if (results.isEmpty()) {
                    plugin.getMessages().sendRaw(sender, "search-empty");
                    return;
                }
                sendSearchResults(sender, query, results);
            }
        );
    }

    private void loadCode(CommandSender sender, String code) {
        completeOnMain(
            sender,
            plugin.getDB().getItemAsync(code),
            result -> renderCode(sender, code, result)
        );
    }

    private void renderCode(CommandSender sender, String code, Optional<ItemData> result) {
        if (result.isEmpty()) {
            plugin.getMessages().sendRaw(sender, "search-empty");
            return;
        }
        ItemData item = result.orElseThrow();
        sender.sendMessage("§e§l=== Tìm kiếm: #" + code + " ===");
        sender.sendMessage("  §7- §f" + item.getDisplayName()
            + " §8| §7Chủ: §f" + displayOwner(item)
            + " §8| §7Phát hiện: §f" + item.getDetectionCount() + "§7 lần");
    }

    private void sendPlayerItems(CommandSender sender, String playerName, List<ItemData> items) {
        sender.sendMessage(plugin.getMessages().getRaw(
            "search-header",
            Map.of("player", playerName)
        ));
        sender.sendMessage("  §7Tổng số vật phẩm: §f" + items.size());

        int count = 0;
        for (ItemData item : items) {
            if (count >= MAX_RESULTS) {
                sender.sendMessage("§7... và §f" + (items.size() - count) + " §7vật phẩm khác");
                break;
            }
            sender.sendMessage("  §7- §f" + item.getDisplayName()
                + " §8| §7Phát hiện: §f" + item.getDetectionCount() + " lần"
                + " §8| §7Mã: §e#" + item.getCode());
            count++;
        }
    }

    private void sendSearchResults(CommandSender sender, String query, List<ItemData> results) {
        sender.sendMessage("§6§l=== Tìm kiếm: " + query + " ===");
        sender.sendMessage("§7Tìm thấy: §f" + results.size() + " §7kết quả");

        int count = 0;
        for (ItemData item : results) {
            if (count >= MAX_RESULTS) {
                sender.sendMessage("§7... và §f" + (results.size() - count) + " §7kết quả khác");
                break;
            }
            sender.sendMessage("  §7- §f" + item.getDisplayName()
                + " §8| §7Chủ: §f" + displayOwner(item)
                + " §8| §7Mã: §e#" + item.getCode());
            count++;
        }
    }

    private String displayOwner(ItemData item) {
        return item.getOwnerName() != null ? item.getOwnerName() : "Không rõ";
    }

    private void sendUsage(CommandSender sender) {
        plugin.getMessages().sendRaw(
            sender,
            "invalid-args",
            Map.of("usage", "/itemguard search <player|#code>")
        );
    }

    private <T> void completeOnMain(
        CommandSender sender,
        CompletableFuture<T> query,
        Consumer<T> renderer
    ) {
        query.whenComplete((value, failure) -> UiMainThreadHandoff.dispatch(plugin, () -> {
            if (failure != null) {
                plugin.getLogger().log(Level.SEVERE, "ItemGuard search failed", failure);
                sender.sendMessage("§e§l[ItemGuard] §cKhông thể tìm kiếm lúc này. Vui lòng thử lại.");
                return;
            }
            renderer.accept(value);
        }));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("itemguard.search")) {
            return List.of();
        }
        if (args.length == 1) {
            return filter(plugin.getServer().getOnlinePlayers().stream()
                .map(Player::getName)
                .collect(Collectors.toList()), args[0]);
        }
        return new ArrayList<>();
    }

    private List<String> filter(List<String> list, String input) {
        return list.stream()
            .filter(value -> value.toLowerCase().startsWith(input.toLowerCase()))
            .collect(Collectors.toList());
    }

    private record UUIDPlayer(java.util.UUID uuid, String name) {
    }
}
