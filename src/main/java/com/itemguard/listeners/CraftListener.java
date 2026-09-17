package com.itemguard.listeners;

import com.itemguard.ItemGuard;
import com.itemguard.services.ItemTrackingService;
import com.itemguard.tracking.CraftOutputPolicy;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.inventory.ItemStack;

public class CraftListener implements Listener {

    private final ItemGuard plugin;
    private final ItemTrackingService tracking;
    private final CraftOutputPolicy craftOutputPolicy = new CraftOutputPolicy();

    public CraftListener(ItemGuard plugin) {
        this.plugin = plugin;
        this.tracking = plugin.getTrackingService();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        ItemStack result = event.getCurrentItem();
        if (result == null) return;

        boolean hasIdentity = tracking.hasCodeOrUuid(result);
        boolean identityReady = hasIdentity && tracking.isIdentityReady(result);
        boolean eligible = tracking.shouldTrack(result);
        CraftOutputPolicy.Action action = craftOutputPolicy.decide(
            hasIdentity,
            identityReady,
            eligible,
            plugin.getConfigs().cancelsUntrackedCraftOutput()
        );
        if (action == CraftOutputPolicy.Action.ALLOW_UNTRACKED
            || action == CraftOutputPolicy.Action.ALLOW_UNTAGGED_ELIGIBLE) {
            return;
        }

        event.setCancelled(true);
        // English, because LITE is an English-only jar and this line reaches the player. The
        // Vietnamese version shipped inside it (C2, review 2026-09-17) while the listing told
        // buyers the plugin is English.
        //
        // Which message a refusal gets is decided by `CraftOutputPolicy.messageKeyFor`, next to the
        // actions, so the mapping is unit-tested instead of being written twice (H3, review #3).
        plugin.getMessages().send(player, CraftOutputPolicy.messageKeyFor(action));
    }
}
