package premiumgameplay;

import com.itemguard.ItemGuard;
import com.itemguard.data.ItemHistory;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Fixture-only observer for the Premium real-player gameplay journey. */
@SuppressWarnings("deprecation")
public final class PremiumGameplayProbe extends JavaPlugin implements Listener {
    private ItemGuard guard;

    @Override
    public void onEnable() {
        guard = (ItemGuard) Bukkit.getPluginManager().getPlugin("ItemGuard");
        if (guard == null || !guard.isEnabled() || guard.isLiteEdition()) {
            throw new IllegalStateException("Premium ItemGuard is not enabled");
        }
        Bukkit.getPluginManager().registerEvents(this, this);
        if (getCommand("premiumgameplay") == null) {
            throw new IllegalStateException("premiumgameplay command is missing");
        }
        getCommand("premiumgameplay").setExecutor(this);
        getLogger().info("PREMIUM_GAMEPLAY_PROBE READY");
    }

    @Override
    public boolean onCommand(
        CommandSender sender,
        Command command,
        String label,
        String[] args
    ) {
        if (!(sender instanceof org.bukkit.command.ConsoleCommandSender)) {
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage("usage: /premiumgameplay <identity|history> <player|code>");
            return true;
        }
        if ("identity".equalsIgnoreCase(args[0]) && args.length == 2) {
            identity(args[1]);
            return true;
        }
        if ("history".equalsIgnoreCase(args[0]) && args.length == 2) {
            history(args[1]);
            return true;
        }
        sender.sendMessage("unknown premium gameplay operation");
        return true;
    }

    private void identity(String playerName) {
        new org.bukkit.scheduler.BukkitRunnable() {
            private int samples;

            @Override
            public void run() {
                Player player = Bukkit.getPlayerExact(playerName);
                if (player != null) {
                    for (ItemStack item : player.getInventory().getContents()) {
                        String code = guard.getTrackingService().getCodeFromItem(item);
                        UUID uuid = guard.getTrackingService().getItemUuidFromItem(item);
                        if (code == null || uuid == null) {
                            continue;
                        }
                        guard.getDB().getItemAsync(code).whenComplete((row, failure) ->
                            Bukkit.getScheduler().runTask(PremiumGameplayProbe.this, () -> {
                                if (failure != null || row == null || row.isEmpty()) {
                                    getLogger().severe(
                                        "PREMIUM_GAMEPLAY_IDENTITY_FAILED player=" + playerName
                                    );
                                    return;
                                }
                                getLogger().info(
                                    "PREMIUM_GAMEPLAY_IDENTITY_READY player=" + playerName
                                        + " code=" + code
                                        + " uuid=" + uuid
                                        + " db=true"
                                );
                            })
                        );
                        cancel();
                        return;
                    }
                }
                if (++samples >= 120) {
                    getLogger().severe(
                        "PREMIUM_GAMEPLAY_IDENTITY_FAILED player=" + playerName + " deadline=true"
                    );
                    cancel();
                }
            }
        }.runTaskTimer(this, 1L, 2L);
    }

    private void history(String code) {
        guard.getDB().getHistoryAsync(code, 200).whenComplete((rows, failure) ->
            Bukkit.getScheduler().runTask(this, () -> {
                if (failure != null || rows == null) {
                    getLogger().severe("PREMIUM_GAMEPLAY_HISTORY_FAILED code=" + code);
                    return;
                }
                List<String> actions = new ArrayList<>();
                for (ItemHistory row : rows) {
                    actions.add(row.getAction());
                }
                getLogger().info(
                    "PREMIUM_GAMEPLAY_HISTORY_READY code=" + code
                        + " rows=" + rows.size()
                        + " actions=" + String.join(",", actions)
                );
            })
        );
    }

    private boolean fixturePlayer(Player player) {
        return player.getName().equals("PremiumStaff")
            || player.getName().equals("PremiumMember");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDrop(PlayerDropItemEvent event) {
        if (!fixturePlayer(event.getPlayer())) return;
        getLogger().info(
            "PREMIUM_GAMEPLAY_EVENT DROP player=" + event.getPlayer().getName()
                + " item=" + event.getItemDrop().getItemStack().getType()
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPickup(PlayerPickupItemEvent event) {
        if (!fixturePlayer(event.getPlayer())) return;
        getLogger().info(
            "PREMIUM_GAMEPLAY_EVENT PICKUP player=" + event.getPlayer().getName()
                + " item=" + event.getItem().getItemStack().getType()
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !fixturePlayer(player)) return;
        String title = event.getView().getTitle();
        Object holder = event.getInventory().getHolder();
        String kind = holder == null
            ? "PLAYER_INVENTORY"
            : holder.getClass().getSimpleName();
        getLogger().info(
            "PREMIUM_GAMEPLAY_EVENT INVENTORY_CLICK kind=" + kind
                + " player=" + player.getName()
                + " slot=" + event.getSlot()
                + " title=" + title.replace(' ', '_')
                + " cancelled=" + event.isCancelled()
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !fixturePlayer(player)) return;
        Object holder = event.getInventory().getHolder();
        if (holder != null && !(holder instanceof org.bukkit.inventory.PlayerInventory)) {
            getLogger().info(
                "PREMIUM_GAMEPLAY_EVENT GUI_DRAG kind=" + holder.getClass().getSimpleName()
                    + " player=" + player.getName()
                    + " cancelled=" + event.isCancelled()
            );
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player) || !fixturePlayer(player)) return;
        String title = event.getView().getTitle();
        if (title.contains("ItemGuard")) {
            getLogger().info(
                "PREMIUM_GAMEPLAY_EVENT GUI_CLOSE player=" + player.getName()
                    + " title=" + title.replace(' ', '_')
            );
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        if (!fixturePlayer(event.getPlayer()) || event.getItem() == null) return;
        getLogger().info(
            "PREMIUM_GAMEPLAY_EVENT INTERACT player=" + event.getPlayer().getName()
                + " action=" + event.getAction()
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMerge(ItemMergeEvent event) {
        if (event.isCancelled()) {
            getLogger().info("PREMIUM_GAMEPLAY_EVENT ITEM_MERGE_CANCELLED");
        }
    }
}
