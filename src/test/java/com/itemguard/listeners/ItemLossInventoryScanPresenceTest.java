package com.itemguard.listeners;

import com.itemguard.ItemGuard;
import com.itemguard.dupe.ScanEpochGenerator;
import org.bukkit.inventory.CraftingInventory;
import com.itemguard.restore.LossReason;
import com.itemguard.services.ItemTrackingService;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Absence from {@code getContents()} is not proof that a tracked item left the player.
 *
 * <p>Observed in source: {@code ItemLossListener.snapshot} reads only
 * {@code player.getInventory().getContents()}, and {@code scanOnlinePlayers} treats any code missing
 * from the next snapshot as a removal unless the last recorded action explains it. Two ordinary
 * places hold a live item outside that array — the mouse cursor
 * ({@code Player.getItemOnCursor}) and the crafting input grid of the open view
 * ({@code Player.getOpenInventory().getTopInventory()}) — and neither is read anywhere in
 * {@code src/main/java/com/itemguard/listeners}.
 *
 * <p>The scan runs every {@code SCAN_INTERVAL_TICKS} = 20 ticks, so simply holding a tracked item on
 * the cursor for a second, or leaving it in the 2x2 grid, is enough. The last recorded action of a
 * held item is normally {@code "PICKUP"} ({@code ItemTrackingService} writes it on pickup), which
 * {@code UnexplainedRemovalPolicy.EXPLAINED} does not contain, so the departure is judged
 * unexplained and a {@code CLEARED} row is written for an item that is still in the player's hands.
 *
 * <p>This is the same defect class already fixed once for death loot — see the comment on
 * {@code ItemTrackingService.onItemDeath}, which exists because the watcher "wrote a false CLEARED
 * row for loot that was lying on the ground". {@code CLEARED.confirmsDestruction()} is true, so the
 * record says an existing item was destroyed. What that false history is worth acting on is a
 * separate question: no duplication has been demonstrated here, and the LITE build has no restore
 * path at all, so this asserts the recorded history only.
 *
 * <p>Adding {@code "PICKUP"} to the explained set is deliberately not what these tests ask for: that
 * would also silence a real {@code /clear} of a picked-up item, which is the case the listener was
 * written for. The last two tests pin that down — a genuine removal must still be recorded, with or
 * without an inventory screen open.
 */
class ItemLossInventoryScanPresenceTest {

    private static final String CODE = "ABC123";

    @Test
    void anItemHeldOnTheCursorIsNotRecordedAsCleared() {
        Fixture fixture = new Fixture();
        fixture.tick();

        // Left-click picks the stack up: it leaves the slot array and sits on the cursor. The item
        // exists, nobody removed it, and no action has been recorded because nothing has happened
        // yet — the player is still deciding where to put it.
        fixture.contents.set(new ItemStack[] {null});
        fixture.cursor.set(fixture.tracked);

        fixture.tick();
        fixture.settle();

        assertTrue(fixture.journal.reads.isEmpty(),
            "an item on the cursor is still held and must not even be asked about: "
                + fixture.journal.reads);
        assertTrue(fixture.journal.writes.isEmpty(),
            "a loss was recorded for an item on the cursor: " + fixture.journal.writes);
        verify(fixture.tracking, never())
            .recordLossByIdentity(any(), any(), any(), any(), any());
        verify(fixture.tracking, never()).recordLoss(any(), any(), any(), any());

        // And the route is live, not merely silent: the same fixture records the loss the moment the
        // stack genuinely goes. Without this the assertions above pass for a listener that reports
        // nothing at all, which is the bug this whole class was written to prevent.
        fixture.cursor.set(fixture.emptyCursor);
        fixture.tick();
        fixture.settle();
        assertEquals(1, fixture.journal.writes.size(),
            "the offload never reported anything, so the suppression above proves nothing");
    }

    @Test
    void anItemPlacedInTheCraftingInputGridIsNotRecordedAsCleared() {
        Fixture fixture = new Fixture();
        fixture.tick();

        // The player's own inventory screen is a CRAFTING view whose top inventory is the 2x2 input
        // grid plus the result slot. An ingredient waiting there is not in getContents(), and a
        // player can leave it there indefinitely.
        fixture.contents.set(new ItemStack[] {null});
        fixture.topContents.set(new ItemStack[] {fixture.tracked, null, null, null, null});

        fixture.tick();
        fixture.settle();

        assertTrue(fixture.journal.reads.isEmpty(),
            "an item in the open view must not even be asked about: " + fixture.journal.reads);
        assertTrue(fixture.journal.writes.isEmpty(),
            "a loss was recorded for an item in the crafting grid: " + fixture.journal.writes);
        verify(fixture.tracking, never())
            .recordLossByIdentity(any(), any(), any(), any(), any());
        verify(fixture.tracking, never()).recordLoss(any(), any(), any(), any());

        // Live-route check, taking the item back into the slots first. Emptying the grid instead
        // would report nothing, and not because the route is dead: the identity has already fallen
        // out of the baseline. That is a real defect and it has its own test below rather than being
        // worked around here.
        fixture.topContents.set(new ItemStack[] {null, null, null, null, null});
        fixture.contents.set(new ItemStack[] {fixture.tracked});
        fixture.tick();

        fixture.contents.set(new ItemStack[] {null});
        fixture.tick();
        fixture.settle();
        assertEquals(1, fixture.journal.writes.size(),
            "the offload never reported anything, so the suppression above proves nothing");
    }

