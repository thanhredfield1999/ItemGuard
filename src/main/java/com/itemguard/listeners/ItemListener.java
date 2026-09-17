package com.itemguard.listeners;

import com.itemguard.ItemGuard;
import com.itemguard.identity.IdentityTagResolution;
import com.itemguard.identity.PickupIdentityAction;
import com.itemguard.identity.PickupIdentityPolicy;
import com.itemguard.services.ItemTrackingService;
import com.itemguard.tracking.ContainerTransfer;
import com.itemguard.tracking.ClickSide;
import com.itemguard.tracking.ContainerPositionSource;
import com.itemguard.tracking.ContainerTransferClassifier;
import com.itemguard.tracking.GroundItemMergePolicy;
import com.itemguard.tracking.InventoryKind;
import com.itemguard.tracking.InventoryKindResolver;
import com.itemguard.tracking.PositionSource;
import com.itemguard.tracking.TransferPositionPolicy;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ItemListener implements Listener {

    private final ItemGuard plugin;
    private final ItemTrackingService tracking;
    private final PickupIdentityPolicy pickupIdentityPolicy = new PickupIdentityPolicy();
    private final GroundItemMergePolicy groundItemMergePolicy =
        new GroundItemMergePolicy();
    private final InventoryKindResolver inventoryKindResolver = new InventoryKindResolver();
    private final ContainerTransferClassifier containerTransferClassifier =
        new ContainerTransferClassifier();

    /**
     * H3 (review 2026-09-17): every cancellation in this class used to be silent, and the most
     * common one is not an edge case — after a restart the readiness cache is empty, so the first
     * click, drop or use of a tracked item is refused while the check runs, with nothing said. The
     * player sees their action vanish and concludes the server or the plugin is broken.
     *
     * <p>Told once per player per window rather than once per event: a chest worth of items clicked
     * in a second is one problem, not twenty. The map is pruned and capped for the same reason the
     * container cooldown is.
     */
    private static final long NOTICE_WINDOW_MS = 5_000L;
    private static final int NOTICE_MAX_PLAYERS = 4_096;
    // Keyed by `<player uuid>|<refusal key>`, so one refusal cannot silence a different one (M1,
    // review #3), and bounded in entries rather than players for the same reason.
    private final Map<String, Long> identityNoticeAt = new ConcurrentHashMap<>();

    private void tellIdentityNotReady(Player player) {
        notifyOnce(player, "identity-not-ready");
    }

    /**
     * M8 (review 2026-09-17): a corrupt tag is not a transient state, so it gets its own sentence
     * instead of "try again in a moment" — which sent the player back to repeat an action that can
     * never succeed, with nothing for staff to search for.
     */
    private void tellCorruptTag(Player player) {
        notifyOnce(player, "identity-corrupt");
    }

    /** The pickup was cancelled so the item can be tagged first; say so rather than nothing. */
    private void tellBeingTagged(Player player) {
        notifyOnce(player, "pickup-tagging");
    }

    private void notifyOnce(Player player, String key) {
        long now = System.currentTimeMillis();
        // Keyed by the refusal as well as the player (M1, review #3). Keyed by player alone, three
        // refusals with three different meanings shared one five-second window, so a corrupt tag
        // clicked just after an unrelated refusal was silent - the exact silence this method exists
        // to remove, in the scenario it was written for (a fresh start with an empty cache).
        Long previous = identityNoticeAt.get(player.getUniqueId() + "|" + key);
        if (previous != null && now - previous < NOTICE_WINDOW_MS) {
            return;
        }
        if (identityNoticeAt.size() >= NOTICE_MAX_PLAYERS) {
            identityNoticeAt.values().removeIf(at -> now - at >= NOTICE_WINDOW_MS);
            // L2 (review 2026-09-17): pruning expired entries only bounds the map if some of them
            // have expired. 4,096 players acting inside one five-second window left the cap soft, so
            // the oldest half goes too — the same strategy the container cooldown uses.
            if (identityNoticeAt.size() >= NOTICE_MAX_PLAYERS) {
                identityNoticeAt.entrySet().stream()
                    .sorted(java.util.Map.Entry.comparingByValue())
                    .limit(identityNoticeAt.size() / 2)
                    .map(java.util.Map.Entry::getKey)
                    .toList()
                    .forEach(identityNoticeAt::remove);
            }
        }
        identityNoticeAt.put(player.getUniqueId() + "|" + key, now);
        plugin.getMessages().send(player, key);
    }

    /**
     * Works out what kind of transfer a click was, from the inventory the item left and the one it
     * arrived in. Direction is derived rather than assumed: recording a put as a take would move
     * custody to the player who gave the item away.
     */
    private ContainerTransfer classifyTransfer(InventoryClickEvent event, Player player) {
        org.bukkit.inventory.Inventory clicked = event.getClickedInventory();
        if (clicked == null) {
            return ContainerTransfer.INVENTORY_MOVE;
        }
        InventoryKind clickedKind = kindOf(clicked, player);
        InventoryKind otherKind = kindOf(event.getView().getTopInventory() == clicked
            ? event.getView().getBottomInventory()
            : event.getView().getTopInventory(), player);

        boolean shiftMove = event.isShiftClick();
        if (!shiftMove) {
            // A plain click picks the stack up into the cursor from the clicked inventory, so the
            // clicked side is the source only once it lands elsewhere. Without a destination yet,
            // report movement within the clicked inventory.
            return containerTransferClassifier.classify(clickedKind, clickedKind);
        }
        return containerTransferClassifier.classify(clickedKind, otherKind);
    }

    /**
     * The block position of the container in this click, or null when there is none.
     *
     * <p>Asking the clicked inventory directly is wrong: when a player shift-clicks out of their own
     * inventory, the clicked side is the player's, and Bukkit answers that with the player's own
     * position. That is why chest rows used to record where somebody stood.
     */
    private org.bukkit.Location containerLocation(InventoryClickEvent event, Player player) {
        org.bukkit.inventory.Inventory clicked = event.getClickedInventory();
        org.bukkit.inventory.Inventory top = event.getView().getTopInventory();
        ClickSide side = ContainerPositionSource.pick(kindOf(clicked, player), kindOf(top, player));
        org.bukkit.inventory.Inventory holder = switch (side) {
            case CLICKED -> clicked;
            case TOP -> top;
            case NONE -> null;
        };
        return holder == null ? null : holder.getLocation();
    }

    private InventoryKind kindOf(org.bukkit.inventory.Inventory inventory, Player player) {
        if (inventory == null) {
            return InventoryKind.UNKNOWN;
        }
        if (inventory.equals(player.getInventory())) {
            return InventoryKind.PLAYER;
        }
        if (inventory.equals(player.getEnderChest())) {
            return InventoryKind.ENDER_CHEST;
        }
        return inventoryKindResolver.resolve(
            inventory.getType().name(),
            inventory.getLocation() != null
        );
    }

    private final com.itemguard.tracking.InHandTaggingPolicy inHandTagging =
        new com.itemguard.tracking.InHandTaggingPolicy();
    private final com.itemguard.tracking.ClickedSlotResolution clickedSlots =
        new com.itemguard.tracking.ClickedSlotResolution();

    public ItemListener(ItemGuard plugin) {
        this.plugin = plugin;
        this.tracking = plugin.getTrackingService();
    }

    /**
     * Tags an item sitting in a player's slot straight away instead of leaving it for the
     * 600-tick sweep.
     *
     * <p>Without this, /give produced an item with no ID and the only way to get one was to
     * throw it on the ground and pick it back up. Worse, two identical items issued inside
     * that window both stayed untagged and later received separate identities, so a duplicate
     * looked like two legitimate items.
     */
    private void tagUntrackedInHand(Player player, int slot) {
        if (slot < 0 || !tracking.canTrack(player)) {
            return;
        }
        ItemStack item = player.getInventory().getItem(slot);
        if (item == null || item.getType() == Material.AIR) {
            return;
        }
        boolean tagged = tracking.hasCodeOrUuid(item);
        if (inHandTagging.shouldTagNow(tracking.shouldTrack(item), tagged)) {
            tracking.requestPlayerSlotTag(player, slot);
        }
    }

    /**
     * An operator's /give lands in a slot with no event of its own. The first moment the
     * server tells us about that item is the player selecting or holding the slot, so that is
     * where the gap gets closed.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeldSlotChange(org.bukkit.event.player.PlayerItemHeldEvent event) {
        // MONITOR: this observes and tags, it never alters the event.
        tagUntrackedInHand(event.getPlayer(), event.getNewSlot());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemPickup(PlayerPickupItemEvent event) {
        ItemStack item = event.getItem().getItemStack();
        Player player = event.getPlayer();
        if (!tracking.canTrack(player)) return;

        IdentityTagResolution identity = tracking.resolveIdentityTags(item);
        PickupIdentityAction action = pickupIdentityPolicy.resolve(identity.status());
        if (action == PickupIdentityAction.RECORD) {
            if (!tracking.isEntityIdentityReady(event.getItem())) {
                event.setCancelled(true);
                tellIdentityNotReady(player);
                return;
            }
            tracking.onItemPickup(item, player);
        } else if (action == PickupIdentityAction.TAG_SOURCE && tracking.shouldTrack(item)) {
            // M3 (review 2026-09-17): this refusal had no message. To the player it was the same
            // experience as the silent cancellations H3 fixed - crouch, pick the sword up, nothing
            // happens.
            event.setCancelled(true);
            tellBeingTagged(player);
            tracking.requestEntityTag(event.getItem(), player);
        } else if (action == PickupIdentityAction.IGNORE_CORRUPT) {
            event.setCancelled(true);
            tellCorruptTag(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemDrop(PlayerDropItemEvent event) {
        ItemStack item = event.getItemDrop().getItemStack();
        Player player = event.getPlayer();

        if (tracking.hasCodeOrUuid(item)) {
            if (!tracking.isIdentityReady(item)) {
                event.setCancelled(true);
                tellIdentityNotReady(player);
                return;
            }
            tracking.onItemDrop(item, player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event instanceof CraftItemEvent) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemStack item = event.getCurrentItem();
        if (item == null || item.getType() == Material.AIR) return;

        if (!tracking.hasCodeOrUuid(item)) {
            // Untagged but trackable: tag it now rather than waiting for the sweep. Deferred
            // to the next tick because the click has not been applied yet, so the slot the
            // item ends up in is not known until the event finishes.
            if (tracking.shouldTrack(item)) {
                // getSlot() indexes the CLICKED inventory. With a chest open, chest slot 5 is
                // not player slot 5, so reading it back from the player would inspect an
                // unrelated item. Only the player's own inventory can be read back; anything
                // else is left to the periodic sweep, which is late but never wrong.
                final int slot = clickedSlots.playerSlotOf(
                    event.getClickedInventory() == player.getInventory(), event.getSlot());
                if (slot >= 0) {
                    // Deferred a tick: the click has not been applied yet, so the item is not
                    // in its final slot until the event finishes.
                    plugin.getServer().getScheduler().runTask(
                        plugin, () -> tagUntrackedInHand(player, slot));
                }
            }
            return;
        }
        {
            // No `hasCodeOrUuid` test here: the branch above returns for exactly the opposite case,
            // so repeating it was a second PDC read whose answer was already known (L5, review #3).
            if (!tracking.isIdentityReady(item)) {
                event.setCancelled(true);
                tellIdentityNotReady(player);
                return;
            }
            // Name the actual transfer instead of labelling every chest, ender chest and shulker
            // click INVENTORY_MOVE. See docs/design/2026-09-12-container-transfer-classification.md:
            // only a take out of shared world storage may move custody.
            ContainerTransfer transfer = classifyTransfer(event, player);
            // A chest transfer records the chest, not where the player happened to stand, so a lost
            // item can actually be found again. See docs/design/2026-09-12-loss-and-restore.md.
            org.bukkit.Location containerAt =
                TransferPositionPolicy.sourceFor(transfer) == PositionSource.CONTAINER
                    ? containerLocation(event, player)
                    : null;
            tracking.onItemMoveInInventory(item, player, transfer.action(), containerAt);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        for (int slot : event.getRawSlots()) {
            ItemStack item = event.getNewItems().get(slot);
            if (item == null || item.getType() == Material.AIR) continue;
            if (tracking.hasCodeOrUuid(item)) {
                if (!tracking.isIdentityReady(item)) {
                    event.setCancelled(true);
                    tellIdentityNotReady(player);
                    return;
                }
                tracking.onItemMoveInInventory(item, player, "INVENTORY_DRAG");
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemUse(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (item == null || item.getType() == Material.AIR) return;

        if (tracking.hasCodeOrUuid(item)) {
            if (!tracking.isIdentityReady(item)) {
                event.setCancelled(true);
                tellIdentityNotReady(player);
                return;
            }
            if (event.getAction().name().contains("RIGHT")) {
                tracking.onItemUse(item, player);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemMerge(ItemMergeEvent event) {
        ItemStack source = event.getEntity().getItemStack();
        ItemStack target = event.getTarget().getItemStack();
        GroundItemMergePolicy.Action action = groundItemMergePolicy.decide(
            tracking.hasCodeOrUuid(source),
            tracking.hasCodeOrUuid(target)
        );
        if (action == GroundItemMergePolicy.Action.CANCEL) {
            event.setCancelled(true);
        }
    }

}
