package com.itemguard.listeners;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.itemguard.ItemGuard;
import com.itemguard.services.ItemTrackingService;
import java.util.UUID;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

/**
 * Tags item entities the moment they enter the world.
 *
 * <p>Split out of {@link ItemListener} because {@code EntityAddToWorldEvent} is a Paper event
 * that does not exist on Spigot. Bukkit refuses the <em>entire</em> listener class when one
 * handler references a missing event type, so leaving this method inside ItemListener killed
 * all seven handlers on Spigot and disabled item tracking completely — observed on a real
 * Spigot 1.21.4 server, 2026-09-15:
 *
 * <pre>
 * Plugin ItemGuard has failed to register events for class ...ItemListener because
 * com/destroystokyo/paper/event/entity/EntityAddToWorldEvent does not exist.
 * </pre>
 *
 * <p>{@link #isAvailable()} lets the plugin register this listener only where the event
 * exists. On Spigot it is skipped, and the consequence is stated plainly in the listing:
 * items entering the world are tagged on first pickup or container scan instead of at spawn.
 */
public final class PaperEntitySpawnListener implements Listener {

    private final ItemGuard plugin;
    private final ItemTrackingService tracking;

    public PaperEntitySpawnListener(ItemGuard plugin) {
        this.plugin = plugin;
        this.tracking = plugin.getTrackingService();
    }

    /**
     * Whether this server provides the Paper event this listener needs.
     *
     * <p>Checked by class lookup rather than by server brand string: forks rename themselves
     * freely, but either the class is on the classpath or it is not.
     */
    public static boolean isAvailable() {
        try {
            Class.forName("com.destroystokyo.paper.event.entity.EntityAddToWorldEvent");
            return true;
        } catch (ClassNotFoundException absent) {
            return false;
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityAdded(EntityAddToWorldEvent event) {
        if (!(event.getEntity() instanceof Item itemEntity)) {
            return;
        }
        ItemStack item = itemEntity.getItemStack();
        if (!tracking.shouldTrack(item)) {
            return;
        }
        if (tracking.hasCode(item)) {
            tracking.isEntityIdentityReady(itemEntity);
            return;
        }
        UUID entityUuid = itemEntity.getUniqueId();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            org.bukkit.entity.Entity resolved = plugin.getServer().getEntity(entityUuid);
            if (resolved instanceof Item stableItem && stableItem.isValid()) {
                tracking.requestEntityTag(stableItem, null);
            } else {
                plugin.getLogger().warning(
                    "Entity identity capture unavailable after world insertion: uuid="
                        + entityUuid
                );
            }
        });
    }
}