    @Test
    void anItemThatDisappearsFromTheOpenViewIsNeverReported() {
        // Suppressing on the open view used to drop the identity from the baseline at the same time,
        // so the pass after the suppression had nothing to compare against and whatever happened to
        // that stack next was invisible for good. Same shape as the cursor blind spot that
        // anItemDeletedWhileOnTheCursorIsStillRecorded guards against, and pre-existing: the old
        // synchronous scan lost the baseline in exactly the same place. It only surfaced when
        // something finally checked the pass after a suppression.
        //
        // Fixed the way the cursor was — the player's own 2x2 grid is part of the snapshot, because
        // it is the player's and it travels with them. A chest is not, and keeps the old treatment;
        // see anItemLeftInAnExternalContainerIsNotRecordedWhenTheViewCloses.
        Fixture fixture = new Fixture();
        fixture.tick();

        fixture.contents.set(new ItemStack[] {null});
        fixture.topContents.set(new ItemStack[] {fixture.tracked, null, null, null, null});
        fixture.tick();
        fixture.settle();
        assertTrue(fixture.journal.writes.isEmpty(), "the item was visible, so nothing is due yet");

        // Now it genuinely goes: out of the grid and nowhere else. Nobody can see it any more.
        fixture.topContents.set(new ItemStack[] {null, null, null, null, null});
        fixture.tick();
        fixture.settle();

        assertEquals(1, fixture.journal.writes.size(),
            "an item that vanished out of the open view was never reported: the identity was "
                + "dropped from the baseline when the view suppressed it");
    }

    @Test
    void anItemLeftInAnExternalContainerIsNotRecordedWhenTheViewCloses() {
        // The bound on the fix above. Watching the player's own crafting grid must not turn into
        // watching every open container: a chest belongs to the world, not to whoever happens to have
        // it open. An item put in one and left there is still there after the screen closes, and the
        // screen closing is not evidence of anything at all — treating it as a departure would write
        // a confirmed destruction for a stack sitting in a chest anybody can walk up to.
        Fixture fixture = new Fixture();

        // A chest is a plain Inventory belonging to a block, not a CraftingInventory the player
        // holds, so the listener must give it no custody at all.
        Inventory chest = mock(Inventory.class);
        AtomicReference<ItemStack[]> chestContents =
            new AtomicReference<>(new ItemStack[] {null, null, null});
        when(chest.getContents()).thenAnswer(call -> chestContents.get());
        when(fixture.view.getTopInventory()).thenReturn(chest);
        fixture.tick();

        // Moved into the chest: out of the slots, visible in the open view.
        fixture.contents.set(new ItemStack[] {null});
        chestContents.set(new ItemStack[] {fixture.tracked, null, null});
        fixture.tick();
        fixture.settle();
        assertTrue(fixture.journal.writes.isEmpty(),
            "an item visible in an open chest was reported as lost: " + fixture.journal.writes);

        // The player closes the chest. The stack has not moved; only the screen has.
        chestContents.set(new ItemStack[] {null, null, null});
        fixture.tick();
        fixture.settle();

        assertTrue(fixture.journal.writes.isEmpty(),
            "closing a container was treated as the item being destroyed: " + fixture.journal.writes);
        verify(fixture.tracking, never()).recordLoss(any(), any(), any(), any());
    }

    @Test
    void anItemThatIsGenuinelyGoneIsStillRecordedAsCleared() {
        // The positive control. Without it, every assertion above is satisfied by a listener that
        // stops reporting removals altogether, which would put back the bug this listener exists to
        // fix: /clear took the item and the records still showed it as held.
        Fixture fixture = new Fixture();
        fixture.tick();

        fixture.contents.set(new ItemStack[] {null});

        fixture.tick();
        fixture.settle();

        // The route moved, the requirement did not: the loss is no longer written by the tracking
        // service on the scan thread, it is submitted to the journal and written there. This watches
        // the far end of the same decision.
        assertEquals(1, fixture.journal.writes.size(),
            "a genuine removal was not reported: " + fixture.journal.writes);
        LossRecord written = fixture.journal.writes.get(0);
        assertEquals(CODE, written.code());
        assertEquals(fixture.itemUuid, written.itemUuid());
        assertEquals(LossReason.CLEARED, written.reason());
        assertEquals(fixture.player.getUniqueId(), written.playerUuid());
    }

