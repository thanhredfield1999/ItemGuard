package com.itemguard.listeners;

import com.itemguard.ItemGuard;
import com.itemguard.restore.LossReason;
import com.itemguard.services.ItemTrackingService;
import com.itemguard.tracking.UnexplainedRemovalPolicy;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Records why a tracked item stopped existing.
 *
 * <p>Without this, {@code /clear} removed an item and the records still showed it as held, so an
 * admin had nothing to act on.
 *
 * <p>Removal is detected by watching inventories rather than by matching command text. Command text
 * is unreliable — aliases exist, other plugins delete items, and {@code performCommand} does not fire
 * the command event — whereas a tracked item that vanishes while the records say a player was holding
 * it is a removal however it happened.
 */
public class ItemLossListener implements Listener {

    /** Twenty ticks: fast enough that the cause is still obvious, cheap enough to run always. */
    private static final long SCAN_INTERVAL_TICKS = 20L;

    private final ItemGuard plugin;
    private final ItemTrackingService tracking;
    private final UnexplainedRemovalPolicy removalPolicy = new UnexplainedRemovalPolicy();

    /** What each online player was last seen holding: code to item identity. */
    private final Map<UUID, Map<String, UUID>> lastSeen = new HashMap<>();

    /** Identity of each open departure, kept across the retries the offload may need. */
    private final DepartureTokenBook departures = new DepartureTokenBook();

    /**
     * Where departures are reported. Null until {@link #useOffload} runs, and the scan reports
     * nothing while it is: there is no synchronous path left to fall back to, and reinstating one
     * would put the database back on the tick this listener was changed to keep it off.
     */
    private ItemLossScanOffloadCoordinator offload;

    /** Set by {@link #stop}. Nothing is scanned or reported afterwards. */
    private boolean stopped;

