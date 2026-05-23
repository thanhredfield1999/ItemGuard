package com.itemguard.commands;

import com.itemguard.ItemGuard;
import com.itemguard.data.ItemData;
import com.itemguard.services.ItemTrackingService;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

public class CheckCommand implements CommandExecutor, TabCompleter {

    private final ItemGuard plugin;

    public CheckCommand(ItemGuard plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getMessages().send(sender, "player-only");
            return true;
        }
        if (!sender.hasPermission("itemguard.check")) {
            plugin.getMessages().send(sender, "no-permission");
            return true;
        }
        checkItemInHand(player);
        return true;
    }

    public void checkItemInHand(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR) {
            plugin.getMessages().send(player, "check-item-hand");
            return;
        }

        ItemTrackingService tracking = plugin.getTrackingService();
        String code = tracking.getCodeFromItem(hand);
        UUID itemUuid = tracking.getItemUuidFromItem(hand);

        if (code == null) {
            if (tracking.shouldTrack(hand)) {
                ItemStack tagged = tracking.tagItem(hand, player);
                player.getInventory().setItemInMainHand(tagged);
                code = tracking.getCodeFromItem(tagged);
                itemUuid = tracking.getItemUuidFromItem(tagged);
            } else {
                player.sendMessage(plugin.getMessages().getRaw("item-not-tracked"));
                return;
            }
        }

        Optional<ItemData> dataOpt = plugin.getDB().getItem(code);
        if (dataOpt.isEmpty()) {
            plugin.getMessages().send(player, "item-not-tracked");
            return;
        }

        ItemData data = dataOpt.get();
        int historyCount = plugin.getDB().getHistoryCount(code);
        String timeStr = formatTime(data.getCreatedAt());

        player.sendMessage(plugin.getMessages().getRaw("item-info-header"));
        player.sendMessage(plugin.getMessages().getRaw("item-info-code", Map.of("code", code)));
        player.sendMessage(plugin.getMessages().getRaw("item-info-material", Map.of("material", formatMaterial(hand))));
        player.sendMessage(plugin.getMessages().getRaw("item-info-owner", Map.of("owner", data.getOwnerName() != null ? data.getOwnerName() : "Unknown")));
        player.sendMessage(plugin.getMessages().getRaw("item-info-created", Map.of("time", timeStr)));
        player.sendMessage(plugin.getMessages().getRaw("item-info-history", Map.of("count", String.valueOf(historyCount))));

        if (data.getLastLocation() != null) {
            player.sendMessage("  §7Vi tri cuoi: §f" + data.getLastLocation());
        }
        String lastActionName = plugin.getMessages().getRaw("action-" + (data.getLastAction() != null ? data.getLastAction().toLowerCase() : "unknown"),
            data.getLastAction() != null ? data.getLastAction() : "Unknown");
        player.sendMessage("  §7Hanh dong cuoi: §f" + lastActionName);
        if (data.getDetectionCount() > 1) {
            player.sendMessage("  §cSo lan phat hien: §f" + data.getDetectionCount());
        }
    }

    private String formatMaterial(ItemStack item) {
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
            return item.getItemMeta().getDisplayName().replace("§", "&");
        }
        String name = item.getType().name();
        StringBuilder sb = new StringBuilder();
        for (char c : name.toLowerCase().toCharArray()) {
            if (sb.length() == 0) {
                sb.append(Character.toUpperCase(c));
            } else if (c == '_') {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private String formatTime(long timestamp) {
        Instant instant = Instant.ofEpochMilli(timestamp);
        LocalDateTime ldt = LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
        return ldt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return new ArrayList<>();
    }
}
