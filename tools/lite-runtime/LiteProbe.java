package smoke;

import com.itemguard.ItemGuard;
import com.itemguard.tasks.InventoryScanTask;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.List;

/** Test-only observer/seed adapter. Never packaged with the product. */
public final class LiteProbe extends JavaPlugin {
    private ItemGuard guard;
    private String code;
    private final java.util.Map<String, Integer> craftIngredientCount = new java.util.HashMap<>();
    private final java.util.Map<String, Integer> craftSwordCount = new java.util.HashMap<>();

    /**
     * Item entities produced by real player drops, keyed by entity id, oldest first.
     *
     * <p>The only handle this harness has on a PHYSICAL stack. After {@code duplicate} two live
     * stacks carry the same ItemGuard code and the same item UUID — {@code duplicatesafe} requires
     * both to survive — so nothing on the stack itself names one entity rather than the other. The
     * drop event does, and it is the only thing that does.
     */
    private final java.util.LinkedHashMap<java.util.UUID, org.bukkit.entity.Item> tossedDrops =
        new java.util.LinkedHashMap<>();

    /** Samples a pull may take before it gives up. 120 x 4 ticks, unchanged. */
    private static final int PULL_SAMPLES = 120;

    /**
     * One armed crafting-close observation: what was true before the close, and what the close
     * event itself reported.
     *
     * <p>Created fresh by every arm and discarded with its listeners after its case, so a case can
     * never be adjudicated on an acknowledgement that belongs to an earlier close.
     */
    private static final class CraftClose {
        /** Which close is this case's, what a failed read means, what recovery may clear. */
        private final CraftCloseLifecycle lifecycle;
        private final Material staged;
        private final String codeBefore;
        private final String uuidBefore;
        private final int rowsBefore;
        private final int clearedBefore;
        /** Slots this probe filled to make the inventory full, so recovery returns exactly those. */
        private final int[] filled;
        private org.bukkit.event.Listener listener;
        private boolean dropObserved;
        private String reason = "NONE";
        private int closedAt;
        private CraftClose(CraftCloseLifecycle lifecycle, Material staged, String codeBefore,
                           String uuidBefore, int rowsBefore, int clearedBefore, int[] filled) {
            this.lifecycle = lifecycle;
            this.staged = staged;
            this.codeBefore = codeBefore;
            this.uuidBefore = uuidBefore;
            this.rowsBefore = rowsBefore;
            this.clearedBefore = clearedBefore;
            this.filled = filled;
        }
    }

    /** The one armed crafting-close observation, or {@code null} when nothing is armed. */
    private CraftClose armedClose;

    /**
     * The finished full case whose filler is still in the inventory, or {@code null}.
     *
     * <p>Held separately from {@link #armedClose} because the case that took the room has already
     * ended by the time the recovery gives it back: releasing the listener and returning the filler
     * are different obligations, and the first must not wait on the second.
     */
    private CraftClose filledCase;

    /** Ticks a crafting-close case waits for its own client close before giving up. */
    private static final int CLOSE_WAIT_TICKS = 200;

    /** How far around the actor an item entity is looked for. A close-time drop lands at the feet. */
    private static final int DROP_SEARCH_BLOCKS = 8;

    /** Samples the recovery may take, at 5 ticks each, before it gives up. */
    private static final int RECOVER_SAMPLES = 100;

    /** Takes up room for the full case. Never tracked, and never stackable with the identity. */
    private static final Material FILLER = Material.COBBLESTONE;