    @Test
    void theLocationOfAGenuineRemovalIsCapturedOnTheScanThread() {
        // The old path passed player.getLocation() straight into recordLossByIdentity, so every
        // inventory-clear row carried where it happened. The offloaded path must not lose that, and
        // it cannot read a Location once it is off the main thread: a Bukkit Location is not safe to
        // touch from the journal's thread and the player may have moved or logged out by then. So the
        // capture has to happen here, on the scan, while the candidate is being built.
        //
        // RED until that capture exists. It pins the capture point only; that the captured value
        // reaches the row is pinned separately, at the journal.
        Fixture fixture = new Fixture();
        fixture.tick();

        fixture.contents.set(new ItemStack[] {null});
        fixture.tick();

        verify(fixture.player, atLeastOnce()).getLocation();
    }

    @Test
    void anItemDeletedWhileOnTheCursorIsStillRecordedAsCleared() {
        // The risk the cursor fix itself creates. Suppressing the report for a dragged item would
        // also be a permanent blind spot if the identity were dropped from the watch at the same
        // time: whatever removed it afterwards would never be reported. Counting the cursor as held
        // keeps it under watch, so the scan after it genuinely disappears still records the loss.
        Fixture fixture = new Fixture();
        fixture.tick();

        fixture.contents.set(new ItemStack[] {null});
        fixture.cursor.set(fixture.tracked);
        fixture.tick();

        fixture.cursor.set(fixture.emptyCursor);
        fixture.tick();
        fixture.settle();

        assertEquals(1, fixture.journal.writes.size(),
            "the identity was dropped from the watch while it sat on the cursor: "
                + fixture.journal.writes);
        assertEquals(CODE, fixture.journal.writes.get(0).code());
        assertEquals(fixture.itemUuid, fixture.journal.writes.get(0).itemUuid());
        assertEquals(LossReason.CLEARED, fixture.journal.writes.get(0).reason());
    }

    @Test
    void aRemovalWhileAnInventoryScreenIsOpenIsStillRecordedAsCleared() {
        // Bounds the fix: "the player has a screen open" is not a reason to skip them. A staff member
        // running /clear on somebody browsing their own inventory must still produce a record. Only
        // the item actually being somewhere may suppress the row, so the cursor and the grid here
        // hold a different item.
        Fixture fixture = new Fixture();
        fixture.tick();

        ItemStack other = mock(ItemStack.class);
        when(fixture.tracking.getCodeFromItem(other)).thenReturn("ZZZ999");
        when(fixture.tracking.getItemUuidFromItem(other)).thenReturn(UUID.randomUUID());
        when(fixture.tracking.getLastRecordedAction("ZZZ999")).thenReturn("PICKUP");

        fixture.contents.set(new ItemStack[] {null});
        fixture.cursor.set(other);
        fixture.topContents.set(new ItemStack[] {other, null, null, null, null});

        fixture.tick();
        fixture.settle();

        assertEquals(1, fixture.journal.writes.size(),
            "an open inventory screen suppressed a removal it has nothing to do with: "
                + fixture.journal.writes);
        assertEquals(CODE, fixture.journal.writes.get(0).code());
        assertEquals(fixture.itemUuid, fixture.journal.writes.get(0).itemUuid());
        assertEquals(LossReason.CLEARED, fixture.journal.writes.get(0).reason());
    }

    /**
     * One online player holding one tracked stack, with the scan task driven by hand.
     *
     * <p>The listener is exercised through {@code start()} and the captured scheduler task rather
     * than through a test-only entry point, so the production wiring stays untouched.
     */
    private static final class Fixture {

        final ItemTrackingService tracking = mock(ItemTrackingService.class);
        final ItemGuard plugin = mock(ItemGuard.class);
        final Player player = mock(Player.class);
        final PlayerInventory inventory = mock(PlayerInventory.class);
        final InventoryView view = mock(InventoryView.class);

        /**
         * The player's own inventory screen: a crafting grid they hold, not a block in the world.
         *
         * <p>A {@link CraftingInventory} whose holder is this player, which is what the listener
         * checks. Deliberately not expressed with {@code InventoryType}: on Paper 1.21.11 that enum
         * initialises {@code MenuType}, which asks for a registry no offline test has, and touching it
         * once poisons the class for every test that follows in the same JVM.
         */
        final CraftingInventory topInventory = mock(CraftingInventory.class);

        /**
         * A real ItemStack needs a Paper registry that an offline test does not have; only whether
         * the tracking service resolves an identity from it matters here.
         */
        final ItemStack tracked = mock(ItemStack.class);
        final ItemStack emptyCursor = mock(ItemStack.class);
        final UUID itemUuid = UUID.randomUUID();