    /**
     * Ground item entities that took destroying damage but were still alive when it landed, mapped
     * to the reason to use if they do stop existing.
     *
     * <p>Access-ordered and capped. Damage is not proof of removal, so an entry is only guaranteed
     * to be drained when a removal arrives — and a stack that survives a fire never produces one.
     * Fire damage also repeats on a tick timer, so this map is fed at tick rate and would otherwise
     * grow for as long as the server runs. The oldest note is evicted first: the item damaged most
     * recently is the one most likely to be about to burn up.
     */
    private final Map<UUID, LossReason> pendingDamage =
        new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<UUID, LossReason> eldest) {
                return size() > MAX_PENDING_DAMAGE;
            }
        };

    /**
     * Most simultaneously-burning tracked drops worth remembering.
     *
     * <p>Well above any plausible number of tracked items on fire at once, and small enough that
     * the worst case is a few thousand map entries rather than an unbounded leak. Overflowing means
     * a very old damage note is forgotten, so a genuine burn goes unrecorded — the fail-closed
     * direction, never a false confirmed loss.
     */
    static final int MAX_PENDING_DAMAGE = 4096;

    public ItemLossListener(ItemGuard plugin) {
        this.plugin = plugin;
        this.tracking = plugin.getTrackingService();
    }

    /**
     * Hands the listener the offload it reports departures through. Main thread, before {@link #start}.
     *
     * <p>An injection seam rather than a constructor argument so the offload can be built from the
     * database manager's own executor once that exists, and so a test can drive the same route with a
     * journal it controls.
     */
    public void useOffload(ItemLossScanOffloadCoordinator offload) {
        this.offload = offload;
    }

    /**
     * Disable. Stops scanning and revokes everything in flight.
     *
     * <p>Called before the database closes. After this the coordinator refuses work and drops its
     * entries without calling back into the candidates, so nothing here touches Bukkit once the
     * server has started tearing the plugin down.
     */
    public void stop() {
        stopped = true;
        if (offload != null) {
            offload.shutdown();
        }
    }

    /** Starts the inventory watch. Called once the plugin is enabled. */
    public void start() {
        plugin.getServer().getScheduler().runTaskTimer(
            plugin, this::scanOnlinePlayers, SCAN_INTERVAL_TICKS, SCAN_INTERVAL_TICKS);
    }

    /**
     * Notes why a ground item was hurt. Damage alone is not proof that it stopped existing.
     *
     * <p>An item entity has health, so fire, lava and void damage are survivable, and the
     * {@code OUT_OF_WORLD} removal cause is documented as applying only to entities removed
     * immediately because "some entities get damage instead". Recording a loss here confirmed
     * destruction for an item still lying on the ground, which is a duplication path: the item
     * exists and the records say it was destroyed. The cause is remembered instead, and only turned
     * into a loss once the entity is actually observed leaving the world.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGroundItemDamaged(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Item dropped)) {
            return;
        }
        LossReason reason = ItemLossPolicy.forEntityDamage(event.getCause().name());
        if (reason != null) {
            pendingDamage.put(dropped.getUniqueId(), reason);
        } else {
            // A later damage source supersedes an earlier fire/void attribution.
            // Unknown destruction is preferable to recording a false cause.
            pendingDamage.remove(dropped.getUniqueId());
        }
    }

    /**
     * Turns remembered damage into a recorded loss once the entity has actually left the world.
     *
     * <p>This is the terminal signal the damage handler lacks: the entity is gone, so a reason that
     * confirms destruction can no longer contradict an item that is still lying there. A removal
     * that explains itself another way — picked up, merged, chunk unloaded — is not a loss, so only
     * removals whose cause is consistent with the damage destroying it are recorded.
     *
     * <p>One removal cause needs no damage note at all. {@code Entity.onBelowWorld()} discards with
     * {@code OUT_OF_WORLD} and {@code ItemEntity} does not override it, so an item that falls into
     * the void is removed without ever firing a {@code VOID} damage event. Requiring a note there
     * would silently drop every void loss.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onGroundItemRemoved(EntityRemoveEvent event) {
        // Same guard the scan keeps, for the same reason: after stop() the database is closing, so a
        // write submitted from here is refused by a shutting-down executor and the rejection comes
        // out of a MONITOR handler as a plugin error. Nothing is lost by declining — nothing could
        // have been written after the connection closes either way.
        if (stopped || !(event.getEntity() instanceof Item dropped)) {
            return;
        }
        // Always consume the note, whatever the cause, so it cannot be inherited by a later removal.
        LossReason remembered = pendingDamage.remove(dropped.getUniqueId());
        String cause = event.getCause().name();

        LossReason withoutDamage = ItemLossPolicy.forRemovalWithoutDamage(cause);
        if (withoutDamage != null) {
            tracking.recordLoss(dropped.getItemStack(), withoutDamage, dropped.getLocation(), null);
            return;
        }
        if (remembered == null) {
            return;
        }
        if (!ItemLossPolicy.destroysDamagedItem(cause)) {
            return;
        }
        tracking.recordLoss(dropped.getItemStack(), remembered, dropped.getLocation(), null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGroundItemDespawn(ItemDespawnEvent event) {
        if (stopped) {
            return;
        }
        tracking.recordLoss(
            event.getEntity().getItemStack(),
            ItemLossPolicy.forDespawn(),
            event.getEntity().getLocation(),
            null);
    }

    /**
     * A logout is not a loss, so the player's snapshot is dropped rather than compared.
     *
     * <p>Anything already in flight goes with it. A quit takes the whole inventory out of reach, so
     * there is nothing left to revalidate against and no baseline worth rearming.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID playerUuid = event.getPlayer().getUniqueId();
        lastSeen.remove(playerUuid);
        departures.forgetPlayer(playerUuid);
        if (offload != null) {
            offload.invalidate(playerUuid);
        }
    }

    /**
     * One pass of the inventory watch. Main thread, and it must stay off the database.
     *
     * <p>The pass does three things in order, and the order is the point. Everything the player can
     * still see is reported as present first, because a sighting refutes a loss that may already be
     * queued and the database cannot tell: nothing appends a row for "the stack is back in hand", so
     * the journal's fence would re-read the same old action and write the loss anyway. Then the
     * departures are collected. Only then, and only if the previous pass has finished, is a new
     * generation opened and the departures handed over.
     */
    private void scanOnlinePlayers() {
        if (stopped || offload == null) {
            return;
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            UUID playerUuid = player.getUniqueId();
            Map<String, UUID> current = snapshot(player);
            current.forEach((code, itemUuid) -> seen(playerUuid, code, itemUuid));

            Map<String, UUID> baseline = lastSeen.put(playerUuid, current);
            // After the swap, so a code handed back lands in the baseline this pass just installed
            // rather than in the map being thrown away. A candidate stranded by a refused dispatch
            // gets its code back here; it reads as held for the rest of this pass and is reported
            // again by the next one if it is still gone.
            offload.recoverParked(playerUuid);
            if (baseline == null) {
                continue;
            }

            Map<String, UUID> departed = new HashMap<>();
            baseline.forEach((code, itemUuid) -> {
                if (current.containsKey(code)) {
                    return;
                }
                // Missing from the slots is not the same as destroyed. An identity sitting in the
                // window the player has open is visibly still in the world, and CLEARED confirms
                // destruction, so recording one here would mark an item anybody can see as gone.
                // Checked before anything is handed over, so the suppressed case costs nothing.
                if (stillVisibleToPlayer(player, code, itemUuid)) {
                    seen(playerUuid, code, itemUuid);
                    return;
                }
                departed.put(code, itemUuid);
            });
            if (departed.isEmpty()) {
                continue;
            }
            if (offload.hasPending(playerUuid)) {
                // The previous pass has not drained. Opening a generation now would supersede its
                // entries, and at one pass a second that means a slow journal never finishes one.
                // The codes stay missing instead, so this pass costs nothing and the next retries.
                departed.forEach(current::putIfAbsent);
                continue;
            }

            long generation = offload.beginGeneration(playerUuid);
            departed.forEach((code, itemUuid) -> {
                Optional<UUID> token = departures.tokenFor(playerUuid, code, itemUuid);
                if (token.isEmpty()) {
                    // The book is full. Writing without an identity would risk recording this loss
                    // twice, so the code stays missing and is offered again once a slot frees.
                    current.putIfAbsent(code, itemUuid);
                    return;
                }
                // A refusal rearms through the candidate, so the code is put back either way.
                offload.request(new ScanCandidate(
                    player, playerUuid, code, itemUuid, generation, token.get()
                ));
            });
        }
    }

    /** The identity is in hand or in sight, so no queued loss for it is still true. */
    private void seen(UUID playerUuid, String code, UUID itemUuid) {
        departures.observePresent(playerUuid, code, itemUuid);
        offload.observePresence(code, itemUuid, playerUuid);
    }

    /**
     * One departed identity, and the only place the baseline is edited on its behalf.
     *
     * <p>Every method here runs on the main thread: the coordinator hops back before calling any of
     * them, and the {@link Player} reference is only ever dereferenced there.
     */
    private final class ScanCandidate implements LossCandidate {

        private final Player player;
        private final UUID playerUuid;
        private final String code;
        private final UUID itemUuid;
        private final long generation;
        private final UUID departureToken;
        private final String location;

        ScanCandidate(
            Player player,
            UUID playerUuid,
            String code,
            UUID itemUuid,
            long generation,
            UUID departureToken
        ) {
            this.player = player;
            this.playerUuid = playerUuid;
            this.code = code;
            this.itemUuid = itemUuid;
            this.generation = generation;
            this.departureToken = departureToken;
            // Taken here, on the scan, and kept as text. By the time the journal writes it the
            // player may have walked away or logged out, and a Location cannot be read off the main
            // thread at all — so the position recorded is the one the departure was noticed at.
            this.location = ItemTrackingService.formatLocation(player.getLocation());
        }

        @Override
        public String location() {
            return location;
        }

        @Override
        public String code() {
            return code;
        }

        @Override
        public UUID itemUuid() {
            return itemUuid;
        }

        @Override
        public UUID playerUuid() {
            return playerUuid;
        }

        @Override
        public long generation() {
            return generation;
        }

        @Override
        public UUID departureToken() {
            return departureToken;
        }

        /** Still gone, re-read from the live inventory a few ticks after the sighting. */
        @Override
        public boolean stillMissing() {
            if (stopped || !player.isOnline()) {
                return false;
            }
            return !snapshot(player).containsKey(code)
                && !stillVisibleToPlayer(player, code, itemUuid);
        }

        /**
         * The loss is on disk. The code leaves the baseline, but only if the baseline still means
         * this stack — a code rebound to another item since has a departure of its own to report.
         */
        @Override
        public void recorded() {
            Map<String, UUID> baseline = lastSeen.get(playerUuid);
            if (baseline != null) {
                baseline.remove(code, itemUuid);
            }
            departures.releaseRecorded(playerUuid, code, itemUuid);
            plugin.getLogger().info("loss code=" + code + " reason="
                + ItemLossPolicy.forInventoryRemoval().action() + " player=" + player.getName());
        }

        /**
         * Nothing was written, so the code must stay missing and be offered again.
         *
         * <p>{@code putIfAbsent}: if the stack has come back since, the baseline already holds the
         * newer truth and this rearm has no business overwriting it. The token is deliberately left
         * alone — the same disappearance is still open, and the next attempt has to reuse its
         * identity or the loss can be written twice.
         */
        @Override
        public void rearmBaseline() {
            Map<String, UUID> baseline = lastSeen.get(playerUuid);
            if (baseline != null) {
                baseline.putIfAbsent(code, itemUuid);
            }
        }

        /**
         * Nothing can ever be written for this code, so it leaves the baseline without a loss.
         *
         * <p>Same retirement as {@link #recorded()} — the entry goes and the departure is closed, or
         * the next pass would mint a fresh token for the same doomed write — and deliberately
         * different wording. No row exists behind this line, so "loss" must not appear in it: an
         * admin reading the log has to be able to tell a destroyed item from a tag whose tracked row
         * was pruned out from under it.
         */
        @Override
        public void unrecordable(String reason) {
            Map<String, UUID> baseline = lastSeen.get(playerUuid);
            if (baseline != null) {
                baseline.remove(code, itemUuid);
            }
            departures.releaseRecorded(playerUuid, code, itemUuid);
            plugin.getLogger().warning("unrecordable departure code=" + code
                + " player=" + player.getName() + " reason=" + reason
                + " (no loss recorded: the tracked item row is gone, the item tag is not)");
        }
    }

    /** How many damage notes are currently held. Test seam for the bound above. */
    int pendingDamageSize() {
        return pendingDamage.size();
    }

    /** Whether a damage note is still held for this entity. Test seam for the bound above. */
    boolean hasPendingDamage(UUID entityId) {
        return pendingDamage.containsKey(entityId);
    }

    /**
     * Tracked codes this player is currently holding, mapped to their item identity.
     *
     * <p>The cursor counts as held. A stack picked up inside an inventory screen leaves the slot
     * array for as long as it is being dragged — seconds, against a one-second scan — and it drops
     * with the player if they disconnect while holding it. Reading the slots alone made an ordinary
     * drag look like a removal and wrote a CLEARED row for an item still in hand.
     *
     * <p>Unlike the open window in {@link #stillVisibleToPlayer}, the cursor belongs in the snapshot
     * rather than being a mere suppression: the stack really is this player's, so keeping it here
     * means a deletion off the cursor is still caught by the following scan.
     */
    private Map<String, UUID> snapshot(Player player) {
        Map<String, UUID> held = new HashMap<>();
        collectTracked(held, player.getInventory().getContents());
        collectTracked(held, player.getItemOnCursor());
        collectTracked(held, personalCraftingContents(player));
        return held;
    }

    /**
     * The 2x2 grid of the player's own inventory screen, and nothing else.
     *
     * <p>{@link #stillVisibleToPlayer} suppresses a report for anything sitting in the open view, but
     * suppression alone dropped the identity out of the snapshot that replaces the baseline — so a
     * stack that left the grid afterwards was never compared against anything and its disappearance
     * went unreported for good. The grid is the one part of that view the player actually owns: it
     * has no block behind it and it follows the player rather than staying in the world. Keeping its
     * contents in the snapshot is the same treatment the cursor already gets, and for the same reason.
     *
     * <p>Identified by what the inventory <em>is</em> — a {@link CraftingInventory} whose holder is
     * this player — rather than by {@code InventoryType}. Two reasons, and the first is the weaker
     * one: on Paper 1.21.11 {@code InventoryType} initialises {@code MenuType}, which needs a live
     * registry, so naming the constant makes this method unrunnable anywhere a server is not booted.
     * The second is that the pair is strictly narrower. A crafting table is a {@code CraftingInventory}
     * too and is deliberately excluded, because its holder is the block and not the player: its
     * contents stay in the world when the screen closes, so they are no more this player's holdings
     * than a chest's are.
     *
     * <p>Every other view is left exactly as it was. A chest, barrel or crafting table is storage that
     * belongs to the world, not holdings that belong to this player: closing one is not a departure
     * and its contents are somebody else's business. They keep the suppression and gain no custody,
     * so nothing here can turn a container being closed or unloaded into a recorded destruction.
     *
     * <p>An absent view, an absent top inventory or a type Bukkit does not report reads as "not the
     * player's grid", which is the conservative side: the identity is watched no more closely than
     * before rather than being claimed on a guess.
     */
    private ItemStack[] personalCraftingContents(Player player) {
        InventoryView view = player.getOpenInventory();
        if (view == null) {
            return null;
        }
        Inventory top = view.getTopInventory();
        if (!(top instanceof CraftingInventory grid) || grid.getHolder() != player) {
            return null;
        }
        return grid.getContents();
    }

    /**
     * Whether this identity is still visible in the inventory the player has open.
     *
     * <p>Strictly an existence check and deliberately not a custody claim. The open top inventory is
     * often a chest, which is storage rather than this player's holdings, so nothing is recorded
     * here and the identity is not added to the snapshot. The only conclusion drawn is the narrow
     * one that earns it: an item lying in an open window was not destroyed.
     *
     * <p>This covers the crafting input grid without naming it. A player with no container open
     * still has a crafting view whose top inventory is the 2x2 grid and its result slot, so an
     * ingredient parked there reads as present for as long as it sits there.
     *
     * <p>Paper never returns a null view or top inventory; if one ever did, falling through to the
     * history check restores the previous behaviour rather than silencing every removal.
     */
    private boolean stillVisibleToPlayer(Player player, String code, UUID itemUuid) {
        InventoryView view = player.getOpenInventory();
        if (view == null) {
            return false;
        }
        Inventory top = view.getTopInventory();
        return top != null && containsIdentity(top.getContents(), code, itemUuid);
    }

    /** Whether one of these stacks carries exactly this identity. */
    private boolean containsIdentity(ItemStack[] contents, String code, UUID itemUuid) {
        if (contents == null) {
            return false;
        }
        for (ItemStack stack : contents) {
            if (stack == null) {
                continue;
            }
            // Both halves must match. A code on its own would let a stale or hand-forged tag
            // suppress a real loss; the pair is what the rest of this listener treats as identity.
            if (code.equals(tracking.getCodeFromItem(stack))
                && itemUuid.equals(tracking.getItemUuidFromItem(stack))) {
                return true;
            }
        }
        return false;
    }

    private void collectTracked(Map<String, UUID> into, ItemStack[] contents) {
        if (contents == null) {
            return;
        }
        for (ItemStack stack : contents) {
            collectTracked(into, stack);
        }
    }

    private void collectTracked(Map<String, UUID> into, ItemStack stack) {
        if (stack == null) {
            return;
        }
        String code = tracking.getCodeFromItem(stack);
        UUID itemUuid = tracking.getItemUuidFromItem(stack);
        if (code != null && itemUuid != null) {
            into.put(code, itemUuid);
        }
    }

    /**
     * Gives a disappearance an identity that lasts exactly as long as the disappearance does.
     *
     * <p>Nothing calls this yet. It is the missing half of
     * {@link LossCandidate#departureToken()}: the journal deduplicates on a token, and the token is
     * only worth anything if somebody remembers it across the gap between one scan pass giving up and
     * the next one trying again. {@link #lastSeen} cannot do that job as it stands — a departed code
     * is absent from the snapshot that replaces it, so by the next pass the baseline has forgotten the
     * code entirely. The book remembers what the snapshot drops.
     *
     * <p>The contract, in the order the scan will exercise it:
     *
     * <ul>
     *   <li>{@code tokenFor} mints on the first sighting of a departure and returns the same value on
     *       every later call, so a rearm and the fresh candidate built after it share one identity.</li>
     *   <li>{@code observePresent} is called for every identity in the current snapshot. Seeing the
     *       item is the only thing that ends a departure, so it is the only thing that retires a
     *       token; the next disappearance then mints a new one.</li>
     *   <li>{@code forgetPlayer} drops a player's whole book on quit, exactly as {@code lastSeen} is
     *       dropped — a logout is not a loss and leaves nothing to attribute.</li>
     * </ul>
     *
     * <p>Identity is the pair, never the code alone: the same code carried by a different item UUID is
     * a different physical stack whose departure is its own event. Storage is per player, capped, and
     * there is no server-wide token cache.
     *
     * <p>The cap refuses rather than evicts. Dropping a live token to make room would hand the next
     * resubmission of that departure a new identity and write the loss a second time — the book would
     * be manufacturing exactly the duplicate it exists to prevent, silently, under load. Refusing
     * admission costs one departure its offload: the caller writes nothing and leaves the baseline
     * armed, so the code is still missing, still visible to the next pass, and recorded as soon as a
     * slot frees. Under-reporting late beats double-reporting quietly.
     *
     * <p>Main thread only, like the {@link #lastSeen} map it accompanies. Nothing here is
     * synchronised.
     */
    public static final class DepartureTokenBook {

        /**
         * Departures held per player before new ones are refused.
         *
         * <p>Far above any plausible number of tracked items one player can lose between two scan
         * passes. Reaching it means something is wrong — a mass deletion, or the offload path failing
         * to drain — and in either case refusing new work is the safe response.
         */
        static final int MAX_DEPARTURES_PER_PLAYER = 512;

        private final int maxDeparturesPerPlayer;

        /** Per player, the departures currently believed open, each with its identity. */
        private final Map<UUID, Map<IdentityKey, UUID>> byPlayer = new HashMap<>();

        public DepartureTokenBook() {
            this(MAX_DEPARTURES_PER_PLAYER);
        }

        DepartureTokenBook(int maxDeparturesPerPlayer) {
            if (maxDeparturesPerPlayer < 1) {
                throw new IllegalArgumentException("maxDeparturesPerPlayer must be positive");
            }
            this.maxDeparturesPerPlayer = maxDeparturesPerPlayer;
        }

        /**
         * The token for this player's departure of this identity.
         *
         * <p>An open departure always answers, cap or no cap — it is already being tracked, and
         * withholding its token is what would cause a duplicate. Only a departure the book has never
         * seen can be refused.
         *
         * @return the identity of this departure, or empty when the book is full and this departure
         *     is new. Empty means "do not write this loss and do not retire the baseline"; it is not
         *     an error and it is not permanent.
         */
        public Optional<UUID> tokenFor(UUID playerUuid, String code, UUID itemUuid) {
            Objects.requireNonNull(playerUuid, "playerUuid");
            IdentityKey identity = new IdentityKey(
                Objects.requireNonNull(code, "code"),
                Objects.requireNonNull(itemUuid, "itemUuid")
            );
            Map<IdentityKey, UUID> departures =
                byPlayer.computeIfAbsent(playerUuid, key -> new HashMap<>());

            UUID open = departures.get(identity);
            if (open != null) {
                return Optional.of(open);
            }
            if (departures.size() >= maxDeparturesPerPlayer) {
                // Full. Nothing is dropped and nothing is minted; the caller keeps the code missing.
                return Optional.empty();
            }
            UUID minted = UUID.randomUUID();
            departures.put(identity, minted);
            return Optional.of(minted);
        }

        /**
         * The identity is in hand again, so the departure it had is over.
         *
         * <p>Removes that one pair and nothing else: another stack under the same code, or the same
         * pair held by another player, is a different departure that is still open. Freeing the entry
         * is also what lifts a refusal, so a book at capacity recovers as items come back.
         */
        public void observePresent(UUID playerUuid, String code, UUID itemUuid) {
            Objects.requireNonNull(playerUuid, "playerUuid");
            Map<IdentityKey, UUID> departures = byPlayer.get(playerUuid);
            if (departures == null) {
                return;
            }
            departures.remove(new IdentityKey(
                Objects.requireNonNull(code, "code"),
                Objects.requireNonNull(itemUuid, "itemUuid")
            ));
            if (departures.isEmpty()) {
                byPlayer.remove(playerUuid);
            }
        }

        /**
         * The loss for this departure is on disk, so the departure is over.
         *
         * <p>Same removal as {@link #observePresent}, different reason, and the distinction is worth
         * keeping: one says the item came back, the other says it is gone for good. Releasing only
         * the exact pair matters here too — a code that has since been rebound to another stack has a
         * departure of its own that this success says nothing about.
         */
        public void releaseRecorded(UUID playerUuid, String code, UUID itemUuid) {
            observePresent(playerUuid, code, itemUuid);
        }

        /** The player left. Nothing of theirs is owed a token any more. */
        public void forgetPlayer(UUID playerUuid) {
            byPlayer.remove(Objects.requireNonNull(playerUuid, "playerUuid"));
        }

        /** A code alone is not an identity: the same code on a different stack is a different item. */
        private record IdentityKey(String code, UUID itemUuid) {
        }
    }
}