    @Override public void onEnable() {
        guard = (ItemGuard) Bukkit.getPluginManager().getPlugin("ItemGuard");
        if (guard == null || !guard.isEnabled() || !guard.isLiteEdition()) throw new IllegalStateException("LITE not enabled");
        // Drives probeTick(). Without this every elapsed-tick measurement would read zero and
        // the timing assertions would pass vacuously.
        Bukkit.getScheduler().runTaskTimer(this, () -> probeTicks++, 1L, 1L);
        // Observer only, at MONITOR: the drop has to be on record before `liteprobe pull` is ever
        // sent, because the command arrives after the bot has already thrown the stack.
        Bukkit.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler(
                priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
            public void onDrop(org.bukkit.event.player.PlayerDropItemEvent event) {
                tossedDrops.put(event.getItemDrop().getUniqueId(), event.getItemDrop());
            }
        }, this);
        getLogger().info("LITE_PROBE_BOOT");
    }

    @Override public void onDisable() {
        // Last line of defence for the test-only chunk ticket: no case may leave one behind, and a
        // case that died before its own release still must not outlive the probe.
        releaseHomeChunk();
        // Same discipline for the armed crafting-close listener: nothing a case armed may outlive
        // the case, and a case that died before its own release still must not outlive the probe.
        finishArmedClose(armedClose, CraftCloseLifecycle.Outcome.TIMEOUT);
    }

    private Player player(String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p == null) throw new IllegalStateException("player missing: " + name);
        return p;
    }
    private void check(boolean value, String label) {
        if (!value) throw new IllegalStateException(label);
        getLogger().info("LITE_CASE " + label + " PASS");
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof org.bukkit.command.ConsoleCommandSender)) return true;
        try {
            Player staff = player("LiteStaff");
            Player member = player("LiteMember");
            String action = args.length == 0 ? "" : args[0];
            if (action.equals("seed")) {
                for (Player p : List.of(staff, member)) { p.setOp(false); p.getInventory().clear(); }
                var permissions = staff.addAttachment(this);
                for (String node : List.of("itemguard.search", "itemguard.history.others", "itemguard.stats", "itemguard.notify"))
                    permissions.setPermission(node, true);
                staff.getInventory().setItem(0, new ItemStack(Material.DIAMOND_SWORD));
                staff.getInventory().setHeldItemSlot(0);
                guard.getTrackingService().scanPlayerInventory(staff);
                check(!member.hasPermission("itemguard.search") && staff.hasPermission("itemguard.notify"), "permissions");
            } else if (action.equals("craftstage")) {
                org.bukkit.Location table = staff.getLocation().getBlock().getLocation().add(2, 0, 0);
                table.getBlock().setType(Material.CRAFTING_TABLE);
                check(table.getBlock().getType() == Material.CRAFTING_TABLE, "craft-table-staged");
                // Mineflayer's 1.21.11 use-item-on packet is not acknowledged by this Paper build
                // (no window packet despite a visible nearby table). Open the real workbench via
                // Paper, then keep the security-relevant part client-driven: Mineflayer performs
                // both actual result-slot clicks and the probe observes the real CraftItemEvent.
                staff.openWorkbench(table, true);
                check(staff.getOpenInventory().getTopInventory() instanceof CraftingInventory,
                    "craft-window-opened");
            } else if (action.equals("craftprepare")) {
                // The real client opens this table and clicks output slot 0. The probe only stages a
                // vanilla eligible recipe; it never fires or simulates CraftItemEvent.
                check(staff.getOpenInventory().getTopInventory() instanceof CraftingInventory,
                    "craft-window-open");
                CraftingInventory inventory = (CraftingInventory) staff.getOpenInventory().getTopInventory();
                inventory.setMatrix(new ItemStack[]{
                    new ItemStack(Material.DIAMOND), null, null,
                    new ItemStack(Material.DIAMOND), null, null,
                    new ItemStack(Material.STICK), null, null
                });
                craftIngredientCount.put("before", 3);
                craftSwordCount.put("before", count(staff, Material.DIAMOND_SWORD));
                check(inventory.getResult() != null && inventory.getResult().getType() == Material.DIAMOND_SWORD,
                    "craft-result-staged");
            } else if (action.equals("craftassert")) {
                String mode = args[1];
                CraftingInventory inventory = (CraftingInventory) staff.getOpenInventory().getTopInventory();
                int ingredients = java.util.Arrays.stream(inventory.getMatrix())
                    .filter(java.util.Objects::nonNull).mapToInt(ItemStack::getAmount).sum();
                int output = count(staff, Material.DIAMOND_SWORD) - craftSwordCount.getOrDefault("before", 0);
                // CraftListener's HIGH handler sends the client denial after it cancels. A MONITOR
                // or LOWEST observer does not reliably run after that cancellation in this Paper
                // event path. Cancellation is independently proven by the bot's exactly-one
                // denial receipt; this side proves neither recipe inputs nor output were mutated.
                boolean safe = ingredients == craftIngredientCount.get("before") && output == 0;
                getLogger().info("LITE_CRAFT " + mode + " event=1"
                    + (mode.equals("normal") ? " ingredient=" : " ingredients=") + ingredients + " output=" + output);
                check(safe, "craft-" + mode + "-fail-closed");
            } else if (action.equals("identity")) {
                new org.bukkit.scheduler.BukkitRunnable() {
                    private int samples;
                    @Override public void run() {
                        try {
                            ItemStack item = staff.getInventory().getItemInMainHand();
                            String observed = guard.getTrackingService().getCodeFromItem(item);
                            if (observed != null && guard.getTrackingService().isIdentityReady(item)) {
                                code = observed;
                                getConfig().set("expected-code", code);
                                getConfig().set("expected-uuid", guard.getTrackingService().getItemUuidFromItem(item).toString());
                                saveConfig();
                                check(true, "identity");
                                getLogger().info("LITE_CODE " + code);
                                cancel();
                            } else if (++samples >= 40) {
                                getLogger().severe("LITE_PROBE_FAIL identity-deadline");
                                cancel();
                            }
                        } catch (Throwable failure) {
                            getLogger().log(java.util.logging.Level.SEVERE, "LITE_PROBE_FAIL identity", failure);
                            cancel();
                        }
                    }
                }.runTaskTimer(this, 1L, 5L);
            } else if (action.equals("listener-count")) {
                // Asks Bukkit's handler registry how many ItemGuard handlers are attached to
                // each event. This is the direct measurement for a leaked registration: after
                // bukkit:reload, onDisable must have removed the old instance's listeners, or
                // every event now runs twice while still producing correct-looking results.
                // Counted per listener CLASS, not per event. Several distinct ItemGuard
                // listeners legitimately handle the same event - PlayerJoinEvent alone is
                // handled by PlayerListener, CatalogUi and ItemLossListener - so "this event
                // has 3 ItemGuard handlers" says nothing. A leak is the SAME class registered
                // more than once for the SAME event, which is what this counts.
                java.util.Map<String, Integer> worst = new java.util.TreeMap<>();
                for (org.bukkit.event.HandlerList list : org.bukkit.event.HandlerList.getHandlerLists()) {
                    java.util.Map<String, Integer> perClass = new java.util.HashMap<>();
                    for (org.bukkit.plugin.RegisteredListener registered : list.getRegisteredListeners()) {
                        if (!"ItemGuard".equals(registered.getPlugin().getName())) continue;
                        String name = registered.getListener().getClass().getSimpleName();
                        perClass.merge(name, 1, Integer::sum);
                    }
                    // Keep the highest count seen for each class across all events.
                    perClass.forEach((name, n) -> worst.merge(name, n, Math::max));
                }
                StringBuilder report = new StringBuilder();
                worst.forEach((name, n) -> report.append(name).append('=').append(n).append(';'));
                getLogger().info("LITE_LISTENER_COUNT " + report);
            } else if (action.equals("single-move")) {
                // One inventory move, driven server-side. After bukkit:reload the bot keeps its
                // item but its drops no longer spawn an entity, so bot-driven actions cannot be
                // used to measure post-reload behaviour. The probe does not depend on the
                // client connection.
                // Find the tracked item wherever it sits: the fixture's earlier steps may have
                // left it outside the main hand, and moving a null would record nothing while
                // still reporting DONE - a silent no-op that would read as "no duplicate".
                int from = -1;
                for (int slot = 0; slot < staff.getInventory().getSize() && from < 0; slot++) {
                    ItemStack candidate = staff.getInventory().getItem(slot);
                    if (candidate != null
                        && guard.getTrackingService().getCodeFromItem(candidate) != null) {
                        from = slot;
                    }
                }
                if (from < 0) {
                    getLogger().severe("LITE_PROBE_FAIL single-move-no-tracked-item");
                    return true;
                }
                int to = from == 9 ? 10 : 9;
                ItemStack held = staff.getInventory().getItem(from);
                staff.getInventory().setItem(from, null);
                staff.getInventory().setItem(to, held);
                getLogger().info("LITE_SINGLE_MOVE_DONE from=" + from + " to=" + to);
            } else if (action.equals("history-rows")) {
                // Raw row count for this identity. A listener registered twice still produces
                // a correct ID and a correct-looking history; the only visible symptom is that
                // every action is recorded twice, so the count is the measurement that shows it.
                //
                // The code is re-read from the item rather than taken from the `code` field:
                // bukkit:reload constructs a NEW plugin instance whose fields are null, so the
                // remembered value does not survive the very event under test.
                String queryCode = code;
                if (queryCode == null) {
                    for (int slot = 0; slot < staff.getInventory().getSize() && queryCode == null; slot++) {
                        ItemStack candidate = staff.getInventory().getItem(slot);
                        if (candidate != null) {
                            queryCode = guard.getTrackingService().getCodeFromItem(candidate);
                        }
                    }
                    if (queryCode == null) queryCode = getConfig().getString("expected-code");
                }
                if (queryCode == null) {
                    getLogger().severe("LITE_PROBE_FAIL history-rows-no-code");
                    return true;
                }
                final String rowCode = queryCode;
                guard.getDB().getHistoryAsync(rowCode, 500).whenComplete((rows, failure) ->
                    Bukkit.getScheduler().runTask(this, () -> {
                        if (failure != null || rows == null) {
                            getLogger().severe("LITE_PROBE_FAIL history-rows");
                        } else {
                            getLogger().info("LITE_HISTORY_ROWS " + rows.size() + " code=" + rowCode);
                        }
                    }));
            } else if (action.equals("two-items")) {
                // Two tracked items in one inventory. Everything so far has used exactly one,
                // which cannot catch slot-index confusion: with a single item, reading the
                // wrong slot usually finds nothing and fails loudly. With two, reading the
                // wrong slot finds the OTHER tracked item and the mistake looks like success.
                new org.bukkit.scheduler.BukkitRunnable() {
                    private int samples;
                    @Override public void run() {
                        try {
                            String zero = null, one = null;
                            ItemStack a = staff.getInventory().getItem(0);
                            ItemStack b = staff.getInventory().getItem(1);
                            if (a != null) zero = guard.getTrackingService().getCodeFromItem(a);
                            if (b != null) one = guard.getTrackingService().getCodeFromItem(b);
                            if (zero != null && one != null) {
                                getLogger().info("LITE_TWO_ITEMS slot0=" + zero + " slot1=" + one
                                    + " distinct=" + !zero.equals(one));
                                cancel();
                            } else if (++samples >= 60) {
                                getLogger().info("LITE_TWO_ITEMS slot0=" + zero + " slot1=" + one
                                    + " distinct=false");
                                cancel();
                            }
                        } catch (Throwable failure) {
                            getLogger().log(java.util.logging.Level.SEVERE, "LITE_PROBE_FAIL two-items", failure);
                            cancel();
                        }
                    }
                }.runTaskTimer(this, 1L, 5L);
            } else if (action.equals("seed-two")) {
                // Two distinct trackable items, side by side in slots 0 and 1.
                staff.getInventory().setItem(0, new ItemStack(org.bukkit.Material.DIAMOND_SWORD));
                staff.getInventory().setItem(1, new ItemStack(org.bukkit.Material.NETHERITE_PICKAXE));
                getLogger().info("LITE_SEED_TWO_DONE");
            } else if (action.equals("digest")) {
                // The snapshot digest hashes the whole item, so a cosmetic edit is expected to
                // change it. Printed so the two-plugin fixture can show identity and digest
                // moving independently.
                ItemStack item = staff.getInventory().getItemInMainHand();
                var codec = new com.itemguard.snapshot.PaperItemSnapshotCodec(64 * 1024);
                byte[] sha = codec.capture(item).sha256();
                StringBuilder hex = new StringBuilder();
                for (byte b : sha) hex.append(String.format("%02x", b));
                getLogger().info("LITE_DIGEST " + hex);
            } else if (action.equals("forge-socket")) {
                // Reproduces BastionForge's write pattern exactly as read from
                // BukkitLiteItemPort.java:139 - editMeta in place, rewrite lore and attribute
                // modifiers, set one namespaced key of its own. Driven here rather than
                // through the forge GUI because socketing needs menu clicks a bot cannot
                // reliably perform; BastionForge is installed and enabled either way.
                ItemStack item = staff.getInventory().getItemInMainHand();
                org.bukkit.NamespacedKey forgeKey =
                    new org.bukkit.NamespacedKey("bastionlite", "state");
                item.editMeta(meta -> {
                    meta.getPersistentDataContainer().set(
                        forgeKey, org.bukkit.persistence.PersistentDataType.STRING,
                        "gem=ruby;level=3");
                    meta.setLore(java.util.List.of("Socket 1: Ruby", "+3 Attack"));
                    meta.addAttributeModifier(
                        org.bukkit.attribute.Attribute.ATTACK_DAMAGE,
                        new org.bukkit.attribute.AttributeModifier(
                            new org.bukkit.NamespacedKey("bastionlite", "gem_atk"),
                            3.0,
                            org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER,
                            org.bukkit.inventory.EquipmentSlotGroup.HAND));
                });
                staff.getInventory().setItemInMainHand(item);
                getLogger().info("LITE_SOCKET_APPLIED");
            } else if (action.equals("history-churn")) {
                // Generate real history writes so the kill lands while SQLite has work in
                // flight. An idle server killed cleanly proves nothing about durability.
                new org.bukkit.scheduler.BukkitRunnable() {
                    private int moves;
                    @Override public void run() {
                        try {
                            ItemStack item = staff.getInventory().getItemInMainHand();
                            if (item == null || item.getType() == org.bukkit.Material.AIR) { cancel(); return; }
                            // Drop and re-give in place: each cycle writes history rows.
                            staff.getInventory().setItemInMainHand(null);
                            staff.getInventory().setItemInMainHand(item);
                            if (++moves == 1) getLogger().info("LITE_CHURN_STARTED");
                            if (moves >= 200) cancel();
                        } catch (Throwable failure) {
                            getLogger().log(java.util.logging.Level.SEVERE, "LITE_PROBE_FAIL history-churn", failure);
                            cancel();
                        }
                    }
                }.runTaskTimer(this, 1L, 1L);
            } else if (action.equals("identity-readback")) {
                // Read the ID back out of the item itself after the crash. The PDC is the
                // claim under test; the database only has to agree with it.
                new org.bukkit.scheduler.BukkitRunnable() {
                    private int samples;
                    @Override public void run() {
                        try {
                            // Search the whole inventory, not just the main hand: after a
                            // crash the player rejoins and the held slot may differ. Looking
                            // only at the hand would report a lost identity for an item that
                            // is sitting two slots away, which is a false alarm, not a bug.
                            String observed = null;
                            int foundSlot = -1;
                            for (int slot = 0; slot < staff.getInventory().getSize(); slot++) {
                                ItemStack candidate = staff.getInventory().getItem(slot);
                                if (candidate == null) continue;
                                String code = guard.getTrackingService().getCodeFromItem(candidate);
                                if (code != null) { observed = code; foundSlot = slot; break; }
                            }
                            ItemStack item = foundSlot >= 0
                                ? staff.getInventory().getItem(foundSlot) : null;
                            if (observed != null) {
                                getLogger().info("LITE_READBACK " + observed
                                    + " slot=" + foundSlot
                                    + " ready=" + guard.getTrackingService().isIdentityReady(item)
                                    + " expected=" + getConfig().getString("expected-code"));
                                cancel();
                            } else if (++samples >= 40) {
                                getLogger().info("LITE_READBACK NONE expected="
                                    + getConfig().getString("expected-code"));
                                cancel();
                            }
                        } catch (Throwable failure) {
                            getLogger().log(java.util.logging.Level.SEVERE, "LITE_PROBE_FAIL identity-readback", failure);
                            cancel();
                        }
                    }
                }.runTaskTimer(this, 1L, 5L);
            } else if (action.equals("history")) {
                guard.getDB().getHistoryAsync(code, 45).whenComplete((rows, failure) -> Bukkit.getScheduler().runTask(this, () -> {
                    if (failure != null || rows == null || rows.isEmpty()) getLogger().severe("LITE_PROBE_FAIL history");
                    else check(rows.stream().allMatch(row -> code.equals(row.getCode())), "history");
                }));
            } else if (action.equals("gui")) {
                var view = staff.getOpenInventory();
                var top = view.getTopInventory();
                var holder = top.getHolder();
                // The click already advanced the overview to that identity's timeline, so accept any
                // LiteCommand Preview subclass. Comparing the concrete class name to the abstract
                // base would never match: the real holder is OverviewPreview/TimelinePreview.
                boolean preview = false;
                for (Class<?> type = holder == null ? null : holder.getClass(); type != null; type = type.getSuperclass()) {
                    if (type.getName().equals("com.itemguard.lite.LiteCommand$Preview")) { preview = true; break; }
                }
                check(preview, "gui-open");
                // Zone contract: a decorative frame, a labelled guide, and labelled navigation.
                check(top.getItem(4) != null && top.getItem(4).getType() == Material.BOOK, "gui-guide-row");
                // The navigation row no longer carries a close button: Minecraft already closes the
                // screen, so the timeline's exit is the labelled Back arrow at the bottom left.
                // Material alone would not prove that — paging uses ARROW too — so assert the exact
                // English label, which makes a "Previous page" arrow in this slot fail.
                ItemStack back = top.getItem(45);
                String backLabel = back == null || back.getItemMeta() == null ? null
                    : org.bukkit.ChatColor.stripColor(back.getItemMeta().getDisplayName());
                check(back != null && back.getType() == Material.ARROW && "Back".equals(backLabel),
                    "gui-back-button");
                ItemStack corner = top.getItem(0);
                check(corner != null && corner.getType().name().endsWith("GLASS_PANE"), "gui-framed-border");
                check(top.getItem(10) != null && !top.getItem(10).getType().isAir()
                    && staff.getItemOnCursor().getType().isAir(), "gui-click-readonly");
                { boolean probeClose = ProbeInitiatedClose.enter();
                  try { staff.closeInventory(); }
                  finally { ProbeInitiatedClose.exit(probeClose); } }
            } else if (action.equals("custodyself")) {
                // The staff bot threw its own item and picked it back up. Custody must not move.
                String expected = getConfig().getString("expected-code");
                guard.getDB().getHistoryAsync(expected, 45).whenComplete((rows, failure) -> Bukkit.getScheduler().runTask(this, () -> {
                    if (failure != null || rows == null || rows.isEmpty()) {
                        getLogger().severe("LITE_PROBE_FAIL custody-self-history");
                        return;
                    }
                    var chain = com.itemguard.custody.CustodyChain.replay(rows,
                        new com.itemguard.custody.CustodyTransferPolicy(0L));
                    getLogger().info("LITE_CUSTODY self transfers=" + chain.transfers()
                        + " holders=" + chain.distinctHolders()
                        + " current=" + (chain.currentHolder() == null ? "none" : chain.currentHolder()));
                    check(chain.transfers() == 0 && chain.distinctHolders() == 1
                        && staff.getUniqueId().equals(chain.currentHolder()),
                        "custody-self-drop-not-counted");
                }));
            } else if (action.equals("custodytransfer")) {
                // The member bot picked up the staff bot's item. Custody must move exactly once.
                String expected = getConfig().getString("expected-code");
                guard.getDB().getHistoryAsync(expected, 45).whenComplete((rows, failure) -> Bukkit.getScheduler().runTask(this, () -> {
                    if (failure != null || rows == null || rows.isEmpty()) {
                        getLogger().severe("LITE_PROBE_FAIL custody-transfer-history");
                        return;
                    }
                    var chain = com.itemguard.custody.CustodyChain.replay(rows,
                        new com.itemguard.custody.CustodyTransferPolicy(0L));
                    getLogger().info("LITE_CUSTODY transfer transfers=" + chain.transfers()
                        + " holders=" + chain.distinctHolders()
                        + " current=" + (chain.currentHolder() == null ? "none" : chain.currentHolder()));
                    check(chain.transfers() == 1 && chain.distinctHolders() == 2
                        && member.getUniqueId().equals(chain.currentHolder())
                        && chain.holders().contains(staff.getUniqueId()),
                        "custody-handover-counted-once");
                }));
            } else if (action.equals("custodypingpong")) {
                // The same two bots swapped repeatedly. Custody stays correct, the count is throttled.
                String expected = getConfig().getString("expected-code");
                long cooldown = com.itemguard.custody.CustodyWindow.DEFAULT_MILLIS;
                guard.getDB().getHistoryAsync(expected, 45).whenComplete((rows, failure) -> Bukkit.getScheduler().runTask(this, () -> {
                    if (failure != null || rows == null || rows.isEmpty()) {
                        getLogger().severe("LITE_PROBE_FAIL custody-pingpong-history");
                        return;
                    }
                    var chain = com.itemguard.custody.CustodyChain.replay(rows,
                        new com.itemguard.custody.CustodyTransferPolicy(cooldown));
                    getLogger().info("LITE_CUSTODY pingpong transfers=" + chain.transfers()
                        + " holders=" + chain.distinctHolders()
                        + " cooldownMs=" + cooldown);
                    check(chain.transfers() <= 1 && chain.distinctHolders() == 2,
                        "custody-pingpong-throttled");
                }));
            } else if (action.equals("pull")) {
                // Server-side pickup of ONE physical drop, by the player that was asked to collect
                // it. The entity comes from the real PlayerDropItemEvent this bot's toss produced,
                // and the receipt is the pickup event for THAT entity plus the tracked identity
                // landing in THAT player's inventory.
                //
                // What this replaced: the nearest world Item whose ItemGuard code matched, latched
                // once, never re-resolved, and reported as pulled the moment that reference stopped
                // being valid. Every part of that is satisfiable without a pickup. `duplicate`
                // clones the staff's stack into the member and `duplicatesafe` requires both to
                // survive, so from there two live stacks share the code AND the item UUID: neither
                // field distinguishes physical entities. And !isValid() is equally true of a
                // despawn, of a merge between those two stacks, and of the OTHER bot collecting it
                // while standing on the same block. Every custody case in this scope is adjudicated
                // off this one line.
                Player target = player(args[1]);
                final java.util.UUID expected =
                    java.util.UUID.fromString(getConfig().getString("expected-uuid", ""));
                org.bukkit.entity.Item onGround = null;
                int live = 0;
                for (java.util.Iterator<org.bukkit.entity.Item> known =
                        tossedDrops.values().iterator(); known.hasNext();) {
                    org.bukkit.entity.Item candidate = known.next();
                    if (!candidate.isValid()) {
                        known.remove();
                        continue;
                    }
                    if (!isTracked(candidate.getItemStack())) {
                        continue;
                    }
                    live++;
                    onGround = candidate;
                }
                if (live != 1) {
                    // 0: the toss moved nothing, so there is no drop to collect and polling for one
                    // could only ever end in a deadline — which is how this failure used to be
                    // reported. 2+: two same-identity entities are lying there and no receipt could
                    // honestly say which of them a pickup was.
                    getLogger().severe("LITE_PROBE_FAIL pull-drop-not-isolated actor="
                        + target.getName() + " liveTrackedDrops=" + live);
                    return true;
                }
                final org.bukkit.entity.Item drop = onGround;
                final java.util.UUID dropId = drop.getUniqueId();
                final java.util.UUID[] collector = new java.util.UUID[1];
                final org.bukkit.event.Listener pickup = new org.bukkit.event.Listener() {
                    @org.bukkit.event.EventHandler(
                        priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
                    public void onPickup(org.bukkit.event.entity.EntityPickupItemEvent event) {
                        if (event.getItem().getUniqueId().equals(dropId)
                            && event.getEntity() instanceof Player who) {
                            collector[0] = who.getUniqueId();
                        }
                    }
                };
                Bukkit.getPluginManager().registerEvents(pickup, this);
                // Isolate the other actor before the target is moved in: the teleport puts the
                // target inside the one-block vanilla collection radius of whoever already stands
                // there, and that is exactly how the member collected the staff's stack on fixture
                // de95793790e4. Same bounded separation the rehome case already uses.
                for (Player other : List.of(staff, member)) {
                    if (!other.getUniqueId().equals(target.getUniqueId())
                        && distanceTo(other, drop.getLocation()) < PICKUP_SEPARATION_BLOCKS) {
                        org.bukkit.Location parked =
                            drop.getLocation().add(MEMBER_SEPARATION_BLOCKS, 0, 0);
                        parked.setY(parked.getWorld().getHighestBlockYAt(parked) + 1);
                        other.teleport(parked);
                    }
                }
                new org.bukkit.scheduler.BukkitRunnable() {
                    private int samples;
                    /** Everything observed, then the listener dropped, then one verdict. */
                    private void report(String failure, java.util.UUID identity) {
                        cancel();
                        org.bukkit.event.HandlerList.unregisterAll(pickup);
                        getLogger().info("LITE_PULL actor=" + target.getName()
                            + " entity=" + dropId
                            + " collector=" + (collector[0] == null ? "none" : collector[0])
                            + " identity=" + (identity == null ? "none" : identity)
                            + " entityValid=" + drop.isValid() + " samples=" + samples);
                        if (failure == null) {
                            getLogger().info("LITE_PULLED " + target.getName());
                        } else {
                            getLogger().severe("LITE_PROBE_FAIL " + failure
                                + " actor=" + target.getName() + " entity=" + dropId);
                        }
                    }
                    @Override public void run() {
                        try {
                            if (collector[0] != null) {
                                if (!target.getUniqueId().equals(collector[0])) {
                                    report("pull-wrong-actor", null);
                                    return;
                                }
                                // The stack is put into the inventory after the event returns, so
                                // this is read on a later sample: the receipt names where the
                                // identity actually is, not merely that an event fired.
                                java.util.UUID landed = null;
                                for (ItemStack stack : target.getInventory().getContents()) {
                                    java.util.UUID identity = stack == null ? null
                                        : guard.getTrackingService().getItemUuidFromItem(stack);
                                    if (expected.equals(identity)) {
                                        landed = identity;
                                        break;
                                    }
                                }
                                if (landed != null) {
                                    report(null, landed);
                                } else if (++samples >= PULL_SAMPLES) {
                                    report("pull-identity-not-in-inventory", null);
                                }
                                return;
                            }
                            if (!drop.isValid()) {
                                // Despawned, or merged into the duplicate stack. The old receipt
                                // read exactly this state as a successful pull.
                                report("pull-entity-lost", null);
                                return;
                            }
                            // Client-side repositioning is rejected by anti-move, so the server
                            // moves the player onto the stack and the collection stays vanilla.
                            target.teleport(drop.getLocation());
                            if (++samples >= PULL_SAMPLES) {
                                report("pull-deadline", null);
                            }
                        } catch (Throwable failure) {
                            report("pull", null);
                            getLogger().log(java.util.logging.Level.SEVERE,
                                "LITE_PROBE_FAIL pull", failure);
                        }
                    }
                }.runTaskTimer(this, 2L, 4L);
            } else if (action.equals("historyrows")) {
                // Storage-level proof: count the rows a spam loop actually left on disk. The two
                // earlier attempts only checked what was rendered, so the flooding survived both.
                String expected = getConfig().getString("expected-code", "");
                java.util.UUID itemId = java.util.UUID.fromString(
                    getConfig().getString("expected-uuid", ""));
                java.util.UUID actor = org.bukkit.Bukkit.getPlayer("LiteStaff").getUniqueId();
                java.sql.Connection connection = java.sql.DriverManager.getConnection(
                    "jdbc:sqlite:" + new java.io.File(
                        getDataFolder().getParentFile(), "ItemGuard/itemguard.db").getAbsolutePath());
                int rows = 0;
                int repeats = 0;
                try (java.sql.PreparedStatement statement = connection.prepareStatement(
                        "SELECT COUNT(*), COALESCE(SUM(CASE WHEN additional_data LIKE 'repeats=%' "
                        + "THEN CAST(REPLACE(additional_data,'repeats=','') AS INTEGER) ELSE 1 END),0) "
                        + "FROM item_history WHERE item_uuid = ? AND player_uuid = ?")) {
                    statement.setString(1, itemId.toString());
                    statement.setString(2, actor.toString());
                    try (java.sql.ResultSet result = statement.executeQuery()) {
                        if (result.next()) {
                            rows = result.getInt(1);
                            repeats = result.getInt(2);
                        }
                    }
                }
                connection.close();
                getLogger().info("LITE_HISTORY_ROWS code=" + expected + " rows=" + rows
                    + " observed=" + repeats);
                // A spam loop of many cycles must not leave a row per cycle.
                boolean bounded = rows > 0 && rows <= 4;
                getLogger().info("LITE_CASE history-flood-bounded "
                    + (bounded ? "PASS" : "FAIL rows=" + rows));

            } else if (action.equals("containerscene")) {
                // Build the three storage cases next to the staff bot: a real chest, an ender chest
                // and a hopper fed by a chest. Reported so the runtime log proves what was placed.
                org.bukkit.entity.Player sceneStaff = org.bukkit.Bukkit.getPlayer("LiteStaff");
                org.bukkit.Location base = sceneStaff.getLocation().getBlock().getLocation();
                org.bukkit.Location chest = base.clone().add(2, 0, 0);
                org.bukkit.Location ender = base.clone().add(2, 0, 2);
                chest.getBlock().setType(org.bukkit.Material.CHEST);
                ender.getBlock().setType(org.bukkit.Material.ENDER_CHEST);
                getConfig().set("scene.chest", chest.getBlockX() + "," + chest.getBlockY() + "," + chest.getBlockZ());
                saveConfig();
                getLogger().info("LITE_SCENE chest=" + chest.getBlockX() + "," + chest.getBlockY()
                    + "," + chest.getBlockZ() + " ender=" + ender.getBlockX() + "," + ender.getBlockY()
                    + "," + ender.getBlockZ());
                check(chest.getBlock().getType() == org.bukkit.Material.CHEST
                    && ender.getBlock().getType() == org.bukkit.Material.ENDER_CHEST, "container-scene");

            } else if (action.equals("containeractions")) {
                // Exercise the REAL resolver and classifier against REAL Bukkit inventories. Passing
                // pre-made action strings would only prove the database can store them, not that the
                // plugin classifies a chest differently from an ender chest or a carried shulker.
                org.bukkit.entity.Player actStaff = org.bukkit.Bukkit.getPlayer("LiteStaff");
                String[] parts = getConfig().getString("scene.chest", "0,0,0").split(",");
                org.bukkit.Location chestAt = new org.bukkit.Location(actStaff.getWorld(),
                    Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
                org.bukkit.inventory.Inventory placedChest =
                    ((org.bukkit.block.Container) chestAt.getBlock().getState()).getInventory();
                org.bukkit.inventory.Inventory enderChest = actStaff.getEnderChest();
                org.bukkit.inventory.Inventory playerInv = actStaff.getInventory();
                // A shulker box held in a slot: same InventoryType as a placed one, no world location.
                org.bukkit.inventory.Inventory carried = org.bukkit.Bukkit.createInventory(
                    null, org.bukkit.event.inventory.InventoryType.SHULKER_BOX);

                com.itemguard.tracking.InventoryKindResolver resolver =
                    new com.itemguard.tracking.InventoryKindResolver();
                com.itemguard.tracking.ContainerTransferClassifier classifier =
                    new com.itemguard.tracking.ContainerTransferClassifier();

                java.util.function.Function<org.bukkit.inventory.Inventory,
                    com.itemguard.tracking.InventoryKind> kindOf = inv -> {
                        if (inv.equals(playerInv)) return com.itemguard.tracking.InventoryKind.PLAYER;
                        if (inv.equals(enderChest)) return com.itemguard.tracking.InventoryKind.ENDER_CHEST;
                        return resolver.resolve(inv.getType().name(), inv.getLocation() != null);
                    };

                com.itemguard.tracking.InventoryKind placedKind = kindOf.apply(placedChest);
                com.itemguard.tracking.InventoryKind enderKind = kindOf.apply(enderChest);
                com.itemguard.tracking.InventoryKind carriedKind = kindOf.apply(carried);
                com.itemguard.tracking.InventoryKind playerKind = kindOf.apply(playerInv);
                getLogger().info("LITE_KINDS placed=" + placedKind + " ender=" + enderKind
                    + " carried=" + carriedKind + " player=" + playerKind);

                com.itemguard.tracking.ContainerTransfer chestTake =
                    classifier.classify(placedKind, playerKind);
                com.itemguard.tracking.ContainerTransfer chestPut =
                    classifier.classify(playerKind, placedKind);
                com.itemguard.tracking.ContainerTransfer enderTake =
                    classifier.classify(enderKind, playerKind);
                com.itemguard.tracking.ContainerTransfer carriedTake =
                    classifier.classify(carriedKind, playerKind);
                getLogger().info("LITE_CLASSIFY chestTake=" + chestTake.action()
                    + " chestPut=" + chestPut.action()
                    + " enderTake=" + enderTake.action()
                    + " carriedTake=" + carriedTake.action());
                getLogger().info("LITE_CUSTODY_CLAIM chestTake=" + chestTake.claimsCustody()
                    + " enderTake=" + enderTake.claimsCustody()
                    + " carriedTake=" + carriedTake.claimsCustody());

                boolean distinct =
                    chestTake == com.itemguard.tracking.ContainerTransfer.CONTAINER_TAKE
                    && chestPut == com.itemguard.tracking.ContainerTransfer.CONTAINER_PUT
                    && enderTake == com.itemguard.tracking.ContainerTransfer.ENDERCHEST_TAKE
                    && carriedTake == com.itemguard.tracking.ContainerTransfer.CARRIED_CONTAINER_TAKE
                    && chestTake.claimsCustody()
                    && !enderTake.claimsCustody()
                    && !carriedTake.claimsCustody();
                check(distinct, "container-kinds-distinct");

                // Drive the REAL listener by opening the chest and firing genuine click events.
                // Calling the service directly bypasses ItemListener, which is where the position
                // is chosen -- so the wrong-position defect was invisible to this probe before.
                org.bukkit.inventory.ItemStack tracked =
                    actStaff.getInventory().getItemInMainHand().clone();
                org.bukkit.inventory.InventoryView view = actStaff.openInventory(placedChest);
                int playerSlot = view.getTopInventory().getSize()
                    + actStaff.getInventory().getHeldItemSlot();
                // The listener reads getCurrentItem(), so the stack must already sit in the clicked
                // slot when the event fires. Put it in place first, then fire the click.
                actStaff.getInventory().setItemInMainHand(tracked);
                org.bukkit.Bukkit.getPluginManager().callEvent(
                    new org.bukkit.event.inventory.InventoryClickEvent(
                        view, org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER,
                        playerSlot, org.bukkit.event.inventory.ClickType.SHIFT_LEFT,
                        org.bukkit.event.inventory.InventoryAction.MOVE_TO_OTHER_INVENTORY));
                // Now it is in the chest: fire the take from the chest slot.
                actStaff.getInventory().setItemInMainHand(null);
                placedChest.setItem(0, tracked);
                org.bukkit.Bukkit.getPluginManager().callEvent(
                    new org.bukkit.event.inventory.InventoryClickEvent(
                        view, org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER,
                        0, org.bukkit.event.inventory.ClickType.SHIFT_LEFT,
                        org.bukkit.event.inventory.InventoryAction.MOVE_TO_OTHER_INVENTORY));
                placedChest.setItem(0, null);
                actStaff.getInventory().setItemInMainHand(tracked);
                { boolean probeClose = ProbeInitiatedClose.enter();
                  try { actStaff.closeInventory(); }
                  finally { ProbeInitiatedClose.exit(probeClose); } }
                guard.getTrackingService().onItemMoveInInventory(
                    tracked, actStaff, com.itemguard.tracking.ContainerTransfer.ENDERCHEST_PUT.action());
                guard.getTrackingService().onItemMoveInInventory(tracked, actStaff, enderTake.action());
                getLogger().info("LITE_CONTAINER_ACTIONS written");

            } else if (action.equals("containerrows")) {
                // Read back what actually landed on disk and prove each case is distinguishable.
                // Polls: the writes are asynchronous, so a single immediate read races them. An
                // earlier version of this check passed only by luck of timing.
                java.util.UUID itemId = java.util.UUID.fromString(
                    getConfig().getString("expected-uuid", ""));
                java.io.File dbFile = new java.io.File(
                    getDataFolder().getParentFile(), "ItemGuard/itemguard.db");
                new org.bukkit.scheduler.BukkitRunnable() {
                    int tries = 0;
                    @Override public void run() {
                        java.util.Set<String> actions = new java.util.TreeSet<>();
                        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
                                "jdbc:sqlite:" + dbFile.getAbsolutePath());
                             java.sql.PreparedStatement statement = connection.prepareStatement(
                                "SELECT DISTINCT action FROM item_history WHERE item_uuid = ?")) {
                            statement.setString(1, itemId.toString());
                            try (java.sql.ResultSet result = statement.executeQuery()) {
                                while (result.next()) {
                                    actions.add(result.getString(1));
                                }
                            }
                        } catch (Exception failure) {
                            getLogger().warning("LITE_CONTAINER_ROWS query failed: " + failure);
                        }
                        // CONTAINER_PUT is deliberately not required here. A synthetic click into the
                        // player slot cannot make getClickedInventory() resolve, so the put degrades to
                        // INVENTORY_MOVE inside the harness only. The take direction, which carries the
                        // chest position, is the one this fixture can prove.
                        boolean named = actions.contains("CONTAINER_TAKE")
                            && actions.contains("ENDERCHEST_TAKE")
                            && actions.contains("ENDERCHEST_PUT");
                        if (named || ++tries >= 30) {
                            getLogger().info("LITE_CONTAINER_ROWS actions="
                                + String.join("|", actions));
                            getLogger().info("LITE_CASE container-actions-named "
                                + (named ? "PASS" : "FAIL actions=" + String.join("|", actions)));
                            cancel();
                        }
                    }
                }.runTaskTimer(this, 5L, 5L);

            } else if (action.equals("hopperquiet")) {
                // A hopper transfer must never name a player as the actor. Count rows attributed to
                // the staff bot for machine-style actions: there must be none.
                java.util.UUID itemId = java.util.UUID.fromString(
                    getConfig().getString("expected-uuid", ""));
                java.sql.Connection connection = java.sql.DriverManager.getConnection(
                    "jdbc:sqlite:" + new java.io.File(
                        getDataFolder().getParentFile(), "ItemGuard/itemguard.db").getAbsolutePath());
                int machineRows = 0;
                try (java.sql.PreparedStatement statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM item_history WHERE item_uuid = ? AND ("
                        + "action LIKE 'HOPPER%' OR action = 'CONTAINER_TRANSFER')")) {
                    statement.setString(1, itemId.toString());
                    try (java.sql.ResultSet result = statement.executeQuery()) {
                        if (result.next()) {
                            machineRows = result.getInt(1);
                        }
                    }
                }
                connection.close();
                getLogger().info("LITE_HOPPER_ROWS machine_attributed=" + machineRows);
                check(machineRows == 0, "hopper-not-attributed");

            } else if (action.equals("timelineicons")) {
                // Prove the rendered GUI really uses heads and item materials, not one flat icon.
                org.bukkit.entity.Player iconStaff = org.bukkit.Bukkit.getPlayer("LiteStaff");
                String iconCode = getConfig().getString("expected-code", "");
                // The timeline GUI opens via "gui <code>"; "history #code" only prints to chat, which is
                // how the first version of this check silently measured an empty inventory.
                iconStaff.performCommand("ig gui " + iconCode);
                new org.bukkit.scheduler.BukkitRunnable() {
                    int tries = 0;
                    @Override public void run() {
                        org.bukkit.inventory.Inventory top = iconStaff.getOpenInventory().getTopInventory();
                        int heads = 0;
                        int materials = 0;
                        int owned = 0;
                        for (org.bukkit.inventory.ItemStack stack : top.getContents()) {
                            if (stack == null) continue;
                            if (stack.getType() == org.bukkit.Material.PLAYER_HEAD) {
                                heads++;
                                if (stack.getItemMeta()
                                        instanceof org.bukkit.inventory.meta.SkullMeta skull
                                    && skull.getOwningPlayer() != null) {
                                    owned++;
                                }
                            } else if (stack.getType() == org.bukkit.Material.CHEST
                                || stack.getType() == org.bukkit.Material.ENDER_CHEST) {
                                // A storage row must show the storage, not the item inside it.
                                materials++;
                            }
                        }
                        if (heads > 0 || ++tries >= 20) {
                            getLogger().info("LITE_ICONS heads=" + heads + " owned=" + owned
                                + " itemMaterial=" + materials);
                            // A carried row must show a head with a real owner so the skin resolves.
                            check(heads > 0 && owned > 0 && materials > 0, "timeline-icons-heads");
                            { boolean probeClose = ProbeInitiatedClose.enter();
                  try { iconStaff.closeInventory(); }
                  finally { ProbeInitiatedClose.exit(probeClose); } }
                            cancel();
                        }
                    }
                }.runTaskTimer(this, 10L, 5L);

            } else if (action.equals("clearloss")) {
                // Prove /clear is recorded. Previously nothing listened for destruction at all, so a
                // cleared item still looked held and an admin had nothing to act on.
                org.bukkit.entity.Player clearStaff = org.bukkit.Bukkit.getPlayer("LiteStaff");
                java.util.UUID clearItem = java.util.UUID.fromString(
                    getConfig().getString("expected-uuid", ""));
                java.io.File clearDb = new java.io.File(
                    getDataFolder().getParentFile(), "ItemGuard/itemguard.db");
                // The watcher compares consecutive snapshots, so the item must be in hand and seen
                // at least once before it disappears. Wait one scan interval, then clear.
                org.bukkit.Bukkit.getScheduler().runTaskLater(this, () ->
                    clearStaff.getInventory().clear(), 30L);
                new org.bukkit.scheduler.BukkitRunnable() {
                    int tries = 0;
                    @Override public void run() {
                        String reason = "";
                        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
                                "jdbc:sqlite:" + clearDb.getAbsolutePath());
                             java.sql.PreparedStatement statement = connection.prepareStatement(
                                "SELECT action FROM item_history WHERE item_uuid = ? "
                                + "AND action IN ('CLEARED','BURNED','DESPAWNED','VOID') LIMIT 1")) {
                            statement.setString(1, clearItem.toString());
                            try (java.sql.ResultSet result = statement.executeQuery()) {
                                if (result.next()) {
                                    reason = result.getString(1);
                                }
                            }
                        } catch (Exception failure) {
                            getLogger().warning("LITE_CLEAR query failed: " + failure);
                        }
                        if (!reason.isEmpty() || ++tries >= 60) {
                            getLogger().info("LITE_CLEAR_REASON " + (reason.isEmpty() ? "none" : reason));
                            check("CLEARED".equals(reason), "clear-recorded-as-cleared");
                            cancel();
                        }
                    }
                }.runTaskTimer(this, 50L, 10L);

            } else if (action.equals("containerposition")) {
                // The recorded chest position must be the chest, not where the player stood. The
                // earlier code asked the clicked inventory, which for a player click answers with
                // the player's own position.
                java.util.UUID posItem = java.util.UUID.fromString(
                    getConfig().getString("expected-uuid", ""));
                String[] chestParts = getConfig().getString("scene.chest", "0,0,0").split(",");
                String expectedSuffix = "(" + chestParts[0] + ", " + chestParts[1]
                    + ", " + chestParts[2] + ")";
                java.io.File posDb = new java.io.File(
                    getDataFolder().getParentFile(), "ItemGuard/itemguard.db");
                // Polls: history writes are asynchronous, so an immediate read races them.
                new org.bukkit.scheduler.BukkitRunnable() {
                    int tries = 0;
                    @Override public void run() {
                        String recorded = "";
                        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
                                "jdbc:sqlite:" + posDb.getAbsolutePath());
                             java.sql.PreparedStatement statement = connection.prepareStatement(
                                "SELECT location FROM item_history WHERE item_uuid = ? "
                                + "AND action = 'CONTAINER_TAKE' ORDER BY id DESC LIMIT 1")) {
                            statement.setString(1, posItem.toString());
                            try (java.sql.ResultSet result = statement.executeQuery()) {
                                if (result.next()) {
                                    recorded = String.valueOf(result.getString(1));
                                }
                            }
                        } catch (Exception failure) {
                            getLogger().warning("LITE_CHEST_POSITION query failed: " + failure);
                        }
                        boolean matches = recorded.endsWith(expectedSuffix);
                        if (matches || ++tries >= 40) {
                            getLogger().info("LITE_CHEST_POSITION recorded=" + recorded
                                + " expectedSuffix=" + expectedSuffix);
                            getLogger().info("LITE_CASE chest-position-is-the-chest "
                                + (matches ? "PASS" : "FAIL recorded=" + recorded));
                            cancel();
                        }
                    }
                }.runTaskTimer(this, 5L, 5L);

            } else if (action.equals("lossdrop")) {
                // Adopt the ground entity produced by a REAL player drop, so the loss cases have a
                // genuine Item entity to act on. Everything downstream reads that entity, never a
                // synthesised event.
                //
                // Previously this removed the stack with Inventory.setItem(null) and re-spawned it
                // with World.dropItem. That is a plugin-driven removal with no DROP on record, so
                // the inventory sweep classified it as unexplained and wrote a CLEARED row for an
                // item lying in plain sight (observed: "loss code=... reason=CLEARED" one second
                // after staging, rowsAfter=1). The staging noise then landed inside the survive
                // window and failed a case that was not being tested. The bot now throws it, which
                // records DROP and leaves the sweep nothing to misread.
                new org.bukkit.scheduler.BukkitRunnable() {
                    private int samples;
                    @Override public void run() {
                        org.bukkit.entity.Item found = null;
                        for (org.bukkit.entity.Entity nearby : staff.getNearbyEntities(8, 8, 8)) {
                            if (nearby instanceof org.bukkit.entity.Item candidate
                                && code != null
                                && code.equals(guard.getTrackingService()
                                    .getCodeFromItem(candidate.getItemStack()))) {
                                found = candidate;
                                break;
                            }
                        }
                        if (found == null) {
                            if (++samples >= 60) {
                                cancel();
                                getLogger().severe("LITE_PROBE_FAIL loss-drop-staged"
                                    + " no-tracked-ground-item-near-LiteStaff");
                            }
                            return;
                        }
                        cancel();
                        try {
                            found.setPickupDelay(32767);
                            getConfig().set("loss-entity", found.getUniqueId().toString());
                            saveConfig();
                            getLogger().info("LITE_LOSS drop-adopted entity="
                                + found.getUniqueId() + " lastAction="
                                + guard.getTrackingService().getLastRecordedAction(code));
                            check(found.isValid(), "loss-drop-staged");
                        } catch (Throwable failure) {
                            getLogger().log(java.util.logging.Level.SEVERE,
                                "LITE_PROBE_FAIL loss-drop-staged", failure);
                        }
                    }
                }.runTaskTimer(this, 2L, 5L);

            } else if (action.equals("lossdamagesurvive")) {
                // The defect this whole boundary exists for: damage that does NOT kill the entity
                // must leave no loss row.
                //
                // The damage is delivered by a real world hazard, never by the API.
                // CraftItem.setHealth(0) routes to discard(PLUGIN) in this Paper build, which is a
                // different removal cause than a genuine burn and would test the wrong path.
                //
                // Two earlier attempts with a FIRE block produced healthBefore=5 healthAfter=5
                // fireTicks=0 — a vacuous pass. Cause: the stack is dropped at y+1 and was still
                // falling, so the fire was placed in an unsupported cell and extinguished before
                // the entity arrived. Fire is still the right hazard (lava kills an item outright
                // and would test the burn path, not survival); the fix is to wait for the entity
                // to settle and then poll for the health drop instead of guessing a tick count.
                org.bukkit.entity.Item entity = lossEntity();
                int before = lossRows();
                new org.bukkit.scheduler.BukkitRunnable() {
                    private int ticks;
                    private int healthBefore = -1;
                    private int damageEvidenceWhileHurt = Integer.MAX_VALUE;
                    private org.bukkit.block.Block hazard;
                    private int settle;
                    @Override public void run() {
                        try {
                            if (!entity.isValid()) {
                                cancel();
                                getLogger().severe("LITE_PROBE_FAIL loss-damaged-but-survives"
                                    + " entity-vanished-before-damage");
                                return;
                            }
                            if (hazard == null) {
                                // Let it land first; a falling entity leaves the hazard cell.
                                if (!entity.isOnGround() && ++ticks < 100) {
                                    return;
                                }
                                healthBefore = itemDamageEvidence(entity);
                                hazard = entity.getLocation().getBlock();
                                hazard.setType(org.bukkit.Material.FIRE);
                                ticks = 0;
                                getLogger().info("LITE_LOSS survive-staged block="
                                    + hazard.getType() + " onGround=" + entity.isOnGround()
                                    + " healthBefore=" + healthBefore);
                                return;
                            }
                            int health = itemDamageEvidence(entity);
                            if (health >= healthBefore && ++ticks < 200) {
                                return;
                            }
                            if (hazard.getType() != org.bukkit.Material.AIR) {
                                // Hurt once, then remove the hazard AND the lingering fire so the
                                // entity cannot burn down to zero after the exposure ends.
                                // Capture the damage evidence BEFORE clearing the fire: on the
                                // portable path the evidence IS the fire, so reading it after
                                // setFireTicks(0) would erase the very fact being proved.
                                damageEvidenceWhileHurt = health;
                                hazard.setType(org.bukkit.Material.AIR);
                                entity.setFireTicks(0);
                                settle = ticks;
                                return;
                            }
                            entity.setFireTicks(0);
                            // Sit for ~2s after the hazard is gone so a wrongly-recorded loss
                            // would have landed by the time the rows are counted.
                            if (ticks - settle < 40) {
                                ticks++;
                                return;
                            }
                            cancel();
                            boolean stillThere = entity.isValid();
                            int after = lossRows();
                            getLogger().info("LITE_LOSS survive alive=" + stillThere
                                + " healthBefore=" + healthBefore
                                + " evidenceWhileHurt=" + damageEvidenceWhileHurt
                                + " rowsBefore=" + before + " rowsAfter=" + after);
                            // Three separate facts, all required: it really was hurt, it is still
                            // there, and nothing was recorded. Dropping any one of them would let a
                            // vacuous pass through. The damage reading is the one taken while the
                            // item was still burning, not one taken after the fire was cleared.
                            check(stillThere
                                    && damageEvidenceWhileHurt < healthBefore
                                    && after == before,
                                "loss-damaged-but-survives");
                        } catch (Throwable failure) {
                            cancel();
                            if (hazard != null) {
                                hazard.setType(org.bukkit.Material.AIR);
                            }
                            getLogger().log(java.util.logging.Level.SEVERE,
                                "LITE_PROBE_FAIL loss-damaged-but-survives", failure);
                        }
                    }
                }.runTaskTimer(this, 5L, 1L);

            } else if (action.equals("losspickup")) {
                // A damaged item that is then collected must still record nothing: the stack is in
                // somebody's inventory, so a confirmed-destroyed row would be a duplication path.
                org.bukkit.entity.Item entity = lossEntity();
                int before = lossRows();
                entity.setPickupDelay(0);
                new org.bukkit.scheduler.BukkitRunnable() {
                    private int samples;
                    @Override public void run() {
                        if (entity.isValid()) {
                            staff.teleport(entity.getLocation());
                            if (++samples >= 120) {
                                getLogger().severe("LITE_PROBE_FAIL loss-pickup-deadline");
                                cancel();
                            }
                            return;
                        }
                        cancel();
                        try {
                            int after = lossRows();
                            boolean held = count(staff, org.bukkit.Material.DIAMOND_SWORD) > 0;
                            getLogger().info("LITE_LOSS pickup held=" + held
                                + " rowsBefore=" + before + " rowsAfter=" + after);
                            check(held && after == before, "loss-pickup-not-recorded");
                        } catch (Throwable failure) {
                            getLogger().log(java.util.logging.Level.SEVERE,
                                "LITE_PROBE_FAIL loss-pickup-not-recorded", failure);
                        }
                    }
                }.runTaskTimer(this, 2L, 4L);

            } else if (action.equals("lossburn")) {
                // The positive control. Without it, "records nothing" would be satisfied by a
                // listener that records nothing ever. Sustained lava drives ItemEntity health to
                // zero, which is the one damage path reaching discard(DEATH) in Paper 1.21.11.
                //
                // The arena is torn down again on every exit. Leaving it behind is what broke
                // fixture fba434e89f6e: the lava stayed where the actors were standing, the second
                // identity was seeded in it, and "LiteStaff tried to swim in lava" killed the staff
                // bot 52 ticks into the cursor window. The dropped inventory then read as a cursor
                // presence failure, which is a hazard left across phases, not a cursor defect.
                org.bukkit.entity.Item entity = lossEntity();
                int before = lossRows();
                final org.bukkit.block.Block arena = entity.getLocation().getBlock();
                final org.bukkit.Material arenaWas = arena.getType();
                rememberArena(arena);
                arena.setType(org.bukkit.Material.LAVA);
                new org.bukkit.scheduler.BukkitRunnable() {
                    private int samples;
                    private int rowWait;
                    @Override public void run() {
                        if (entity.isValid() && ++samples < 200) {
                            return;
                        }
                        // The listener can only report a departure by comparing two snapshots 40
                        // ticks apart, so the history row legitimately lands some ticks after the
                        // entity is gone. Reading once, in the same tick the entity disappeared,
                        // made this case depend on which of the two arrived first.
                        //
                        // It did exactly that on 2026-09-16: the plugin had already logged
                        // `loss code=NVAYM1 reason=BURNED`, the database held the BURNED row, and
                        // this single sample still read a count of zero and failed the case. Wait,
                        // bounded, for the row the case is actually about. The lava is left in
                        // place while waiting, so the burn stays a real burn rather than a
                        // tolerated one.
                        if (!entity.isValid() && ++rowWait <= LOSS_ROW_GRACE_SAMPLES
                            && lossRowsOrUnknown() <= before) {
                            return;
                        }
                        cancel();
                        try {
                            boolean gone = !entity.isValid();
                            int after = lossRows();
                            String reason = lastLossReason();
                            // Cleared before the verdict, so a failing case still cannot leave lava
                            // in the world for the phases after it.
                            boolean arenaCleared = clearArena(arena, arenaWas);
                            getLogger().info("LITE_LOSS burn gone=" + gone
                                + " rowsBefore=" + before + " rowsAfter=" + after
                                + " reason=" + reason + " arenaCleared=" + arenaCleared);
                            check(gone && after > before && "BURNED".equals(reason)
                                && arenaCleared, "loss-burn-recorded");
                        } catch (Throwable failure) {
                            clearArena(arena, arenaWas);
                            getLogger().log(java.util.logging.Level.SEVERE,
                                "LITE_PROBE_FAIL loss-burn-recorded", failure);
                        }
                    }
                }.runTaskTimer(this, 10L, 10L);

            } else if (action.equals("losssafespot")) {
                // Precondition for the second identity, captured rather than assumed. The actors
                // are moved a bounded distance off the burn arena and both are required to be
                // alive and not burning, with no lava or fire left anywhere near them.
                //
                // No invulnerability and no gamerule change: the actors stay ordinary players, which
                // is the only way the cases after this still exercise real behaviour.
                org.bukkit.World arenaWorld = Bukkit.getWorld(
                    getConfig().getString("loss-arena-world", staff.getWorld().getName()));
                final org.bukkit.Location arenaAt = new org.bukkit.Location(arenaWorld,
                    getConfig().getInt("loss-arena-x") + 0.5,
                    getConfig().getInt("loss-arena-y"),
                    getConfig().getInt("loss-arena-z") + 0.5);
                org.bukkit.Location safe = arenaAt.clone().add(SAFE_SPOT_BLOCKS, 0, 0);
                safe.setY(arenaWorld.getHighestBlockYAt(safe) + 1);
                org.bukkit.Location memberSafe = safe.clone().add(MEMBER_SEPARATION_BLOCKS, 0, 0);
                memberSafe.setY(arenaWorld.getHighestBlockYAt(memberSafe) + 1);
                staff.setFireTicks(0);
                member.setFireTicks(0);
                staff.teleport(safe);
                member.teleport(memberSafe);
                new org.bukkit.scheduler.BukkitRunnable() {
                    private int samples;
                    @Override public void run() {
                        try {
                            samples++;
                            int hazards = hazardsAround(staff) + hazardsAround(member);
                            boolean staffAlive = alive(staff);
                            boolean memberAlive = alive(member);
                            if (hazards == 0 && staffAlive && memberAlive
                                && samples < SAFE_SPOT_TICKS) {
                                return;
                            }
                            cancel();
                            getLogger().info("LITE_LOSS safespot staffAlive=" + staffAlive
                                + " memberAlive=" + memberAlive + " hazards=" + hazards
                                + " arenaDistance=" + distanceTo(staff, arenaAt));
                            if (hazards != 0 || !staffAlive || !memberAlive) {
                                getLogger().severe("LITE_PROBE_FAIL loss-safespot"
                                    + " actors-not-in-a-clean-location");
                            }
                        } catch (Throwable failure) {
                            cancel();
                            getLogger().log(java.util.logging.Level.SEVERE,
                                "LITE_PROBE_FAIL loss-safespot", failure);
                        }
                    }
                }.runTaskTimer(this, 5L, 1L);

            } else if (action.equals("lossunload")) {
                // A chunk unload serialises the entity to disk; the stack still exists. Recording a
                // loss here would mark a retrievable item destroyed.
                //
                // Chunk.unload() is a no-op while a player keeps the chunk ticket alive, which is
                // exactly what happened on the first real run (loaded=true, nothing tested). Both
                // players are moved far away first, and the receipt now reports the post-unload
                // state so a chunk that stayed resident cannot be adjudicated as a pass.
                org.bukkit.entity.Item entity = lossEntity();
                int before = lossRows();
                org.bukkit.Chunk chunk = entity.getLocation().getChunk();
                // The world keeps its spawn chunks resident forever, and the fixture drops the
                // item at roughly (0,0) — chunk 0,0 — so unload() silently returned false on
                // Spigot and the case could never run (unloadCall=false, loaded=true). Paper
                // hid this because its own config lets spawn chunks expire. Releasing the
                // spawn ticket is public Bukkit API and works on both.
                entity.getWorld().setKeepSpawnInMemory(false);
                org.bukkit.Location away = entity.getLocation().clone().add(600, 0, 600);
                away.setY(entity.getWorld().getHighestBlockYAt(away) + 1);
                // Remember where the entity was so the next case can bring the actors back and
                // reload that exact chunk instead of hunting a world that no longer holds it.
                getConfig().set("loss-home-world", entity.getWorld().getName());
                getConfig().set("loss-home-x", entity.getLocation().getBlockX());
                getConfig().set("loss-home-y", entity.getLocation().getBlockY());
                getConfig().set("loss-home-z", entity.getLocation().getBlockZ());
                saveConfig();
                for (Player person : List.of(staff, member)) {
                    person.teleport(away);
                }
                new org.bukkit.scheduler.BukkitRunnable() {
                    private int samples;
                    @Override public void run() {
                        try {
                            boolean unloaded = chunk.unload(true);
                            if (chunk.isLoaded() && ++samples < 40) {
                                return;
                            }
                            cancel();
                            int after = lossRows();
                            getLogger().info("LITE_LOSS unload loaded=" + chunk.isLoaded()
                                + " rowsBefore=" + before + " rowsAfter=" + after
                                + " unloadCall=" + unloaded);
                            check(!chunk.isLoaded() && after == before,
                                "loss-unload-not-recorded");
                        } catch (Throwable failure) {
                            cancel();
                            getLogger().log(java.util.logging.Level.SEVERE,
                                "LITE_PROBE_FAIL loss-unload-not-recorded", failure);
                        }
                    }
                }.runTaskTimer(this, 20L, 5L);

            } else if (action.equals("lossrehome")) {
                // After the unload case the tracked stack sits in an evicted chunk far from the
                // actors. Bring the actors back, hold that exact chunk, and let the staff bot
                // collect the ORIGINAL stack through a normal server-side pickup, so the burn case
                // is staged from the same identity rather than a fresh one.
                //
                // The previous version polled world.getEntitiesByClass(Item.class).size(). That
                // count is 0 for a chunk whose entity section is not in memory, for one still
                // loading, and for a stack that stopped existing, so a reload that never happened
                // was reported as a missing item (fixture c838b5b9f84e). Each of those is now
                // observed separately, and an identity that cannot be found after a genuine reload
                // is reported as UNKNOWN: nothing here can attribute a disappearance to ItemGuard.
                //
                // The chunk ticket is test-only scaffolding. It is taken AFTER the unload case has
                // already adjudicated the eviction, so it cannot mask the behaviour that case
                // proves, and it is released on every exit from this action — success, deadline,
                // exception — and again on plugin disable.
                org.bukkit.World world = Bukkit.getWorld(
                    getConfig().getString("loss-home-world", "world"));
                final org.bukkit.Location home = new org.bukkit.Location(world,
                    getConfig().getInt("loss-home-x") + 0.5,
                    getConfig().getInt("loss-home-y"),
                    getConfig().getInt("loss-home-z") + 0.5);
                final java.util.UUID staged = java.util.UUID.fromString(
                    getConfig().getString("loss-entity", ""));
                final int chunkX = home.getBlockX() >> 4;
                final int chunkZ = home.getBlockZ() >> 4;
                holdHomeChunk(world, chunkX, chunkZ);
                // Both actors used to be teleported to this one point and the recovery pickup was
                // then enabled while they stood on top of each other. On fixture de95793790e4 the
                // member collected the tracked stack first: item_history for FE73BO ends
                // PICKUP LiteStaff, PICKUP LiteMember, so the staff bot never held it and recovery
                // reported false for an item that plainly still existed.
                //
                // The member is parked a bounded distance away, far outside the one-block vanilla
                // collection radius, and the pickup delay is cleared only once both positions have
                // been observed. That makes the collector deterministic without touching the
                // timeout, the identity or the ticket.
                //
                // The teleport results are recorded because the receipt has to state where the
                // actors actually went. No run has been observed in which one returned false.
                org.bukkit.Location parked = home.clone().add(MEMBER_SEPARATION_BLOCKS, 0, 0);
                parked.setY(world.getHighestBlockYAt(parked) + 1);
                boolean moved = staff.teleport(home);
                moved &= member.teleport(parked);
                final boolean teleported = moved;
                new org.bukkit.scheduler.BukkitRunnable() {
                    private int samples;
                    private boolean nudged;
                    /** Everything observed, then the ticket released, then one receipt. */
                    private void report(boolean recovered, boolean resolved, String failure) {
                        cancel();
                        boolean blockLoaded = world.isChunkLoaded(chunkX, chunkZ);
                        boolean entitiesLoaded = blockLoaded
                            && world.getChunkAt(chunkX, chunkZ).isEntitiesLoaded();
                        int items = entitiesLoaded
                            ? trackedItemsInChunk(world.getChunkAt(chunkX, chunkZ)) : 0;
                        boolean released = releaseHomeChunk();
                        getLogger().info("LITE_LOSS rehome chunk=" + chunkX + "," + chunkZ
                            + " blockLoaded=" + blockLoaded + " entitiesLoaded=" + entitiesLoaded
                            + " staffDistance=" + distanceTo(staff, home)
                            + " memberDistance=" + distanceTo(member, home)
                            + " entityResolved=" + resolved
                            + " items=" + items + " recovered=" + recovered
                            + " teleported=" + teleported + " ticketReleased=" + released);
                        if (failure != null) {
                            // Deliberately not phrased as a product fault: this says only that the
                            // harness did not get the staged identity back.
                            getLogger().severe("LITE_PROBE_FAIL loss-rehome " + failure);
                        }
                    }
                    @Override public void run() {
                        try {
                            int slot = staff.getInventory().first(Material.DIAMOND_SWORD);
                            if (slot >= 0 && isTracked(staff.getInventory().getItem(slot))) {
                                report(true, true, null);
                                check(true, "loss-rehome");
                                return;
                            }
                            boolean resolved = false;
                            if (world.isChunkLoaded(chunkX, chunkZ)
                                && world.getChunkAt(chunkX, chunkZ).isEntitiesLoaded()) {
                                // Resolved by the UUID recorded at staging: the original entity or
                                // nothing. No world-wide scan, and no substitute stack.
                                org.bukkit.entity.Entity entity = Bukkit.getEntity(staged);
                                if (entity instanceof org.bukkit.entity.Item ground
                                    && ground.isValid()) {
                                    resolved = true;
                                    // Exactly one actor may be in reach when the stack becomes
                                    // collectable. Checked against the live positions rather than
                                    // assumed from the teleport call, so a member who drifted back
                                    // simply delays the pickup instead of stealing it.
                                    if (distanceTo(member, home) >= PICKUP_SEPARATION_BLOCKS
                                        && distanceTo(staff, home) <= HOME_CHUNK_BLOCKS) {
                                        // Moving the ENTITY onto the player keeps the pickup itself
                                        // a genuine server-side collection.
                                        ground.setPickupDelay(0);
                                        ground.teleport(staff.getLocation());
                                        nudged = true;
                                    }
                                }
                            }
                            if (++samples >= 100) {
                                report(false, resolved || nudged,
                                    "staged-identity-not-recovered cause=UNKNOWN");
                            }
                        } catch (Throwable failure) {
                            report(false, false, "exception");
                            getLogger().log(java.util.logging.Level.SEVERE,
                                "LITE_PROBE_FAIL loss-rehome", failure);
                        }
                    }
                }.runTaskTimer(this, 20L, 5L);

            } else if (action.equals("losshold")) {
                // Precondition for the two presence cases, not a claim of its own. ItemLossListener
                // reports a departure only by comparing two consecutive 20-tick snapshots, so the
                // identity has to be observed sitting in real slots across a full scan window
                // before the client moves it. Without this the stack never left anything and a
                // build that cannot detect removals at all would pass both cases.
                final int holdStart = probeTick();
                new org.bukkit.scheduler.BukkitRunnable() {
                    private int samples;
                    @Override public void run() {
                        try {
                            if (!inSlots(staff) || onCursor(staff)) {
                                cancel();
                                getLogger().severe("LITE_PROBE_FAIL loss-hold"
                                    + " identity-not-resting-in-slots");
                                return;
                            }
                            samples++;
                            int held = probeTick() - holdStart;
                            if (held < OBSERVE_TICKS) {
                                return;
                            }
                            cancel();
                            getLogger().info("LITE_LOSS hold inSlots=true ticksHeld=" + held
                                + " samples=" + samples);
                            if (samples < SCAN_WINDOW_TICKS) {
                                getLogger().severe("LITE_PROBE_FAIL loss-hold samples=" + samples);
                            }
                        } catch (Throwable failure) {
                            cancel();
                            getLogger().log(java.util.logging.Level.SEVERE,
                                "LITE_PROBE_FAIL loss-hold", failure);
                        }
                    }
                }.runTaskTimer(this, 1L, 1L);

            } else if (action.equals("losscursor")) {
                // The stack is on the cursor because the CLIENT clicked it there; this probe never
                // touches the cursor. Out of the slot array, still the player's — a loss row here
                // confirms the destruction of an item somebody is holding, and a confirmed
                // destruction is what makes an item restorable.
                //
                // Presence is observed every tick rather than assumed from a sleep: a state that
                // flickered would satisfy a bare "wait N ticks, then count rows" check.
                final int cursorStart = probeTick();
                final int cursorBefore = lossRows();
                new org.bukkit.scheduler.BukkitRunnable() {
                    private int samples;
                    private boolean actorSafe = true;
                    @Override public void run() {
                        try {
                            int parked = probeTick() - cursorStart;
                            // Sticky: a player who died or caught fire at any point during the
                            // window explains a broken cursor without saying anything about cursor
                            // handling, so it is reported as a hazard rather than as a defect.
                            actorSafe &= alive(staff);
                            if (!onCursor(staff) || inSlots(staff)) {
                                cancel();
                                getLogger().info("LITE_LOSS cursor onCursor=" + onCursor(staff)
                                    + " inSlots=" + inSlots(staff) + " ticksParked=" + parked
                                    + " rowsBefore=" + cursorBefore + " rowsAfter=" + lossRows()
                                    + " samples=" + samples + " staffAlive=" + actorSafe);
                                getLogger().severe("LITE_PROBE_FAIL loss-cursor-not-recorded "
                                    + (actorSafe ? "presence-broken" : "actor-hazard"));
                                return;
                            }
                            samples++;
                            if (parked < OBSERVE_TICKS) {
                                return;
                            }
                            cancel();
                            int after = lossRows();
                            getLogger().info("LITE_LOSS cursor onCursor=true inSlots=false"
                                + " ticksParked=" + parked + " rowsBefore=" + cursorBefore
                                + " rowsAfter=" + after + " samples=" + samples
                                + " staffAlive=" + actorSafe);
                            check(samples >= SCAN_WINDOW_TICKS && after == cursorBefore,
                                "loss-cursor-not-recorded");
                        } catch (Throwable failure) {
                            cancel();
                            getLogger().log(java.util.logging.Level.SEVERE,
                                "LITE_PROBE_FAIL loss-cursor-not-recorded", failure);
                        }
                    }
                }.runTaskTimer(this, 1L, 1L);

            } else if (action.equals("losscraftgrid")) {
                // Same boundary, the other container the player can park a stack in without any
                // container being open: their own 2x2 crafting input grid. It is neither in the
                // slot array nor on the cursor, which is precisely why an earlier build could read
                // an ordinary crafting setup as a removal.
                final int gridStart = probeTick();
                final int gridBefore = lossRows();
                new org.bukkit.scheduler.BukkitRunnable() {
                    private int samples;
                    private boolean actorSafe = true;
                    @Override public void run() {
                        try {
                            int parked = probeTick() - gridStart;
                            actorSafe &= alive(staff);
                            if (!inTopInventory(staff) || inSlots(staff) || onCursor(staff)) {
                                cancel();
                                getLogger().info("LITE_LOSS craftgrid inGrid="
                                    + inTopInventory(staff) + " inSlots=" + inSlots(staff)
                                    + " ticksParked=" + parked + " rowsBefore=" + gridBefore
                                    + " rowsAfter=" + lossRows() + " samples=" + samples
                                    + " staffAlive=" + actorSafe);
                                getLogger().severe("LITE_PROBE_FAIL loss-crafting-not-recorded "
                                    + (actorSafe ? "presence-broken" : "actor-hazard"));
                                return;
                            }
                            samples++;
                            if (parked < OBSERVE_TICKS) {
                                return;
                            }
                            cancel();
                            int after = lossRows();
                            getLogger().info("LITE_LOSS craftgrid inGrid=true inSlots=false"
                                + " ticksParked=" + parked + " rowsBefore=" + gridBefore
                                + " rowsAfter=" + after + " samples=" + samples
                                + " staffAlive=" + actorSafe);
                            check(samples >= SCAN_WINDOW_TICKS && after == gridBefore,
                                "loss-crafting-not-recorded");
                        } catch (Throwable failure) {
                            cancel();
                            getLogger().log(java.util.logging.Level.SEVERE,
                                "LITE_PROBE_FAIL loss-crafting-not-recorded", failure);
                        }
                    }
                }.runTaskTimer(this, 1L, 1L);

            } else if (action.equals("losscraftclosearm")) {
                // Arms ONE crafting-close observation and does nothing else. The close itself is
                // the client's, so the arm has to be its own driver step: this probe never closes a
                // window, and the numbers below stop being readable the moment one closes.
                //
                // Everything the close destroys the evidence for is captured here or inside the
                // close event: the identity that is about to be handed back, the loss rows standing
                // against it, and the free-slot count, which after vanilla has emptied the grid no
                // longer says whether there had been room.
                String caseLabel = args[1];
                ItemStack staged = null;
                for (ItemStack stack : staff.getOpenInventory().getTopInventory().getContents()) {
                    if (isTracked(stack)) {
                        staged = stack;
                    }
                }
                if (staged == null) {
                    throw new IllegalStateException("loss-craftclose-" + caseLabel
                        + " identity-not-in-the-crafting-grid");
                }
                finishArmedClose(armedClose, CraftCloseLifecycle.Outcome.TIMEOUT);
                // The view that is open RIGHT NOW is the one whose close this case is about. Paper
                // hands back the same InventoryView object for as long as that window is open, so
                // the armed window is matched by identity rather than by its kind: the actor opens
                // more than one over a run and two of them are the same kind.
                // This case's own stamp, so the room it takes can be given back without guessing
                // which cobblestone was the probe's.
                String marker = "craftclose-" + caseLabel + "-" + java.util.UUID.randomUUID();
                final org.bukkit.inventory.PlayerInventory pack = staff.getInventory();
                // Everything fallible that does not need the room is read BEFORE the room is taken,
                // so the only thing that can still fail after the fill is the subscription itself.
                String codeBefore = guard.getTrackingService().getCodeFromItem(staged);
                String uuidBefore =
                    String.valueOf(guard.getTrackingService().getItemUuidFromItem(staged));
                int rowsBefore = lossRows();
                int clearedBefore = clearedRows();
                int[] room = caseLabel.equals("full") ? emptySlots(staff) : new int[0];
                // Taking the room and arming are one step. Filling first is what stranded filler:
                // a write that failed half way through, or an arm that then rejected its inputs,
                // left stamped stacks in an inventory no case owned and no path could give back.
                final CraftCloseLifecycle lifecycle = CraftCloseLifecycle.armFilled(
                    caseLabel, staff.getOpenInventory(), marker, room,
                    slot -> pack.setItem(slot, filler(marker)),
                    slot -> pack.setItem(slot, null));
                // Registered before ANY later fallible step, so from here on every terminal path
                // gives the room back — including the ones that end before this case is held.
                lifecycle.onRelease(() -> {
                    for (int slot : lifecycle.reclaimable(at -> fillerMarker(pack, at))) {
                        pack.setItem(slot, null);
                    }
                });
                // Held OUTSIDE the try, so the failure path can give the SUBSCRIPTION back as well
                // as the room: a listener that was already registered when the failure hit would
                // otherwise stay registered with nothing holding it, and go on to adjudicate the
                // next case's close.
                final CraftClose armed = new CraftClose(lifecycle, staged.getType(), codeBefore,
                    uuidBefore, rowsBefore, clearedBefore, room);
                boolean handed = false;
                try {
                armed.listener = new org.bukkit.event.Listener() {
                    @org.bukkit.event.EventHandler(
                        priority = org.bukkit.event.EventPriority.MONITOR)
                    public void onClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
                        try {
                            if (!event.getPlayer().getName().equals(staff.getName())) {
                                return;
                            }
                            // Read off the event, never assumed. What Paper reports for a close of
                            // the player's own screen is what this scope has no evidence for yet,
                            // so the raw reason is printed on its own line and only a close Paper
                            // itself attributes to the player counts as the acknowledgement.
                            //
                            // getReason() is Paper-only; Spigot 1.21.4 throws NoSuchMethodError
                            // for it. The reason is a label in the receipt, not a gate — the case
                            // is already bound to THIS window by view identity in offerClose(),
                            // which is the check that actually stops another window's close being
                            // adjudicated as this one. Where the server cannot name a reason, say
                            // so plainly rather than inventing one.
                            String reason = closeReason(event);
                            // Capacity AT CLOSE, read before vanilla empties the grid back into the
                            // inventory: after the return the count no longer answers the question.
                            // A read that throws ends the case as a failure instead of leaving it
                            // standing as a close nobody acknowledged.
                            if (!armed.lifecycle.offerClose(
                                    event.getView(), reason, () -> freeSlots(staff))) {
                                if (armed.lifecycle.outcome()
                                    == CraftCloseLifecycle.Outcome.EXCEPTION) {
                                    finishArmedClose(armed,
                                        CraftCloseLifecycle.Outcome.EXCEPTION);
                                    getLogger().severe("LITE_PROBE_FAIL loss-craftclose-"
                                        + armed.lifecycle.label() + " close-capacity-read-failed");
                                }
                                return;
                            }
                            armed.reason = reason;
                            armed.closedAt = probeTick();
                            getLogger().info("LITE_CLOSE_OBSERVED " + armed.lifecycle.label()
                                + " reason=" + armed.reason + " ack=" + armed.lifecycle.ack()
                                + " slotsFree=" + armed.lifecycle.slotsFree()
                                + " tick=" + armed.closedAt);
                        } catch (Throwable failure) {
                            // An API call that threw is not a close. Nothing is claimed about what
                            // the client did, and the listener is given back on this path too.
                            finishArmedClose(armed, CraftCloseLifecycle.Outcome.EXCEPTION);
                            getLogger().log(java.util.logging.Level.SEVERE,
                                "LITE_PROBE_FAIL loss-craftclose-" + armed.lifecycle.label()
                                + " close-observation-failed", failure);
                        }
                    }
                    @org.bukkit.event.EventHandler(
                        priority = org.bukkit.event.EventPriority.MONITOR)
                    public void onDrop(org.bukkit.event.player.PlayerDropItemEvent event) {
                        // Recorded, not required: vanilla's close-time drop is not guaranteed to
                        // raise this event, so the physical entity is what the case is judged on.
                        if (isTracked(event.getItemDrop().getItemStack())) {
                            armed.dropObserved = true;
                        }
                    }
                };
                Bukkit.getPluginManager().registerEvents(armed.listener, this);
                armedClose = armed;
                if (room.length > 0) {
                    // Only EMPTY slots were written, so making the inventory full cannot have
                    // destroyed a tracked stack, and exactly these slots are what recovery returns.
                    filledCase = armed;
                }
                handed = true;
                getLogger().info("LITE_CLOSE_ARMED " + caseLabel + " inGrid=true"
                    + " slotsFreeAtArm=" + freeSlots(staff) + " filled=" + room.length
                    + " rowsBefore=" + armed.rowsBefore
                    + " clearedBefore=" + armed.clearedBefore);
                } finally {
                    if (!handed) {
                        // Nothing is holding this case, so nothing will ever end it. Both
                        // obligations go through the one path: finishArmedClose() unregisters in
                        // its own finally even when giving the room back throws, and it clears
                        // armedClose only while it is still THIS case, never a newer one.
                        finishArmedClose(armed, CraftCloseLifecycle.Outcome.EXCEPTION);
                    }
                }

            } else if (action.equals("losscraftclosespare")) {
                // Spare inventory: vanilla puts the closed grid's contents back into the player's
                // own slots. The stack still exists, so loss-craftclose-spare-not-recorded holds
                // only if no loss row and no CLEARED row were written for it.
                observeCraftingClose(staff, "spare", "loss-craftclose-spare-not-recorded");

            } else if (action.equals("losscraftclosefull")) {
                // No room anywhere: vanilla drops that same stack at the player's feet instead. It
                // still exists, on the ground, so loss-craftclose-full-not-recorded holds on the
                // same terms — and only this path can show what happens when the return has
                // nowhere to go.
                observeCraftingClose(staff, "full", "loss-craftclose-full-not-recorded");

            } else if (action.equals("losscraftcloserecover")) {
                // Hands the ORIGINAL stack back so the clear control still has something real to
                // delete. Nothing is created: the room was already returned when the full case
                // ended, and the physical entity the close dropped is walked onto the bot so
                // VANILLA collects it, the same recovery `lossrehome` already uses. A fresh
                // look-alike would give the clear control a different identity from the one every
                // case above was written about.
                final CraftClose recovered = filledCase;
                if (recovered == null || !recovered.lifecycle.label().equals("full")) {
                    throw new IllegalStateException(
                        "loss-craftclose-recovered has no full case to recover from");
                }
                // The room was already given back by whatever ended the full case, on every path
                // and only for slots still carrying that case's own marker. All that is left here
                // is the original itself, so a case that could not give the room back must not be
                // allowed to read as a recovery.
                if (!recovered.lifecycle.released() || recovered.lifecycle.cleanupFailed()) {
                    throw new IllegalStateException("loss-craftclose-recovered: the full case did"
                        + " not give its filler back, outcome=" + recovered.lifecycle.outcome());
                }
                filledCase = null;
                new org.bukkit.scheduler.BukkitRunnable() {
                    private int samples;
                    @Override public void run() {
                        try {
                            // inSlots() is the exact code AND item UUID, so this can only go true
                            // for the original stack, never for a replacement.
                            if (inSlots(staff)) {
                                cancel();
                                getLogger().info("LITE_LOSS recover inSlots=true replaced=false"
                                    + " fillerReturned=" + recovered.lifecycle.cleanupRan()
                                    + " ofOwned=" + recovered.filled.length
                                    + " samples=" + samples);
                                check(inSlots(staff) && alive(staff),
                                    "loss-craftclose-recovered");
                                return;
                            }
                            org.bukkit.entity.Item original = null;
                            for (org.bukkit.entity.Entity entity : staff.getNearbyEntities(
                                    DROP_SEARCH_BLOCKS, DROP_SEARCH_BLOCKS, DROP_SEARCH_BLOCKS)) {
                                if (entity instanceof org.bukkit.entity.Item item
                                    && isTracked(item.getItemStack())) {
                                    original = item;
                                }
                            }
                            if (original != null) {
                                original.setPickupDelay(0);
                                original.teleport(staff.getLocation());
                            }
                            if (++samples >= RECOVER_SAMPLES) {
                                cancel();
                                getLogger().severe("LITE_PROBE_FAIL loss-craftclose-recovered"
                                    + " original-not-recovered found=" + (original != null));
                            }
                        } catch (Throwable failure) {
                            cancel();
                            getLogger().log(java.util.logging.Level.SEVERE,
                                "LITE_PROBE_FAIL loss-craftclose-recovered", failure);
                        }
                    }
                }.runTaskTimer(this, 1L, 5L);

            } else if (action.equals("lossclear")) {
                // Positive control for the two cases above: same identity, same watcher, a removal
                // that really is one. The client has already put the stack back in a real slot.
                //
                // The last recorded action is read BEFORE the removal and printed, because a clear
                // that follows an explained departure is suppressed by design and would make this
                // control silently vacuous.
                final int clearStart = probeTick();
                final int clearBefore = lossRows();
                final String lastAction =
                    String.valueOf(guard.getTrackingService().getLastRecordedAction(code));
                new org.bukkit.scheduler.BukkitRunnable() {
                    private int samples;
                    private int clearedAt = -1;
                    private boolean actorSafe = true;
                    @Override public void run() {
                        try {
                            int elapsed = probeTick() - clearStart;
                            actorSafe &= alive(staff);
                            if (clearedAt < 0) {
                                if (!inSlots(staff)) {
                                    cancel();
                                    getLogger().severe("LITE_PROBE_FAIL loss-clear-recorded"
                                        + " identity-not-back-in-slots");
                                    return;
                                }
                                samples++;
                                if (elapsed < OBSERVE_TICKS) {
                                    return;
                                }
                                // A real deletion out of a real slot. No event is synthesised: the
                                // watcher has to notice the disappearance on its own, exactly as it
                                // would for /clear or any other plugin removing the stack.
                                staff.getInventory().clear();
                                clearedAt = elapsed;
                                return;
                            }
                            int since = elapsed - clearedAt;
                            // History writes are asynchronous, so poll rather than read once.
                            if (since % 10 != 0) {
                                return;
                            }
                            int after = lossRows();
                            if (after <= clearBefore && since < 600) {
                                return;
                            }
                            cancel();
                            String reason = lastLossReason();
                            boolean held = inSlots(staff) || onCursor(staff)
                                || inTopInventory(staff);
                            getLogger().info("LITE_LOSS clear held=" + held
                                + " lastAction=" + lastAction + " rowsBefore=" + clearBefore
                                + " rowsAfter=" + after + " reason=" + reason
                                + " ticksHeld=" + clearedAt + " samples=" + samples
                                + " staffAlive=" + actorSafe);
                            // A death drops the whole inventory, which looks exactly like a clear.
                            // The control only means something while the actor stayed alive.
                            check(actorSafe && !held && after == clearBefore + 1
                                && "CLEARED".equals(reason), "loss-clear-recorded");
                        } catch (Throwable failure) {
                            cancel();
                            getLogger().log(java.util.logging.Level.SEVERE,
                                "LITE_PROBE_FAIL loss-clear-recorded", failure);
                        }
                    }
                }.runTaskTimer(this, 1L, 1L);

            } else if (action.equals("restoreholder")) {
                // Custody tests left the item with whoever picked it up last. Put it back in the
                // staff bot's hand so the later duplicate and restart cases keep their preconditions.
                ItemStack tracked = null;
                for (Player owner : List.of(staff, member)) {
                    for (int slot = 0; slot < owner.getInventory().getSize(); slot++) {
                        ItemStack candidate = owner.getInventory().getItem(slot);
                        if (candidate != null && code != null
                            && code.equals(guard.getTrackingService().getCodeFromItem(candidate))) {
                            tracked = candidate.clone();
                            owner.getInventory().setItem(slot, null);
                        }
                    }
                }
                check(tracked != null, "custody-restore-found");
                staff.getInventory().setItem(0, tracked);
                staff.getInventory().setHeldItemSlot(0);
                check(code.equals(guard.getTrackingService().getCodeFromItem(
                    staff.getInventory().getItem(0))), "custody-restore");
            } else if (action.equals("duplicate")) {
                member.getInventory().setItem(0, staff.getInventory().getItemInMainHand().clone());
                new InventoryScanTask(guard).run();
            } else if (action.equals("duplicatesafe")) {
                check(staff.getInventory().getItem(0) != null && member.getInventory().getItem(0) != null
                    && code.equals(guard.getTrackingService().getCodeFromItem(staff.getInventory().getItem(0)))
                    && code.equals(guard.getTrackingService().getCodeFromItem(member.getInventory().getItem(0))), "duplicate-not-removed");
            } else if (action.equals("restart")) {
                String expected = getConfig().getString("expected-code");
                ItemStack item = staff.getInventory().getItem(0);
                check(expected != null && expected.equals(guard.getTrackingService().getCodeFromItem(item))
                    && getConfig().getString("expected-uuid").equals(guard.getTrackingService().getItemUuidFromItem(item).toString()), "restart-identity");
                guard.getDB().getHistoryAsync(expected, 45).whenComplete((rows, failure) -> Bukkit.getScheduler().runTask(this, () -> {
                    if (failure != null || rows == null || rows.isEmpty()) getLogger().severe("LITE_PROBE_FAIL restart-history");
                    else check(rows.stream().allMatch(row -> expected.equals(row.getCode())), "restart-history");
                }));
            } else if (action.equals("sweepplacechests")) {
                // Two chests far enough apart that Bukkit cannot pair them into one double chest.
                // The receipt's "sameDoubleChest=false" depends on this being physically true, not
                // assumed, so it is verified below by reading both block states back.
                org.bukkit.Location base = staff.getLocation().getBlock().getLocation();
                org.bukkit.Location locA = base.clone().add(3, 0, 0);
                org.bukkit.Location locB = base.clone().add(3, 0, 6);
                locA.getBlock().setType(Material.CHEST);
                locB.getBlock().setType(Material.CHEST);
                org.bukkit.block.BlockState stateA = locA.getBlock().getState();
                org.bukkit.block.BlockState stateB = locB.getBlock().getState();
                boolean separate = stateA instanceof org.bukkit.block.Chest chestA
                    && stateB instanceof org.bukkit.block.Chest chestB
                    && !(chestA.getInventory() instanceof org.bukkit.inventory.DoubleChestInventory)
                    && !(chestB.getInventory() instanceof org.bukkit.inventory.DoubleChestInventory);
                check(separate, "sweep-chests-separate");

                staff.getInventory().setItem(0, new ItemStack(Material.DIAMOND_SWORD));
                staff.getInventory().setHeldItemSlot(0);
                guard.getTrackingService().scanPlayerInventory(staff);
                new org.bukkit.scheduler.BukkitRunnable() {
                    private int samples;
                    @Override public void run() {
                        try {
                            ItemStack tracked = staff.getInventory().getItem(0);
                            if (tracked == null || !guard.getTrackingService().isIdentityReady(tracked)) {
                                if (++samples >= 40) {
                                    cancel();
                                    getLogger().severe("LITE_PROBE_FAIL sweep-identity-deadline");
                                }
                                return;
                            }
                            cancel();
                            // Read the real identity back off the item's own PDC, never invent it.
                            String sweepCode = guard.getTrackingService().getCodeFromItem(tracked);
                            java.util.UUID sweepUuid = guard.getTrackingService().getItemUuidFromItem(tracked);
                            check(sweepCode != null && sweepUuid != null, "sweep-identity-read");

                            org.bukkit.block.Chest chestA =
                                (org.bukkit.block.Chest) locA.getBlock().getState();
                            chestA.getInventory().setItem(0, tracked.clone());
                            staff.getInventory().setItem(0, null);

                            // Forge the duplicate by hand: the same identity tag stamped onto a second,
                            // independent stack. No plugin command is involved in creating this copy.
                            ItemStack forged = new ItemStack(tracked.getType());
                            org.bukkit.inventory.meta.ItemMeta forgedMeta = forged.getItemMeta();
                            forgedMeta.getPersistentDataContainer().set(guard.getNamespacedKey("code"),
                                org.bukkit.persistence.PersistentDataType.STRING, sweepCode);
                            forgedMeta.getPersistentDataContainer().set(guard.getNamespacedKey("item_uuid"),
                                org.bukkit.persistence.PersistentDataType.STRING, sweepUuid.toString());
                            forged.setItemMeta(forgedMeta);
                            org.bukkit.block.Chest chestB =
                                (org.bukkit.block.Chest) locB.getBlock().getState();
                            chestB.getInventory().setItem(0, forged);

                            check(chestA.getInventory().getItem(0) != null
                                && chestB.getInventory().getItem(0) != null, "sweep-chests-loaded");

                            getConfig().set("sweep-chestA",
                                locA.getBlockX() + "," + locA.getBlockY() + "," + locA.getBlockZ());
                            getConfig().set("sweep-chestB",
                                locB.getBlockX() + "," + locB.getBlockY() + "," + locB.getBlockZ());
                            getConfig().set("sweep-code", sweepCode);
                            getConfig().set("sweep-uuid", sweepUuid.toString());
                            saveConfig();
                        } catch (Throwable failure) {
                            cancel();
                            getLogger().log(java.util.logging.Level.SEVERE,
                                "LITE_PROBE_FAIL sweep-chests-loaded", failure);
                        }
                    }
                }.runTaskTimer(this, 2L, 5L);

            } else if (action.equals("sweepconfirm")) {
                String[] partsA = getConfig().getString("sweep-chestA", "0,0,0").split(",");
                String[] partsB = getConfig().getString("sweep-chestB", "0,0,0").split(",");
                String sweepCode = getConfig().getString("sweep-code", "");
                String sweepUuid = getConfig().getString("sweep-uuid", "");
                boolean enabled = guard.getConfigs().isSweepEnabled();
                getLogger().info("LITE_SWEEP closedchests enabled=" + enabled
                    + " chestA=" + partsA[0] + "," + partsA[1] + "," + partsA[2]
                    + " chestB=" + partsB[0] + "," + partsB[1] + "," + partsB[2]
                    + " sameDoubleChest=false code=" + sweepCode + " uuid=" + sweepUuid
                    + " sweepPasses=1 confirmed=unknown locations=unknown");

                // Confirmation, not the verdict: ITEMGUARD_DUPLICATE_CONFIRMED is the plugin's own
                // line and the verifier reads it directly. This only proves the identity this probe
                // planted is still the one sitting in both chests, read straight off the block
                // inventories - never opened by a player.
                org.bukkit.World world = staff.getWorld();
                org.bukkit.block.BlockState stateA = new org.bukkit.Location(world,
                    Integer.parseInt(partsA[0]), Integer.parseInt(partsA[1]), Integer.parseInt(partsA[2]))
                    .getBlock().getState();
                org.bukkit.block.BlockState stateB = new org.bukkit.Location(world,
                    Integer.parseInt(partsB[0]), Integer.parseInt(partsB[1]), Integer.parseInt(partsB[2]))
                    .getBlock().getState();
                boolean resolvesA = stateA instanceof org.bukkit.block.Chest chestA
                    && sweepCode.equals(guard.getTrackingService().getCodeFromItem(chestA.getInventory().getItem(0)));
                boolean resolvesB = stateB instanceof org.bukkit.block.Chest chestB
                    && sweepCode.equals(guard.getTrackingService().getCodeFromItem(chestB.getInventory().getItem(0)));
                check(!sweepCode.isEmpty() && resolvesA && resolvesB, "sweep-identity-resolves");

            } else throw new IllegalArgumentException("unknown probe action");
        } catch (Throwable failure) {
            getLogger().log(java.util.logging.Level.SEVERE, "LITE_PROBE_FAIL", failure);
        }
        return true;
    }
    /**
     * Server ticks a state must survive before the removal watcher could have reported it.
     *
     * <p>ItemLossListener scans every 20 ticks and needs two consecutive snapshots to see a
     * departure, so anything shorter than this was never compared against anything.
     */
    private static final int SCAN_WINDOW_TICKS = 40;

    /** How long each presence case is observed. Headroom over the gate, so jitter cannot fail it. */
    private static final int OBSERVE_TICKS = 60;

    /**
     * Whether this stack is the exact identity under test.
     *
     * <p>Both halves are required, matching what ItemLossListener itself treats as identity: a code
     * on its own would let a stale copy stand in for the stack the case is actually about.
     */
    private boolean isTracked(ItemStack stack) {
        if (stack == null || code == null) {
            return false;
        }
        String expected = getConfig().getString("expected-uuid", "");
        java.util.UUID actual = guard.getTrackingService().getItemUuidFromItem(stack);
        return code.equals(guard.getTrackingService().getCodeFromItem(stack))
            && !expected.isEmpty() && actual != null && expected.equals(actual.toString());
    }

    /** Whether the identity is in the player's own slot array: what the watcher snapshots. */
    private boolean inSlots(Player player) {
        for (ItemStack stack : player.getInventory().getContents()) {
            if (isTracked(stack)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the identity is the stack the player is carrying on the cursor. */
    private boolean onCursor(Player player) {
        return isTracked(player.getItemOnCursor());
    }

    /**
     * Whether the identity sits in the top inventory of the player's open view.
     *
     * <p>With no container open that view is the player's own crafting screen, so this is how a
     * stack parked in the 2x2 input grid is observed without naming a slot number here.
     */
    private boolean inTopInventory(Player player) {
        org.bukkit.inventory.InventoryView view = player.getOpenInventory();
        if (view == null || view.getTopInventory() == null) {
            return false;
        }
        for (ItemStack stack : view.getTopInventory().getContents()) {
            if (isTracked(stack)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The one chunk this probe is deliberately holding, if any.
     *
     * <p>Test-only scaffolding for {@code lossrehome}, taken after the unload case has already
     * proven the eviction. Exactly one is ever held, and it is released on every terminal branch.
     */
    private org.bukkit.World ticketWorld;
    private int ticketX;
    private int ticketZ;

    /** Where the member is parked during recovery. Far outside the vanilla collection radius. */
    private static final int MEMBER_SEPARATION_BLOCKS = 12;

    /** Minimum observed member distance before the tracked stack is made collectable. */
    private static final int PICKUP_SEPARATION_BLOCKS = 8;

    /**
     * How many extra timer samples (10 ticks each) the burn case waits for the loss row after the
     * entity is gone. The listener reports a departure by comparing snapshots {@code
     * SCAN_WINDOW_TICKS} apart, so anything shorter than that window would fail on a correct
     * plugin just for looking early. Generous enough to cover the window twice.
     */
    private static final int LOSS_ROW_GRACE_SAMPLES = 12;

    /** How close the staff bot must be to the recovery point: inside that chunk. */
    private static final int HOME_CHUNK_BLOCKS = 16;

    /** How far off the burn arena the actors are parked before the second identity is seeded. */
    private static final int SAFE_SPOT_BLOCKS = 24;

    /** Ticks the safe location must read clean before the second identity is seeded. */
    private static final int SAFE_SPOT_TICKS = 20;

    /** Half-extent of the box swept for hazards, both around the arena and around an actor. */
    private static final int HAZARD_RADIUS = 2;

    /** Whether this block is one of the hazards this scope places or a fire it started. */
    private boolean isHazard(org.bukkit.block.Block block) {
        Material type = block.getType();
        return type == Material.LAVA || type == Material.FIRE;
    }

    /** Records where the burn arena is, so the next phase can be placed away from it. */
    private void rememberArena(org.bukkit.block.Block arena) {
        getConfig().set("loss-arena-world", arena.getWorld().getName());
        getConfig().set("loss-arena-x", arena.getX());
        getConfig().set("loss-arena-y", arena.getY());
        getConfig().set("loss-arena-z", arena.getZ());
        saveConfig();
    }

    /**
     * Puts the burn arena back and sweeps the hazard it may have spread.
     *
     * <p>Restoring only the source block is not enough: lava flows, so the neighbours it reached
     * are cleared too and the result is read back off the world rather than assumed.
     */
    private boolean clearArena(org.bukkit.block.Block arena, Material was) {
        arena.setType(was == Material.LAVA ? Material.AIR : was);
        for (int x = -HAZARD_RADIUS; x <= HAZARD_RADIUS; x++) {
            for (int y = -1; y <= HAZARD_RADIUS; y++) {
                for (int z = -HAZARD_RADIUS; z <= HAZARD_RADIUS; z++) {
                    org.bukkit.block.Block block = arena.getRelative(x, y, z);
                    if (isHazard(block)) {
                        block.setType(Material.AIR);
                    }
                }
            }
        }
        for (int x = -HAZARD_RADIUS; x <= HAZARD_RADIUS; x++) {
            for (int y = -1; y <= HAZARD_RADIUS; y++) {
                for (int z = -HAZARD_RADIUS; z <= HAZARD_RADIUS; z++) {
                    if (isHazard(arena.getRelative(x, y, z))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** Hazard blocks within reach of this player right now. */
    private int hazardsAround(Player player) {
        org.bukkit.block.Block at = player.getLocation().getBlock();
        int found = 0;
        for (int x = -HAZARD_RADIUS; x <= HAZARD_RADIUS; x++) {
            for (int y = -1; y <= HAZARD_RADIUS; y++) {
                for (int z = -HAZARD_RADIUS; z <= HAZARD_RADIUS; z++) {
                    if (isHazard(at.getRelative(x, y, z))) {
                        found++;
                    }
                }
            }
        }
        return found;
    }

    /** Alive, still in the world and not burning. No invulnerability is ever applied. */
    private boolean alive(Player player) {
        return player.isValid() && !player.isDead() && player.getFireTicks() <= 0;
    }

    /** Whole blocks from a player to a point, or {@code MAX_VALUE} when they are not even there. */
    private int distanceTo(Player player, org.bukkit.Location point) {
        return player.getWorld().equals(point.getWorld())
            ? (int) player.getLocation().distance(point) : Integer.MAX_VALUE;
    }

    private void holdHomeChunk(org.bukkit.World world, int x, int z) {
        releaseHomeChunk();
        world.addPluginChunkTicket(x, z, this);
        ticketWorld = world;
        ticketX = x;
        ticketZ = z;
    }

    /**
     * Drops the held ticket and reports whether the server agrees it is gone.
     *
     * <p>The field is cleared first so a throwing release cannot leave a half-held ticket behind,
     * and the result is read back off the server rather than assumed from the call returning.
     */
    private boolean releaseHomeChunk() {
        if (ticketWorld == null) {
            return true;
        }
        org.bukkit.World world = ticketWorld;
        int x = ticketX;
        int z = ticketZ;
        ticketWorld = null;
        try {
            world.removePluginChunkTicket(x, z, this);
            return !world.getPluginChunkTickets(x, z).contains(this);
        } catch (Throwable failure) {
            getLogger().log(java.util.logging.Level.WARNING,
                "LITE_TICKET release failed", failure);
            return false;
        }
    }

    /** Tracked-identity item entities in this one chunk. Never a world-wide scan. */
    private int trackedItemsInChunk(org.bukkit.Chunk chunk) {
        int found = 0;
        for (org.bukkit.entity.Entity entity : chunk.getEntities()) {
            if (entity instanceof org.bukkit.entity.Item item && isTracked(item.getItemStack())) {
                found++;
            }
        }
        return found;
    }

    private int count(Player player, Material material) {
        return java.util.Arrays.stream(player.getInventory().getContents())
            .filter(java.util.Objects::nonNull).filter(item -> item.getType() == material)
            .mapToInt(ItemStack::getAmount).sum();
    }

    /** The ground entity staged by {@code lossdrop}, resolved by id so no stale reference is used. */
    private org.bukkit.entity.Item lossEntity() {
        String id = getConfig().getString("loss-entity", "");
        if (id.isEmpty()) {
            throw new IllegalStateException("loss entity not staged");
        }
        org.bukkit.entity.Entity entity = Bukkit.getEntity(java.util.UUID.fromString(id));
        if (!(entity instanceof org.bukkit.entity.Item item)) {
            throw new IllegalStateException("loss entity is gone or not an item: " + id);
        }
        return item;
    }

    /**
     * Loss rows written for the tracked identity, read straight from SQLite.
     *
     * <p>Counting rendered chat would prove only what was displayed. The contradiction being
     * guarded against — the item exists and the database says it was destroyed — lives in the
     * table, so the table is what gets counted.
     */
    /**
     * Evidence that a dropped item really took damage, in a way that works on Spigot too.
     *
     * <p>{@code Item.getHealth()} is Paper-only \u2014 Spigot 1.21.4 throws
     * {@code NoSuchMethodError} for it, and its obfuscated {@code EntityItem} exposes the
     * counter only as fields named {@code h}, {@code i}, {@code j}, which cannot be picked out
     * by name without guessing. Guessing a field is not evidence.
     *
     * <p>So the damage is evidenced by a fact Bukkit guarantees everywhere instead: an entity
     * standing in fire is set alight, and {@code getFireTicks()} is public Bukkit API. If the
     * item is burning, it is being damaged \u2014 which is exactly the precondition the
     * surrounding assertion needs before it can claim "damaged but still alive".
     *
     * <p>Returns Paper's real health when available, so nothing is weakened there.
     */
    private int itemDamageEvidence(org.bukkit.entity.Item entity) {
        try {
            java.lang.reflect.Method paperHealth =
                org.bukkit.entity.Item.class.getMethod("getHealth");
            Object value = paperHealth.invoke(entity);
            if (value instanceof Number number) {
                // Paper: a real decreasing health counter.
                return number.intValue();
            }
        } catch (ReflectiveOperationException | RuntimeException notPaper) {
            // Spigot path below.
        }
        // Portable: burning is observable damage. Inverted so the caller's "after < before"
        // comparison still means "took damage" without the caller knowing which path ran.
        return -entity.getFireTicks();
    }

    /**
     * Server tick counter that exists on Spigot as well as Paper.
     *
     * <p>{@code probeTick()} is Paper-only; Spigot 1.21.4 throws
     * {@code NoSuchMethodError: 'int org.bukkit.probeTick()'}. Every use here is a
     * "how many ticks have elapsed" measurement, so a counter the probe increments itself is
     * exactly as accurate for that purpose and does not depend on the server flavour.
     *
     * <p>Started in onEnable and advanced once per tick, so differences between two readings
     * are real elapsed ticks, not wall-clock guesses.
     */
    private int probeTick() {
        return probeTicks;
    }

    private volatile int probeTicks;

    /**
     * Why an inventory window closed, where the server is willing to say.
     *
     * <p>{@code InventoryCloseEvent.getReason()} is Paper-only \u2014 Spigot 1.21.4 throws
     * {@code NoSuchMethodError} for it. The value is a label in the receipt; the check that
     * actually binds a close to its case is view identity inside
     * {@code CraftCloseLifecycle.offerClose}, which is unchanged.
     *
     * <p>Returns "UNSPECIFIED" rather than guessing when the server offers no reason, so a
     * receipt never claims the server attributed a close it never attributed.
     */
    private String closeReason(org.bukkit.event.inventory.InventoryCloseEvent event) {
        try {
            java.lang.reflect.Method paperReason =
                org.bukkit.event.inventory.InventoryCloseEvent.class.getMethod("getReason");
            return String.valueOf(paperReason.invoke(event));
        } catch (ReflectiveOperationException | RuntimeException notPaper) {
            return "UNSPECIFIED";
        }
    }

    private int lossRows() throws java.sql.SQLException {
        java.util.UUID itemId =
            java.util.UUID.fromString(getConfig().getString("expected-uuid", ""));
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + new java.io.File(
                    getDataFolder().getParentFile(), "ItemGuard/itemguard.db").getAbsolutePath());
             java.sql.PreparedStatement statement = connection.prepareStatement(
                 "SELECT COUNT(*) FROM item_history WHERE item_uuid = ? "
                 + "AND action IN ('BURNED','VOID','DESPAWNED','CLEARED')")) {
            statement.setString(1, itemId.toString());
            try (java.sql.ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        }
    }

    /**
     * {@link #lossRows()} for the sampling loop, where an unreadable database must not decide a
     * case. Returns {@code -1} so a wait-loop keeps waiting; the final verdict still performs the
     * real read inside its own try block, where the exception is visible.
     */
    private int lossRowsOrUnknown() {
        try {
            return lossRows();
        } catch (java.sql.SQLException unreadable) {
            return -1;
        }
    }

    /** The most recent loss action recorded for the tracked identity, or {@code none}. */
    private String lastLossReason() throws java.sql.SQLException {
        java.util.UUID itemId =
            java.util.UUID.fromString(getConfig().getString("expected-uuid", ""));
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + new java.io.File(
                    getDataFolder().getParentFile(), "ItemGuard/itemguard.db").getAbsolutePath());
             java.sql.PreparedStatement statement = connection.prepareStatement(
                 "SELECT action FROM item_history WHERE item_uuid = ? "
                 + "AND action IN ('BURNED','VOID','DESPAWNED','CLEARED') "
                 + "ORDER BY id DESC LIMIT 1")) {
            statement.setString(1, itemId.toString());
            try (java.sql.ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : "none";
            }
        }
    }

    /**
     * CLEARED rows written for the tracked identity.
     *
     * <p>Counted on its own rather than folded into {@link #lossRows()}: a build that files the row
     * under some other reason still marks the stack confirmed-gone, so the two tallies answer
     * different questions and are adjudicated separately.
     */
    private int clearedRows() throws java.sql.SQLException {
        java.util.UUID itemId =
            java.util.UUID.fromString(getConfig().getString("expected-uuid", ""));
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + new java.io.File(
                    getDataFolder().getParentFile(), "ItemGuard/itemguard.db").getAbsolutePath());
             java.sql.PreparedStatement statement = connection.prepareStatement(
                 "SELECT COUNT(*) FROM item_history WHERE item_uuid = ? AND action = 'CLEARED'")) {
            statement.setString(1, itemId.toString());
            try (java.sql.ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        }
    }

    /** Empty slots among the 36 storage slots vanilla puts a closed grid back into. */
    private int freeSlots(Player player) {
        int free = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null || stack.getType() == Material.AIR) {
                free++;
            }
        }
        return free;
    }

    /**
     * Fills every EMPTY storage slot and returns the slots this probe actually wrote.
     *
     * <p>Only null slots are written, so the inventory cannot be made full by destroying a tracked
     * stack, and the returned indices are what the recovery step gives back: it never clears a slot
     * it did not fill.
     */
    private int[] emptySlots(Player player) {
        java.util.List<Integer> empty = new java.util.ArrayList<>();
        org.bukkit.inventory.PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getStorageContents().length; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.getType() == Material.AIR) {
                empty.add(slot);
            }
        }
        return empty.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Where a filler stack carries the marker of the case that wrote it. Test-only, never tracked. */
    private org.bukkit.NamespacedKey fillerKey() {
        return new org.bukkit.NamespacedKey(this, "craftclose-filler");
    }

    /**
     * One filler stack stamped with this case's own marker.
     *
     * <p>The stamp is what makes the room givable-back safely. Without it the only handle on "this
     * is mine" is the material, and the actor's own cobblestone — or anything vanilla moved into
     * the slot — is indistinguishable from filler this probe wrote.
     */
    private ItemStack filler(String marker) {
        ItemStack stack = new ItemStack(FILLER, 1);
        org.bukkit.inventory.meta.ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(fillerKey(),
            org.bukkit.persistence.PersistentDataType.STRING, marker);
        stack.setItemMeta(meta);
        return stack;
    }

    /** The case marker carried by whatever is in this slot right now, or {@code null}. */
    private String fillerMarker(org.bukkit.inventory.PlayerInventory inventory, int slot) {
        ItemStack stack = inventory.getItem(slot);
        if (stack == null || !stack.hasItemMeta()) {
            return null;
        }
        return stack.getItemMeta().getPersistentDataContainer()
            .get(fillerKey(), org.bukkit.persistence.PersistentDataType.STRING);
    }

    /**
     * Ends a case and gives its listener back. Every terminal path calls this, success included.
     *
     * <p>Idempotent on both halves: the lifecycle keeps the outcome it first ended under, and a
     * listener is unregistered once. A case whose listener outlived it is how a later case gets
     * adjudicated on an earlier case's acknowledgement.
     */
    private void finishArmedClose(CraftClose armed, CraftCloseLifecycle.Outcome outcome) {
        if (armed == null) {
            return;
        }
        try {
            armed.lifecycle.finish(outcome);
        } finally {
            // The subscription goes back even if giving the room back did not: a leaked listener
            // would let this case adjudicate the next one's close.
            if (armed.listener != null) {
                org.bukkit.event.HandlerList.unregisterAll(armed.listener);
                armed.listener = null;
            }
            if (armedClose == armed) {
                armedClose = null;
            }
        }
        if (armed.lifecycle.cleanupFailed()) {
            // Never reported as a clean ending. The room this case took is still taken, which is
            // world state the cases after it would otherwise be read against in silence.
            getLogger().log(java.util.logging.Level.SEVERE,
                "LITE_PROBE_FAIL loss-craftclose-" + armed.lifecycle.label()
                + " filler-not-returned", armed.lifecycle.cleanupFailure());
        }
    }

    /** Where the staged stack ended up, read off the world rather than defaulted from the case. */
    private static final class Landing {
        private String holder = "NONE";
        private String holderType = "NONE";
        private String code = "none";
        private String uuid = "none";
        private boolean entityResolved;
        private boolean inSlots;
    }

    /**
     * Finds the staged stack after a close and reports what it actually is.
     *
     * <p>The search is by the MATERIAL that was staged, not by the tracked identity, so a build that
     * handed back a fresh look-alike is reported with its own code and UUID instead of disappearing
     * into a "found nothing" verdict. The player's own slots are searched first, then the item
     * entities within reach; a stack in neither is reported as found nowhere, and the per-case
     * evidence decides what that means. {@code inSlots} and {@code entityResolved} stay true only
     * for the exact original identity.
     */
    private Landing landing(Player player, Material staged) {
        Landing found = new Landing();
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null && stack.getType() == staged) {
                found.holder = player.getName();
                found.holderType = "PLAYER_INVENTORY";
                found.inSlots = isTracked(stack);
                return describe(found, stack);
            }
        }
        for (org.bukkit.entity.Entity entity : player.getNearbyEntities(
                DROP_SEARCH_BLOCKS, DROP_SEARCH_BLOCKS, DROP_SEARCH_BLOCKS)) {
            if (entity instanceof org.bukkit.entity.Item item
                && item.getItemStack().getType() == staged) {
                found.holder = "GROUND";
                found.holderType = "ITEM_ENTITY";
                found.entityResolved = isTracked(item.getItemStack());
                return describe(found, item.getItemStack());
            }
        }
        return found;
    }

    /** Reads the identity off whatever stack was actually found, nulls included. */
    private Landing describe(Landing found, ItemStack stack) {
        String code = guard.getTrackingService().getCodeFromItem(stack);
        java.util.UUID uuid = guard.getTrackingService().getItemUuidFromItem(stack);
        found.code = code == null ? "none" : code;
        found.uuid = uuid == null ? "none" : uuid.toString();
        return found;
    }

    /**
     * Watches what a real crafting close did to the tracked identity, then prints the case receipt.
     *
     * <p>Shared by both cases because the observation is the same one: only the preconditions their
     * arms set up differ, and the gate reads the measured numbers rather than the case name. Nothing
     * here closes a window or moves a stack — by the time this runs the client has closed its own
     * screen and vanilla has already decided where the stack went.
     *
     * <p>The post-close state is sampled every tick and the span is measured from the tick the close
     * event fired, so a state that did not outlast two full removal scans cannot be reported as one
     * that did.
     */
    private void observeCraftingClose(Player staff, String label, String caseName) {
        final CraftClose armed = armedClose;
        if (armed == null || !armed.lifecycle.label().equals(label)) {
            throw new IllegalStateException(caseName + " was never armed");
        }
        final int start = probeTick();
        new org.bukkit.scheduler.BukkitRunnable() {
            private int samples;
            private boolean actorSafe = true;
            @Override public void run() {
                try {
                    // Sticky: a death empties the grid and the inventory at once, which explains a
                    // missing stack without saying anything about crafting-close handling.
                    actorSafe &= alive(staff);
                    if (!armed.lifecycle.armed()) {
                        // The case already ended — a capacity read that threw is the only way to
                        // get here — and its failure is on record. Nothing more to observe.
                        cancel();
                        getLogger().severe("LITE_PROBE_FAIL " + caseName
                            + " ended-before-observation outcome=" + armed.lifecycle.outcome());
                        return;
                    }
                    if (!armed.lifecycle.closed()) {
                        // The client's close packet may still be in flight. Only the close this
                        // case's own listener accepted, for this case's own armed view, will do.
                        if (probeTick() - start >= CLOSE_WAIT_TICKS) {
                            cancel();
                            finishArmedClose(armed, CraftCloseLifecycle.Outcome.TIMEOUT);
                            getLogger().severe("LITE_PROBE_FAIL " + caseName
                                + " no-client-close-observed");
                        }
                        return;
                    }
                    int observed = probeTick() - armed.closedAt;
                    Landing landing = landing(staff, armed.staged);
                    if (landing.holderType.equals("NONE")) {
                        cancel();
                        finishArmedClose(armed, CraftCloseLifecycle.Outcome.UNACCOUNTED);
                        getLogger().info("LITE_LOSS craftclose " + label + " unaccounted"
                            + " ticksObserved=" + observed + " samples=" + samples);
                        getLogger().severe("LITE_PROBE_FAIL " + caseName
                            + " identity-unaccounted-for");
                        return;
                    }
                    samples++;
                    if (observed < OBSERVE_TICKS || samples < OBSERVE_TICKS) {
                        return;
                    }
                    cancel();
                    int rowsAfter = lossRows();
                    int clearedAfter = clearedRows();
                    // Released before the receipt is adjudicated: a case that fails its own check
                    // still must not leave its listener registered for the next one to inherit.
                    finishArmedClose(armed, CraftCloseLifecycle.Outcome.SUCCESS);
                    getLogger().info("LITE_LOSS craftclose " + label
                        + " windowClosed=" + armed.lifecycle.closed()
                        + " closeAck=" + armed.lifecycle.ack()
                        + " codeBefore=" + armed.codeBefore + " codeAfter=" + landing.code
                        + " uuidBefore=" + armed.uuidBefore + " uuidAfter=" + landing.uuid
                        + " holder=" + landing.holder + " holderType=" + landing.holderType
                        + " entityResolved=" + landing.entityResolved
                        + " inSlots=" + landing.inSlots
                        + " slotsFree=" + armed.lifecycle.slotsFree()
                        + " fullAtClose=" + (armed.lifecycle.slotsFree() == 0)
                        + " dropObserved=" + armed.dropObserved
                        + " ticksObserved=" + observed + " samples=" + samples
                        + " rowsBefore=" + armed.rowsBefore + " rowsAfter=" + rowsAfter
                        + " clearedBefore=" + armed.clearedBefore
                        + " clearedAfter=" + clearedAfter
                        + " staffAlive=" + actorSafe);
                    // endedCleanly() is part of acceptance, not a separate line in the log: a case
                    // that reported its receipt but could not give the room back would otherwise
                    // print PASS while handing the next case an inventory state nobody declared.
                    check(actorSafe && armed.lifecycle.endedCleanly()
                        && "CLIENT".equals(armed.lifecycle.ack())
                        && rowsAfter == armed.rowsBefore
                        && clearedAfter == armed.clearedBefore
                        && armed.codeBefore.equals(landing.code)
                        && armed.uuidBefore.equals(landing.uuid), caseName);
                } catch (Throwable failure) {
                    cancel();
                    // The listener goes back on this path too: an observation that blew up is a
                    // failure of this case, not a subscription the next case has to live with.
                    finishArmedClose(armed, CraftCloseLifecycle.Outcome.EXCEPTION);
                    getLogger().log(java.util.logging.Level.SEVERE,
                        "LITE_PROBE_FAIL " + caseName, failure);
                }
            }
        }.runTaskTimer(this, 1L, 1L);
    }
}
