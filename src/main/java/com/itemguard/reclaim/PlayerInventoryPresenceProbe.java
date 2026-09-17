package com.itemguard.reclaim;

import com.itemguard.identity.IdentityTagResolution;
import com.itemguard.services.ItemTrackingService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class PlayerInventoryPresenceProbe implements ItemPresenceProbe {

    private final ItemTrackingService tracking;
    private final IdentityPresenceScanner scanner = new IdentityPresenceScanner();

    public PlayerInventoryPresenceProbe(ItemTrackingService tracking) {
        this.tracking = Objects.requireNonNull(tracking, "tracking");
    }

    @Override
    public PresenceEvidence probe(ReclaimTarget target) {
        if (!Bukkit.isPrimaryThread()) {
            return new PresenceEvidence(
                "PLAYER_INVENTORY",
                PresenceStatus.ERROR,
                "Inventory presence probe must run on the server thread"
            );
        }
        Player player = Bukkit.getPlayer(target.playerUuid());
        if (player == null || !player.isOnline()) {
            return new PresenceEvidence(
                "PLAYER_INVENTORY",
                PresenceStatus.UNAVAILABLE,
                "Player must be online for inventory and ender chest verification"
            );
        }
        try {
            List<IdentityTagResolution> identities = new ArrayList<>();
            collect(identities, player.getInventory().getContents());
            collect(identities, player.getEnderChest().getContents());
            PresenceStatus status = scanner.scan(target.itemUuid(), identities);
            return new PresenceEvidence(
                "PLAYER_INVENTORY",
                status,
                status == PresenceStatus.PRESENT
                    ? "Canonical identity is present in inventory or ender chest"
                    : "Canonical identity is absent from inventory and ender chest"
            );
        } catch (RuntimeException failure) {
            return new PresenceEvidence(
                "PLAYER_INVENTORY",
                PresenceStatus.ERROR,
                failure.getClass().getSimpleName()
            );
        }
    }

    private void collect(
        List<IdentityTagResolution> identities,
        ItemStack[] contents
    ) {
        for (ItemStack item : contents) {
            if (item != null) {
                identities.add(tracking.resolveIdentityTags(item));
            }
        }
    }
}
