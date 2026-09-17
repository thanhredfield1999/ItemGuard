package com.itemguard.listeners;

import com.itemguard.ItemGuard;
import com.itemguard.services.ItemTrackingService;
import com.itemguard.tracking.BlockContainerPhysicalSlotResolver;
import com.itemguard.tracking.HopperTransferPolicy;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ContainerListener implements Listener {

    private final ItemGuard plugin;
    private final ItemTrackingService tracking;
    private final HopperTransferPolicy hopperTransferPolicy = new HopperTransferPolicy();
    private final BlockContainerPhysicalSlotResolver blockContainerSlotResolver =
        new BlockContainerPhysicalSlotResolver();
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();

    /** How long a container is left alone after a scan; unchanged behaviour. */
    static final long COOLDOWN_WINDOW_MS = 500L;

    /**
     * Upper bound on remembered cooldowns. The map used to grow for the lifetime of the process,
     * one entry per container ever touched, with nothing to remove them.
     */
    static final int MAX_COOLDOWN_ENTRIES = 4_096;

    public ContainerListener(ItemGuard plugin) {
        this.plugin = plugin;
        this.tracking = plugin.getTrackingService();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        if (!plugin.getConfigs().isContainerScanEnabled()) return;
        if (event.isCancelled()) return;

        ItemStack item = event.getItem();
        if (item == null || item.getType() == Material.AIR) return;
        Inventory source = event.getSource();
        Inventory destination = event.getDestination();
        boolean sourceSupported = blockContainerSlotResolver.supports(source);
        boolean destinationSupported = blockContainerSlotResolver.supports(destination);
        HopperTransferPolicy.Action action;
        if (tracking.hasCodeOrUuid(item)) {
            action = hopperTransferPolicy.decide(
                true,
                tracking.isIdentityReady(item),
                true,
                sourceSupported,
                destinationSupported
            );
        } else {
            if (!tracking.shouldTrack(item)) return;
            action = hopperTransferPolicy.decide(
                false,
                false,
                true,
                sourceSupported,
                destinationSupported
            );
        }
        if (action == HopperTransferPolicy.Action.ALLOW) return;

        event.setCancelled(true);
        if (action != HopperTransferPolicy.Action.CANCEL_AND_SCAN_SOURCE) return;

        if (!isCooldownActive(source.getHolder())) {
            setCooldown(source.getHolder());
            plugin.getServer().getScheduler().runTask(plugin, () ->
                tracking.scanContainerInventory(source, null)
            );
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!plugin.getConfigs().isContainerScanEnabled()) return;
        if (event.isCancelled()) return;
        if (!(event.getPlayer() instanceof Player player)) return;

        if (isCooldownActive(event.getInventory().getHolder())) return;

        int requested = tracking.scanContainerInventory(
            event.getInventory(),
            player
        );
        if (requested > 0) {
            setCooldown(event.getInventory().getHolder());
        }
    }

    private boolean isCooldownActive(Object holder) {
        if (holder == null) return false;
        Long last = cooldowns.get(cooldownKey(holder));
        if (last == null) return false;
        return System.currentTimeMillis() - last < COOLDOWN_WINDOW_MS;
    }

    private void setCooldown(Object holder) {
        if (holder == null) return;
        pruneExpiredCooldowns(System.currentTimeMillis());
        cooldowns.put(cooldownKey(holder), System.currentTimeMillis());
    }

    /**
     * A key that names the same container every time it is seen.
     *
     * <p>This used to be {@code holder.toString()} for everything. A block container's holder prints
     * its coordinates and so happened to work, but anything else — a minecart's entity holder, any
     * holder falling back to {@code Object.toString()} — printed an identity hash that changes with
     * each snapshot. Those entries never matched again, so the cooldown silently never fired, and
     * every one of them stayed in the map forever.
     *
     * <p>A double chest needs its own branch (M2, review 2026-09-17). Its holder is
     * {@code org.bukkit.block.DoubleChest}, which is an {@code InventoryHolder} and <em>not</em> a
     * {@code BlockState}, so it fell through to the identity-hash branch — for the most common
     * container there is, that was not "the occasional miss" but a guaranteed one, and every open of
     * a double chest re-read the whole inventory.
     */
    static String cooldownKey(Object holder) {
        if (holder instanceof org.bukkit.block.BlockState block) {
            var world = block.getWorld();
            return "BLOCK:" + (world == null ? "?" : world.getUID())
                + ':' + block.getX() + ':' + block.getY() + ':' + block.getZ();
        }
        if (holder instanceof org.bukkit.block.DoubleChest doubleChest) {
            var location = doubleChest.getLocation();
            if (location == null) {
                return "DOUBLE:?";
            }
            return "DOUBLE:" + (location.getWorld() == null ? "?" : location.getWorld().getUID())
                + ':' + location.getBlockX() + ':' + location.getBlockY() + ':' + location.getBlockZ();
        }
        if (holder instanceof org.bukkit.entity.Entity entity) {
            return "ENTITY:" + entity.getUniqueId();
        }
        // Unknown holder type: nothing stable to name it by. Bounded by the cap below rather than
        // growing without limit; the window is short enough that the occasional miss is harmless.
        return "OTHER:" + holder.getClass().getName() + '@' + System.identityHashCode(holder);
    }

    /**
     * Drop entries that can no longer suppress anything. Ran before each insert rather than on a
     * timer so it needs no scheduled task, and the cap keeps the sweep bounded on a server that
     * opens a great many containers between two identical ones.
     */
    private void pruneExpiredCooldowns(long now) {
        if (cooldowns.size() < MAX_COOLDOWN_ENTRIES) {
            cooldowns.entrySet().removeIf(entry -> now - entry.getValue() >= COOLDOWN_WINDOW_MS);
            return;
        }
        // At the cap, forget the oldest half instead of sweeping repeatedly: a cooldown this old
        // has expired anyway, and dropping it costs at most one extra scan.
        cooldowns.entrySet().stream()
            .sorted(java.util.Map.Entry.comparingByValue())
            .limit(Math.max(1, cooldowns.size() / 2))
            .map(java.util.Map.Entry::getKey)
            .toList()
            .forEach(cooldowns::remove);
    }

    /** Visible for the cooldown tests; the listener never reads it. */
    int cooldownEntries() {
        return cooldowns.size();
    }
}
