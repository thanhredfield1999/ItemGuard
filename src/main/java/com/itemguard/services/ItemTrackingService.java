package com.itemguard.services;

import com.itemguard.ItemGuard;
import com.itemguard.data.DatabaseManager;
import com.itemguard.data.ItemData;
import com.itemguard.data.ItemHistory;
import com.itemguard.dupe.ContainerInventoryObservationFactory;
import com.itemguard.dupe.PlayerInventoryObservationFactory;
import com.itemguard.identity.IdentityTagResolution;
import com.itemguard.identity.IdentityTagResolver;
import com.itemguard.identity.IdentityTagStatus;
import com.itemguard.identity.PublicItemCodeGenerator;
import com.itemguard.integrations.TrackingMutationAccessPolicy;

import com.itemguard.snapshot.ItemSnapshot;
import com.itemguard.snapshot.PaperItemSnapshotCodec;
import com.itemguard.snapshot.SnapshotValidationException;
import com.itemguard.tracking.AsyncTagPublicationCoordinator;
import com.itemguard.tracking.BlockContainerPhysicalSlotResolver;
import com.itemguard.tracking.CraftItemStackPhysicalHandle;
import com.itemguard.tracking.EntityPublicationCaptureAction;
import com.itemguard.tracking.EntityPublicationLifecyclePolicy;
import com.itemguard.tracking.InventoryPhysicalSourcePolicy;
import com.itemguard.tracking.ItemIdentityEligibilityPolicy;

import com.itemguard.tracking.IdentityReadinessCoordinator;
import com.itemguard.tracking.TagPublication;
import com.itemguard.tracking.TagPhysicalSourceKey;
import com.itemguard.tracking.TagPublicationState;
import com.itemguard.tracking.TagPublicationTarget;
import com.itemguard.tracking.TagReconciliationReceipt;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Item;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.security.MessageDigest;
import java.util.*;

public class ItemTrackingService {

    private final ItemGuard plugin;
    private final DatabaseManager db;
    private final PublicItemCodeGenerator codeGenerator;
    private final Set<Material> forceTrack;
    private final Set<Material> bypassMaterials;
    private final ItemIdentityEligibilityPolicy identityEligibilityPolicy =
        new ItemIdentityEligibilityPolicy();
    private final IdentityTagResolver identityTagResolver = new IdentityTagResolver();
    /** Plain blocks are not worth a tracked identity; gear and enchanted items are. */
    private final com.itemguard.tracking.TrackingWorthinessPolicy worthinessPolicy =
        new com.itemguard.tracking.TrackingWorthinessPolicy();

    /**
     * Stops a held drop key from writing two permanent rows per second. See
     * {@code docs/design/2026-09-12-history-write-amplification.md}: unbounded writes exhaust storage,
     * push real evidence out of every bounded query window, and cost a database write per keypress.
     */
    private final com.itemguard.history.HistoryWriteGate historyWriteGate =
        new com.itemguard.history.HistoryWriteGate(
            com.itemguard.history.HistoryWriteGate.DEFAULT_WINDOW_MILLIS);

    private final PlayerInventoryObservationFactory observationFactory =
        new PlayerInventoryObservationFactory();
    private final ContainerInventoryObservationFactory containerObservationFactory =
        new ContainerInventoryObservationFactory();
    private final TrackingMutationAccessPolicy mutationAccessPolicy =
        new TrackingMutationAccessPolicy();
    private final EntityPublicationLifecyclePolicy entityPublicationLifecyclePolicy =
        new EntityPublicationLifecyclePolicy();
    private final InventoryPhysicalSourcePolicy inventoryPhysicalSourcePolicy =
        new InventoryPhysicalSourcePolicy();
    private final BlockContainerPhysicalSlotResolver blockContainerSlotResolver =
        new BlockContainerPhysicalSlotResolver();

    private final PaperItemSnapshotCodec snapshotCodec =
        new PaperItemSnapshotCodec(1_048_576);
    private final AsyncTagPublicationCoordinator publicationCoordinator;
    private final IdentityReadinessCoordinator readinessCoordinator;

    private final Map<UUID, Set<String>> playerTrackedItems = new HashMap<>();

    static final String KEY_CODE = "code";
    static final String KEY_ITEM_UUID = "item_uuid";

    /** Built once on first use; the plugin instance is not available at construction time. */
    private volatile ItemGuardKeyCache keyCache;

    private ItemGuardKeyCache keys() {
        ItemGuardKeyCache local = keyCache;
        if (local == null) {
            synchronized (this) {
                local = keyCache;
                if (local == null) {
                    local = new ItemGuardKeyCache(plugin::getNamespacedKey);
                    keyCache = local;
                }
            }
        }
        return local;
    }

