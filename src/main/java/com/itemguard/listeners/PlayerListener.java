package com.itemguard.listeners;

import com.itemguard.ItemGuard;
import com.itemguard.services.ItemTrackingService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

public class PlayerListener implements Listener {

    private final ItemGuard plugin;
    private final PlayerLifecyclePolicy lifecyclePolicy = new PlayerLifecyclePolicy();

    public PlayerListener(ItemGuard plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            plugin.getTrackingService().scanPlayerInventory(player);
        }, 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        // LITE never constructs the GUI stack (it pulls in Adventure, which Spigot lacks), so
        // this is null there. An unguarded call would throw on every single player quit.
        var gui = plugin.getGuiListener();
        if (gui != null) {
            gui.releasePlayer(event.getPlayer().getUniqueId());
        }
        if (lifecyclePolicy.resolve(PlayerLifecycleEvent.QUIT)
            == PlayerLifecycleAction.RELEASE_CACHE) {
            plugin.getTrackingService().releasePlayer(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        if (lifecyclePolicy.resolve(PlayerLifecycleEvent.DEATH)
            == PlayerLifecycleAction.LOG_DEATH_INVENTORY) {
            plugin.getTrackingService().onItemDeath(event.getEntity());
        }
    }
}