        final AtomicReference<ItemStack[]> contents = new AtomicReference<>();
        final AtomicReference<ItemStack> cursor = new AtomicReference<>();
        final AtomicReference<ItemStack[]> topContents = new AtomicReference<>();
        final AtomicReference<Runnable> scan = new AtomicReference<>();

        /** The main thread the coordinator hops back to, drained by {@link #settle}. */
        final Deque<Runnable> mainThread = new ArrayDeque<>();

        /** Stands in for the database's executor: the far end of the route the scan now takes. */
        final TestJournal journal = new TestJournal();

        Fixture() {
            Server server = mock(Server.class);
            BukkitScheduler scheduler = mock(BukkitScheduler.class);
            when(plugin.getTrackingService()).thenReturn(tracking);
            when(plugin.getServer()).thenReturn(server);
            // The recorded-loss callback writes an audit line, so the logger has to be real.
            when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger(getClass().getName()));
            when(server.getScheduler()).thenReturn(scheduler);
            doReturn(List.of(player)).when(server).getOnlinePlayers();
            when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong()))
                .thenAnswer(call -> {
                    scan.set(call.getArgument(1, Runnable.class));
                    return mock(BukkitTask.class);
                });

            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            when(player.getInventory()).thenReturn(inventory);
            when(player.getOpenInventory()).thenReturn(view);
            when(view.getTopInventory()).thenReturn(topInventory);

            // Bukkit never returns null for the cursor; an empty hand is an untracked stack.
            contents.set(new ItemStack[] {tracked});
            cursor.set(emptyCursor);
            topContents.set(new ItemStack[] {null, null, null, null, null});
            when(inventory.getContents()).thenAnswer(call -> contents.get());
            when(player.getItemOnCursor()).thenAnswer(call -> cursor.get());
            when(topInventory.getContents()).thenAnswer(call -> topContents.get());
            // What makes this the player's own grid rather than a block they are standing at. Until
            // now it was only asserted in a comment, and nothing distinguished it from a chest.
            when(topInventory.getHolder()).thenReturn(player);

            when(tracking.getCodeFromItem(tracked)).thenReturn(CODE);
            when(tracking.getItemUuidFromItem(tracked)).thenReturn(itemUuid);
            // What a held item's history actually says: it was picked up and nothing has happened
            // since. UnexplainedRemovalPolicy does not list PICKUP as an explained departure.
            when(tracking.getLastRecordedAction(CODE)).thenReturn("PICKUP");

            // Still online when the journal answers: the revalidation re-reads the live inventory.
            when(player.isOnline()).thenReturn(true);

            ItemLossListener listener = new ItemLossListener(plugin);
            // The scan no longer touches the database on the tick; it reports departures through the
            // offload and the answers come back on the dispatcher. Injecting a working one is what
            // keeps the never() assertions honest — without it every one of them would be satisfied
            // by a listener that reports nothing at all.
            listener.useOffload(new ItemLossScanOffloadCoordinator(
                journal, mainThread::add, () -> true, new ScanEpochGenerator()));
            listener.start();
            assertNotNull(scan.get(), "the inventory scan task must be scheduled by start()");
        }

        /** Runs one pass of the 20-tick inventory scan. */
        void tick() {
            scan.get().run();
        }

        /** Answers the journal and runs what comes back: read first, then the write it approves. */
        void settle() {
            journal.answerRead("PICKUP");
            drainMainThread();
            journal.answerWrite(true);
            drainMainThread();
        }

        private void drainMainThread() {
            for (Runnable task = mainThread.poll(); task != null; task = mainThread.poll()) {
                task.run();
            }
        }
    }

    /** A journal that holds its answers until the test gives them. */
    private static final class TestJournal implements LossJournal {

        final List<String> reads = Collections.synchronizedList(new ArrayList<>());
        final List<LossRecord> writes = Collections.synchronizedList(new ArrayList<>());

        private final Deque<CompletableFuture<String>> pendingReads = new ArrayDeque<>();
        private final Deque<CompletableFuture<Boolean>> pendingWrites = new ArrayDeque<>();

        @Override
        public CompletableFuture<String> lastRecordedAction(String code) {
            reads.add(code);
            CompletableFuture<String> future = new CompletableFuture<>();
            pendingReads.add(future);
            return future;
        }

        @Override
        public CompletableFuture<Boolean> recordLoss(LossRecord record) {
            writes.add(record);
            record.permit().claim();
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            pendingWrites.add(future);
            return future;
        }

        void answerRead(String action) {
            CompletableFuture<String> future = pendingReads.poll();
            if (future != null) {
                future.complete(action);
            }
        }

        void answerWrite(boolean applied) {
            CompletableFuture<Boolean> future = pendingWrites.poll();
            if (future != null) {
                future.complete(applied);
            }
        }
    }
}