    public ItemTrackingService(ItemGuard plugin) {
        this(plugin, new PublicItemCodeGenerator(new java.security.SecureRandom()));
    }

    ItemTrackingService(ItemGuard plugin, PublicItemCodeGenerator codeGenerator) {
        this.plugin = plugin;
        this.codeGenerator = Objects.requireNonNull(codeGenerator, "codeGenerator");
        this.db = plugin.getDB();
        this.forceTrack = plugin.getConfigs().getForceTrackMaterials();
        this.bypassMaterials = EnumSet.of(
            Material.AIR, Material.CAVE_AIR, Material.VOID_AIR
        );
        this.publicationCoordinator = new AsyncTagPublicationCoordinator(
            db,
            runnable -> plugin.getServer().getScheduler().runTask(plugin, runnable),
            plugin::isEnabled,
            System::currentTimeMillis
        );
        this.readinessCoordinator = new IdentityReadinessCoordinator(
            db::reconcile,
            runnable -> plugin.getServer().getScheduler().runTask(plugin, runnable),
            System::currentTimeMillis
        );
    }

    public boolean shouldTrack(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        if (bypassMaterials.contains(item.getType())) return false;
        if (plugin.isLiteEdition()
            && com.itemguard.tracking.TrackingWorthinessPolicy.isLiteExcluded(item.getType().name())) {
            return false;
        }
        // Plain blocks and materials are skipped: the point is finding a lost sword or a duped
        // enchanted tool, and logging every plank costs rows while burying the real entries.
        var meta = item.hasItemMeta() ? item.getItemMeta() : null;
        boolean enchanted = meta != null && meta.hasEnchants();
        boolean customNamed = meta != null && meta.hasDisplayName();
        if (!worthinessPolicy.isWorthTracking(item.getType().name(), enchanted, customNamed)
            && !forceTrack.contains(item.getType())) {
            return false;
        }
        return identityEligibilityPolicy.shouldTrack(
            item.getMaxStackSize(),
            item.getAmount(),
            plugin.getConfigs().isTrackingEnabled(),
            plugin.getConfigs().isTrackNonStackable(),
            plugin.getConfigs().isTrackStackable(),
            forceTrack.contains(item.getType())
        );
    }

    public String getCodeFromItem(ItemStack item) {
        IdentityTagResolution identity = resolveIdentityTags(item);
        return identity.status() == IdentityTagStatus.COMPLETE ? identity.code() : null;
    }

    public UUID getItemUuidFromItem(ItemStack item) {
        IdentityTagResolution identity = resolveIdentityTags(item);
        return identity.status() == IdentityTagStatus.COMPLETE ? identity.itemUuid() : null;
    }

    public IdentityTagResolution resolveIdentityTags(ItemStack item) {
        if (item == null) {
            return identityTagResolver.resolve(null, null);
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return identityTagResolver.resolve(null, null);
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        // Cached: this runs once per hopper per tick, and both keys are immutable.
        NamespacedKey codeKey = keys().code();
        NamespacedKey uuidKey = keys().itemUuid();
        boolean codeHasStringType = pdc.has(codeKey, PersistentDataType.STRING);
        boolean uuidHasStringType = pdc.has(uuidKey, PersistentDataType.STRING);
        return identityTagResolver.resolve(
            pdc.has(codeKey),
            codeHasStringType,
            codeHasStringType ? pdc.get(codeKey, PersistentDataType.STRING) : null,
            pdc.has(uuidKey),
            uuidHasStringType,
            uuidHasStringType ? pdc.get(uuidKey, PersistentDataType.STRING) : null
        );
    }


    public boolean hasCode(ItemStack item) {
        return resolveIdentityTags(item).status() == IdentityTagStatus.COMPLETE;
    }

    public boolean hasCodeOrUuid(ItemStack item) {
        return resolveIdentityTags(item).status() != IdentityTagStatus.ABSENT;
    }

    public boolean isIdentityReady(ItemStack item) {
        if (item == null
            || !identityEligibilityPolicy.supportsIdentity(
                item.getMaxStackSize(), item.getAmount())) {
            return false;
        }
        IdentityTagResolution identity = resolveIdentityTags(item);
        if (identity.status() != IdentityTagStatus.COMPLETE) {
            return false;
        }
        if (readinessCoordinator.isReady(identity)) {
            return true;
        }
        // C2 (review 2026-09-16). Readiness lives in an in-memory cache, so a restart empties it,
        // and the only paths that refilled it were the inventory and container scans — both of
        // which skip the holders whose physical slot cannot be resolved (ender chests, storage
        // minecarts, virtual inventories). A tagged item in one of those stayed unready forever
        // and every click on it was cancelled with nothing said, even though the item and the
        // database were both fine.
        //
        // The identity is already COMPLETE here, so the only open question is whether the
        // journal still knows it. The source key used is one no publication can carry, so this
        // call can never *complete* a PREPARED publication. It can, however, *create* one thing:
        // when the journal has never held this code or uuid at all, reconcileTagPublication adopts
        // the identity and writes a canonical row (last_action = 'ADOPTED'), which is C3's
        // deliberate behaviour and not a side effect. H4 (review 2026-09-17) caught this comment
        // claiming the call "cannot mint anything" — it can, and anyone tightening C3 later needs
        // to know this path mints too.
        // Still false on the first contact, because reconciliation is asynchronous by design.
        reconcilePhysicalIdentity(
            identity,
            TagPhysicalSourceKey.unresolvedHolder(identity.code(), identity.itemUuid()),
            item
        );
        return false;
    }

    public boolean isEntityIdentityReady(Item entity) {
        if (entity == null || !entity.isValid()) {
            return false;
        }
        ItemStack physicalItem = entity.getItemStack();
        IdentityTagResolution identity = resolveIdentityTags(physicalItem);
        return identityEligibilityPolicy.supportsIdentity(
            physicalItem.getMaxStackSize(), physicalItem.getAmount())
            && identity.status() == IdentityTagStatus.COMPLETE
            && reconcilePhysicalIdentity(
                identity,
                TagPhysicalSourceKey.entity(entity.getUniqueId()),
                physicalItem
            );
    }

    private boolean reconcilePhysicalIdentity(
        IdentityTagResolution identity,
        String sourceKey,
        ItemStack physicalItem
    ) {
        if (!identityEligibilityPolicy.supportsIdentity(
            physicalItem.getMaxStackSize(), physicalItem.getAmount())) {
            return false;
        }
        if (readinessCoordinator.isReady(identity)) {
            return true;
        }
        try {
            return readinessCoordinator.reconcile(new TagReconciliationReceipt(
                identity.code(),
                identity.itemUuid(),
                sourceKey,
                snapshotCodec.capture(physicalItem).sha256()
            ));
        } catch (RuntimeException invalidPhysicalSource) {
            return false;
        }
    }

    private void markPublished(TagPublication publication) {
        readinessCoordinator.markReady(
            publication.item().getCode(),
            publication.item().getItemUuid()
        );
        UUID ownerUuid = publication.item().getOwnerUuid();
        if (ownerUuid != null) {
            trackPlayerItem(ownerUuid, publication.item().getCode());
            ItemHistory history = new ItemHistory(
                publication.item().getCode(),
                publication.item().getItemUuid(),
                "SPAWN",
                publication.item().getOwnerName(),
                ownerUuid,
                publication.item().getLastLocation()
            );
            history.setTimestamp(System.currentTimeMillis());
            db.logHistory(history);
        }
    }

    private byte[] sourceDigest(ItemStack item) {
        return snapshotCodec.capture(item).sha256();
    }

    private TagPublication createPublication(
        String sourceKey,
        ItemStack source,
        Player owner
    ) {
        ItemStack tagged = createTaggedClone(source);
        if (tagged == null) {
            throw new IllegalStateException("Cannot create tagged ItemStack clone");
        }
        ItemSnapshot snapshot = snapshotCodec.capture(tagged);
        long now = System.currentTimeMillis();
        IdentityTagResolution identity = resolveIdentityTags(tagged);
        ItemData data = new ItemData(identity.code(), identity.itemUuid());
        data.setMaterial(tagged.getType());
        data.setItemName(getDisplayName(tagged));
        data.setItemLore(getLore(tagged));
        data.setCreatedAt(now);
        data.setLastSeenAt(now);
        data.setLastAction("SPAWN");
        data.setDetectionCount(1);
        if (owner != null) {
            data.setOwnerUuid(owner.getUniqueId());
            data.setOwnerName(owner.getName());
            data.setLastLocation(owner.getLocation());
        }
        return new TagPublication(
            UUID.randomUUID(),
            sourceKey,
            sourceDigest(source),
            data,
            snapshot,
            now,
            TagPublicationState.PREPARED,
            now,
            now,
            null
        );
    }

    private ItemStack createTaggedClone(ItemStack source) {
        ItemStack tagged = source.clone();
        IdentityTagResolution identity = resolveIdentityTags(tagged);
        if (identity.status() != IdentityTagStatus.ABSENT) {
            return null;
        }
        ItemMeta meta = tagged.getItemMeta();
        if (meta == null) {
            return null;
        }
        String code = generateCode();
        UUID itemUuid = UUID.randomUUID();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(keys().code(), PersistentDataType.STRING, code);
        pdc.set(
            keys().itemUuid(),
            PersistentDataType.STRING,
            itemUuid.toString()
        );
        applyVisualTag(meta, tagged, code);
        tagged.setItemMeta(meta);
        return tagged;
    }

    private ItemStack restoreTagged(TagPublication publication) {
        return snapshotCodec.restore(publication.snapshot());
    }

    private boolean submitPublication(
        java.util.function.Supplier<TagPublication> proposal,
        TagPublicationTarget target,
        String failureMessage
    ) {
        try {
            return publicationCoordinator.request(proposal, target);
        } catch (RuntimeException failure) {
            plugin.getLogger().log(
                java.util.logging.Level.SEVERE,
                failureMessage,
                failure
            );
            return false;
        }
    }

    public boolean requestEntityTag(Item entity, Player owner) {
        if (entity == null
            || entityPublicationLifecyclePolicy.captureAction(false, entity.isValid(), 0, 0)
                != EntityPublicationCaptureAction.REQUEST) {
            return false;
        }
        ItemStack source = entity.getItemStack();
        if (!shouldTrack(source)
            || resolveIdentityTags(source).status() != IdentityTagStatus.ABSENT) {
            return false;
        }
        if (!canMutate(entity.getWorld(), owner)) return false;
        String sourceKey = TagPhysicalSourceKey.entity(entity.getUniqueId());
        return submitPublication(
            () -> createPublication(sourceKey, source, owner),
            new TagPublicationTarget() {
                @Override
                public String sourceKey() {
                    return sourceKey;
                }

                @Override
                public boolean matches(byte[] expectedDigest) {
                    if (!entity.isValid()) {
                        return false;
                    }
                    return entityPublicationLifecyclePolicy.canWrite(
                        true,
                        MessageDigest.isEqual(
                            expectedDigest,
                            sourceDigest(entity.getItemStack())
                        )
                    );
                }

                @Override
                public void write(TagPublication publication) {
                    entity.setItemStack(restoreTagged(publication));
                }

                @Override
                public void preparationFailed(Throwable failure) {
                    plugin.getLogger().log(
                        java.util.logging.Level.SEVERE,
                        "Could not reserve the entity identity publication",
                        failure
                    );
                }

                @Override
                public void published(TagPublication publication) {
                    markPublished(publication);
                }

                @Override
                public void publishFailed(TagPublication publication, Throwable failure) {
                    plugin.getLogger().severe(
                        "The identity was written onto the entity but the canonical publish did not "
                            + "complete; staying fail-closed pending reconcile: "
                            + publication.item().getCode()
                    );
                }
            },
            "Could not create the async entity identity publication"
        );
    }

    private boolean requestPlayerInventorySlotTag(
        Inventory inventory,
        int slot,
        Player owner,
        World world,
        String sourceKey
    ) {
        if (inventory == null || slot < 0 || slot >= inventory.getSize()) {
            return false;
        }
        ItemStack source = inventory.getItem(slot);
        if (!shouldTrack(source)
            || resolveIdentityTags(source).status() != IdentityTagStatus.ABSENT) {
            return false;
        }
        Object expectedPhysicalHandle = CraftItemStackPhysicalHandle.extract(source);
        if (expectedPhysicalHandle == null) return false;
        if (!canMutate(world, owner)) return false;
        return submitPublication(
            () -> createPublication(sourceKey, source, owner),
            new TagPublicationTarget() {
                @Override
                public String sourceKey() {
                    return sourceKey;
                }

                @Override
                public boolean matches(byte[] expectedDigest) {
                    ItemStack current = inventory.getItem(slot);
                    return inventoryPhysicalSourcePolicy.matches(
                        expectedPhysicalHandle,
                        CraftItemStackPhysicalHandle.extract(current),
                        expectedDigest,
                        sourceDigest(current)
                    );
                }

                @Override
                public void write(TagPublication publication) {
                    inventory.setItem(slot, restoreTagged(publication));
                }

                @Override
                public void preparationFailed(Throwable failure) {
                    plugin.getLogger().log(
                        java.util.logging.Level.SEVERE,
                        "Could not reserve the inventory identity publication",
                        failure
                    );
                }

                @Override
                public void published(TagPublication publication) {
                    markPublished(publication);
                }

                @Override
                public void publishFailed(TagPublication publication, Throwable failure) {
                    plugin.getLogger().severe(
                        "The identity was written into the inventory slot but the canonical publish did "
                            + "not complete; staying fail-closed pending reconcile: "
                            + publication.item().getCode()
                    );
                }
            },
            "Could not create the async inventory identity publication"
        );
    }

    public boolean requestBlockContainerSlotTag(
        Location location,
        int localSlot,
        Player owner
    ) {
        Optional<BlockContainerPhysicalSlotResolver.ResolvedSlot> initial =
            blockContainerSlotResolver.resolveLoaded(location, localSlot);
        if (initial.isEmpty()) {
            return false;
        }
        BlockContainerPhysicalSlotResolver.ResolvedSlot resolved = initial.orElseThrow();
        ItemStack source = resolved.inventory().getItem(resolved.localSlot());
        if (!shouldTrack(source)
            || resolveIdentityTags(source).status() != IdentityTagStatus.ABSENT) {
            return false;
        }
        Object expectedPhysicalHandle = CraftItemStackPhysicalHandle.extract(source);
        if (expectedPhysicalHandle == null || !canMutate(location.getWorld(), owner)) {
            return false;
        }
        String sourceKey = TagPhysicalSourceKey.blockContainerSlot(
            location.getWorld().getUID(),
            location.getBlockX(),
            location.getBlockY(),
            location.getBlockZ(),
            localSlot
        );
        return submitPublication(
            () -> createPublication(sourceKey, source, owner),
            new TagPublicationTarget() {
                @Override
                public String sourceKey() {
                    return sourceKey;
                }

                @Override
                public boolean matches(byte[] expectedDigest) {
                    Optional<BlockContainerPhysicalSlotResolver.ResolvedSlot> current =
                        blockContainerSlotResolver.resolveLoaded(location, localSlot);
                    if (current.isEmpty()) {
                        return false;
                    }
                    ItemStack item = current.orElseThrow().inventory().getItem(localSlot);
                    return inventoryPhysicalSourcePolicy.matches(
                        true,
                        expectedPhysicalHandle,
                        CraftItemStackPhysicalHandle.extract(item),
                        expectedDigest,
                        sourceDigest(item)
                    );
                }

                @Override
                public void write(TagPublication publication) {
                    BlockContainerPhysicalSlotResolver.ResolvedSlot current =
                        blockContainerSlotResolver.resolveLoaded(location, localSlot)
                            .orElseThrow(() -> new IllegalStateException(
                                "Block container source is no longer loaded"
                            ));
                    ItemStack item = current.inventory().getItem(current.localSlot());
                    if (!inventoryPhysicalSourcePolicy.matches(
                        true,
                        expectedPhysicalHandle,
                        CraftItemStackPhysicalHandle.extract(item),
                        publication.sourceDigest(),
                        sourceDigest(item)
                    )) {
                        throw new IllegalStateException(
                            "Block container source changed before physical write"
                        );
                    }
                    current.inventory().setItem(
                        current.localSlot(),
                        restoreTagged(publication)
                    );
                }

                @Override
                public void preparationFailed(Throwable failure) {
                    plugin.getLogger().log(
                        java.util.logging.Level.SEVERE,
                        "Could not reserve the block-container identity publication",
                        failure
                    );
                }

                @Override
                public void published(TagPublication publication) {
                    markPublished(publication);
                }

                @Override
                public void publishFailed(TagPublication publication, Throwable failure) {
                    plugin.getLogger().severe(
                        "The identity was written into the block-container slot but the canonical "
                            + "publish did not complete; staying fail-closed pending reconcile: "
                            + publication.item().getCode()
                    );
                }
            },
            "Could not create the async block-container identity publication"
        );
    }

    public boolean requestPlayerSlotTag(Player player, int slot) {
        return requestPlayerInventorySlotTag(
            player.getInventory(),
            slot,
            player,
            player.getWorld(),
            TagPhysicalSourceKey.playerSlot(player.getUniqueId(), slot)
        );
    }


    public Optional<ItemData> getTrackedItem(String code) {
        return db.getItem(code);
    }

    public Optional<ItemData> getTrackedItemByUuid(UUID uuid) {
        return db.getItemByUuid(uuid);
    }

    public boolean canTrack(Player player) {
        return canMutate(player.getWorld(), player);
    }

    private boolean canMutate(World world, Player actor) {
        boolean worldKnown = world != null;
        boolean worldEnabled = worldKnown
            && plugin.getConfigs().isWorldEnabled(world.getName());
        boolean worldGuardEnabled = plugin.getConfigs().isWorldGuardEnabled();
        boolean actorAvailable = actor != null
            && worldKnown
            && actor.getWorld().equals(world);
        boolean worldGuardAllowed = actorAvailable
            && plugin.getWorldGuardHook().canTrack(actor);
        return mutationAccessPolicy.canMutate(
            worldKnown,
            worldEnabled,
            worldGuardEnabled,
            actorAvailable,
            worldGuardAllowed
        );
    }

    public void onItemPickup(ItemStack item, Player player) {
        if (!canTrack(player)) return;

        IdentityTagResolution identity = resolveIdentityTags(item);
        if (identity.status() != IdentityTagStatus.COMPLETE) {
            return;
        }

        db.updateItemLastAction(
            identity.code(),
            "PICKUP",
            player.getLocation(),
            player.getName(),
            player.getUniqueId()
        );
        logHistory(identity.code(), identity.itemUuid(), "PICKUP", player);
        trackPlayerItem(player.getUniqueId(), identity.code());
    }

    private void applyVisualTag(ItemMeta meta, ItemStack item, String code) {
        FileConfiguration config = plugin.getConfig();

        // On by default: any item a player uses carries its ID as a dim grey line at the bottom, so
        // a player can read the code off the item without a command.
        if (config.getBoolean("uuid-tag.show-on-item", true)) {
            List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            String tagLine = ChatColor.translateAlternateColorCodes('&', "&8#" + code);

            int position = config.getInt("uuid-tag.lore-position", -1);
            if (position < 0 || position >= lore.size()) {
                lore.add(tagLine);
            } else {
                lore.add(position, tagLine);
            }
            meta.setLore(lore);
        }
    }

    public void onItemDrop(ItemStack item, Player player) {
        String code = getCodeFromItem(item);
        UUID itemUuid = getItemUuidFromItem(item);
        if (code != null && itemUuid != null) {
            db.updateItemLastAction(code, "DROP", player.getLocation(), player.getName(), player.getUniqueId());
            logHistory(code, itemUuid, "DROP", player);
        }
    }

    public void onItemMoveInInventory(ItemStack item, Player player, String action) {
        onItemMoveInInventory(item, player, action, null);
    }

    /**
     * @param containerAt where the container block is, or null to record the player's position.
     *                    A chest transfer must record the chest: the player's position is where they
     *                    stood, and they can reach several containers from one spot then walk away.
     */
    public void onItemMoveInInventory(ItemStack item, Player player, String action,
                                      org.bukkit.Location containerAt) {
        String code = getCodeFromItem(item);
        UUID itemUuid = getItemUuidFromItem(item);
        if (code != null && itemUuid != null) {
            org.bukkit.Location at = containerAt != null ? containerAt : player.getLocation();
            db.updateItemLastAction(code, action, at, player.getName(), player.getUniqueId());
            logHistory(code, itemUuid, action, player, at);
            trackPlayerItem(player.getUniqueId(), code);
        }
    }

    public void onItemUse(ItemStack item, Player player) {
        String code = getCodeFromItem(item);
        UUID itemUuid = getItemUuidFromItem(item);
        if (code != null && itemUuid != null) {
            db.updateItemLastAction(code, "USE", player.getLocation(), player.getName(), player.getUniqueId());
            logHistory(code, itemUuid, "USE", player);
        }
    }

    public void onItemDeath(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && hasCode(item)) {
                String code = getCodeFromItem(item);
                UUID itemUuid = getItemUuidFromItem(item);
                if (code != null && itemUuid != null) {
                    // The stored last action must move too. Without this it stayed at PICKUP, so the
                    // inventory watcher saw a holding action, judged the departure unexplained, and
                    // wrote a false CLEARED row for loot that was lying on the ground. A false loss
                    // row confirms destruction, which would make the item restorable: a dupe path.
                    db.updateItemLastAction(code, "DEATH", player.getLocation(),
                        player.getName(), player.getUniqueId());
                    logHistory(code, itemUuid, "DEATH", player);
                }
            }
        }
    }


    // onContainerOpen was removed: nothing called it, so the CONTAINER_* actions it wrote never
    // occurred. Container transfers are now classified at the click boundary in ItemListener, which
    // is where the source and destination inventories are actually known.

    /**
     * The most recent action recorded for a code, or null when nothing is on record.
     *
     * <p>Reads the newest history row rather than {@code tracked_items.last_action}. The summary
     * column is only as good as every writer remembering to update it, and one that forgot produced
     * a false "removed by command" row for loot lying on the ground. History is append-only, so it
     * cannot drift.
     */
    public String getLastRecordedAction(String code) {
        if (code == null) {
            return null;
        }
        java.util.List<ItemHistory> rows = db.getHistory(code, 1);
        if (rows != null && !rows.isEmpty()) {
            return rows.get(0).getAction();
        }
        return db.getItem(code).map(com.itemguard.data.ItemData::getLastAction).orElse(null);
    }

    /**
     * Records that a tracked item no longer exists, and why.
     *
     * <p>The reason is what an admin acts on: "absent from a sweep" is not actionable, but "burned in
     * lava" or "removed by /clear" is. Writes a history row naming the reason, so a later restore
     * decision has evidence rather than an inference.
     */
    public void recordLoss(ItemStack item, com.itemguard.restore.LossReason reason,
                           org.bukkit.Location at, Player actor) {
        String code = getCodeFromItem(item);
        UUID itemUuid = getItemUuidFromItem(item);
        if (code == null || itemUuid == null) {
            return;
        }
        recordLossByIdentity(code, itemUuid, reason, at, actor);
    }

    /** As {@link #recordLoss}, for when the stack is already gone and only its identity is known. */
    public void recordLossByIdentity(String code, UUID itemUuid,
                                     com.itemguard.restore.LossReason reason,
                                     org.bukkit.Location at, Player actor) {
        if (code == null || itemUuid == null || reason == null) {
            return;
        }
        String actorName = actor == null ? "server" : actor.getName();
        UUID actorUuid = actor == null ? null : actor.getUniqueId();
        db.updateItemLastAction(code, reason.action(), at, actorName, actorUuid);
        ItemHistory history = new ItemHistory(
            code, itemUuid, reason.action(), actorName, actorUuid, formatLocation(at));
        db.logHistory(history);
        plugin.getLogger().info("loss code=" + code + " reason=" + reason.action()
            + " actor=" + actorName);
    }

    private void logHistory(String code, UUID itemUuid, String action, Player player) {
        logHistory(code, itemUuid, action, player, player.getLocation());
    }

    private void logHistory(String code, UUID itemUuid, String action, Player player,
                            org.bukkit.Location at) {
        if (code == null || itemUuid == null) return;
        ItemHistory history = new ItemHistory(
            code, itemUuid, action,
            player.getName(), player.getUniqueId(),
            formatLocation(at != null ? at : player.getLocation())
        );
        // A held drop key would otherwise append two permanent rows per second. Repeats of the same
        // action by the same holder refresh the existing row instead of adding one, so the fact is
        // kept while the duplication is not. Handovers and new actions always append.
        if (historyWriteGate.evaluate(history) == com.itemguard.history.HistoryWriteDecision.COALESCE) {
            db.touchHistory(history);
            return;
        }
        db.logHistory(history);
    }


    private void trackPlayerItem(UUID playerUuid, String code) {
        playerTrackedItems.computeIfAbsent(playerUuid, k -> Collections.synchronizedSet(new HashSet<>()));
        playerTrackedItems.get(playerUuid).add(code);
    }

    public Set<String> getPlayerTrackedItems(UUID playerUuid) {
        Set<String> set = playerTrackedItems.get(playerUuid);
        return set != null ? set : Collections.emptySet();
    }

    public void releasePlayer(UUID playerUuid) {
        playerTrackedItems.remove(playerUuid);
    }

    public void scanPlayerInventory(Player player) {
        scanPlayerInventory(player, null);
    }

    public void scanPlayerInventory(Player player, long scanEpoch) {
        scanPlayerInventory(player, Long.valueOf(scanEpoch));
    }

    private void scanPlayerInventory(Player player, Long scanEpoch) {
        int maxPerPlayer = plugin.getConfigs().getMaxTrackPerPlayer();
        Set<String> tracked = playerTrackedItems.computeIfAbsent(
            player.getUniqueId(), k -> Collections.synchronizedSet(new HashSet<>()));

        int slotCount = player.getInventory().getSize();
        int requested = 0;
        for (int slot = 0; slot < slotCount && requested < maxPerPlayer; slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (item == null
                || !shouldTrack(item)
                || resolveIdentityTags(item).status() != IdentityTagStatus.ABSENT) {
                continue;
            }
            if (requestPlayerSlotTag(player, slot)) {
                requested++;
            }
        }

        long observedAt = System.currentTimeMillis();
        for (int slot = 0; slot < slotCount; slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (item == null
                || !identityEligibilityPolicy.supportsIdentity(
                    item.getMaxStackSize(), item.getAmount())) {
                continue;
            }
            IdentityTagResolution identity = resolveIdentityTags(item);
            if (identity.status() != IdentityTagStatus.COMPLETE) {
                continue;
            }
            if (!reconcilePhysicalIdentity(
                identity,
                TagPhysicalSourceKey.playerSlot(player.getUniqueId(), slot),
                item
            )) {
                continue;
            }
            tracked.add(identity.code());
            db.updateItemLocationOnly(
                identity.code(),
                player.getLocation(),
                player.getName(),
                player.getUniqueId()
            );
            if (scanEpoch != null) {
                observationFactory.create(
                    identity,
                    scanEpoch,
                    player.getUniqueId(),
                    slot,
                    observedAt
                ).ifPresent(db::recordObservation);
            }
        }
    }

    public int scanContainer(org.bukkit.block.Block block) {
        int requested = 0;
        if (block.getState() instanceof org.bukkit.block.Container container) {
            org.bukkit.inventory.Inventory inventory = container.getInventory();
            for (int slot = 0; slot < inventory.getSize(); slot++) {
                ItemStack item = inventory.getItem(slot);
                if (item != null && shouldTrack(item) && !hasCode(item)) {
                    Optional<BlockContainerPhysicalSlotResolver.ResolvedSlot> physical =
                        blockContainerSlotResolver.resolve(inventory, slot);
                    if (physical.isEmpty()) {
                        continue;
                    }
                    BlockContainerPhysicalSlotResolver.ResolvedSlot resolved =
                        physical.orElseThrow();
                    Location location = resolved.location();
                    if (requestBlockContainerSlotTag(
                        location,
                        resolved.localSlot(),
                        null
                    )) {
                        requested++;
                    }
                }
            }
        }
        return requested;
    }

    public int scanContainerInventory(
        org.bukkit.inventory.Inventory inventory,
        Player actor
    ) {
        int requested = 0;
        ItemStack[] contents = inventory.getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item != null && shouldTrack(item) && !hasCode(item)) {
                Optional<BlockContainerPhysicalSlotResolver.ResolvedSlot> physical =
                    blockContainerSlotResolver.resolve(inventory, i);
                if (physical.isEmpty()) {
                    continue;
                }
                BlockContainerPhysicalSlotResolver.ResolvedSlot resolved =
                    physical.orElseThrow();
                Location location = resolved.location();
                if (requestBlockContainerSlotTag(
                    location,
                    resolved.localSlot(),
                    actor
                )) {
                    requested++;
                }
            }
        }
        return requested;
    }

    public void scanOpenBlockContainerInventory(Player player, long scanEpoch) {
        if (!plugin.getConfigs().isContainerScanEnabled()) return;
        org.bukkit.inventory.Inventory inventory = player.getOpenInventory().getTopInventory();
        recordContainerInventoryObservations(inventory, scanEpoch);
    }

    /**
     * Records observations for one already-loaded container block, reusing the exact same
     * identity/eligibility/reconcile path {@link #scanOpenBlockContainerInventory} uses so a
     * closed chest is compared the same way an open one is. Non-container tile entities and
     * ender chests (per-player storage, not a block inventory) are ignored.
     */
    public void recordLoadedContainerObservations(
        org.bukkit.block.BlockState state,
        long scanEpoch
    ) {
        if (!plugin.getConfigs().isContainerScanEnabled()) return;
        if (state instanceof org.bukkit.block.EnderChest) return;
        if (!(state instanceof org.bukkit.block.Container container)) return;
        recordContainerInventoryObservations(container.getInventory(), scanEpoch);
    }

    private void recordContainerInventoryObservations(
        org.bukkit.inventory.Inventory inventory,
        long scanEpoch
    ) {
        long observedAt = System.currentTimeMillis();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null
                || !identityEligibilityPolicy.supportsIdentity(
                    item.getMaxStackSize(), item.getAmount())) {
                continue;
            }
            IdentityTagResolution identity = resolveIdentityTags(item);
            if (identity.status() != IdentityTagStatus.COMPLETE) {
                continue;
            }
            Optional<BlockContainerPhysicalSlotResolver.ResolvedSlot> physical =
                blockContainerSlotResolver.resolve(inventory, slot);
            if (physical.isEmpty()) {
                continue;
            }
            BlockContainerPhysicalSlotResolver.ResolvedSlot resolved =
                physical.orElseThrow();
            Location location = resolved.location();
            if (!reconcilePhysicalIdentity(
                identity,
                TagPhysicalSourceKey.blockContainerSlot(
                    location.getWorld().getUID(),
                    location.getBlockX(),
                    location.getBlockY(),
                    location.getBlockZ(),
                    resolved.localSlot()
                ),
                item
            )) {
                continue;
            }
            containerObservationFactory.create(
                identity,
                scanEpoch,
                location.getWorld().getUID(),
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ(),
                resolved.localSlot(),
                observedAt
            ).ifPresent(db::recordObservation);
        }
    }

    private String generateCode() {
        return codeGenerator.generate();
    }

    private String getDisplayName(ItemStack item) {
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
            String name = item.getItemMeta().getDisplayName();
            return ChatColor.stripColor(name) != null ? name : name;
        }
        return formatMaterialName(item.getType().name());
    }

    private String getLore(ItemStack item) {
        if (!item.hasItemMeta() || !item.getItemMeta().hasLore()) {
            return null;
        }
        List<String> lore = item.getItemMeta().getLore();
        if (lore == null || lore.isEmpty()) {
            return null;
        }
        return String.join("|", lore);
    }

    private String formatMaterialName(String name) {
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

    /**
     * The one way a position is written down, shared so an off-thread writer records the same shape.
     *
     * <p>Static and public because the loss scan has to format on the main thread, before handing the
     * departure to a journal thread that must never touch a {@link org.bukkit.Location}.
     */
    public static String formatLocation(org.bukkit.Location loc) {
        if (loc == null) return "Unknown";
        return String.format("%s (%d, %d, %d)",
            loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }
}
