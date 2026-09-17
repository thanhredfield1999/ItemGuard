package com.itemguard.listeners;

import com.itemguard.ItemGuard;
import com.itemguard.data.DatabaseManager;
import com.itemguard.data.ItemHistory;
import com.itemguard.dupe.ScanEpochGenerator;
import com.itemguard.restore.LossReason;
import com.itemguard.services.ItemTrackingService;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.invocation.InvocationOnMock;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * The one-second inventory scan reaches the database from the thread it runs on.
 *
 * <p>Observed in source, and this is the whole of the path, not a summary of it:
 * {@code ItemLossListener.start} schedules {@code scanOnlinePlayers} with
 * {@code BukkitScheduler.runTaskTimer} — the synchronous timer, whose documented contract is that
 * the task runs on the server's primary thread. For every code missing from a player's next
 * snapshot, {@code scanOnlinePlayers} calls {@code tracking.getLastRecordedAction(code)}, which is
 * {@code ItemTrackingService.getLastRecordedAction} → {@code db.getHistory(code, 1)}, a JDBC read.
 * When the departure is unexplained it then calls {@code recordLossByIdentity}, which is
 * {@code db.updateItemLastAction(...)} followed by {@code db.logHistory(...)}, two JDBC writes.
 * Nothing between the scheduler and those three calls hands the work to another thread.
 *
 * <p>So the tick that notices a genuinely departed item pays for a database round trip inline, once
 * per departed code per player, and the server waits for it. That is the claim under test and the
 * only claim under test. What it costs in practice is not asserted here: no timing, no lag figure,
 * no duplication. LITE has no restore path, so nothing about item recovery follows from it either.
 *
 * <p>Scope, deliberately: this pins the blocking call itself, not a remedy. A cache of last actions,
 * an async offload, and a rearm on capacity loss are each a design with their own failure modes —
 * an offload in particular introduces a read/write race that does not exist today — and none of them
 * is asserted by these tests. Any change that keeps the three database calls off the scan thread
 * satisfies the regression.
 *
 * <p>The three controls below exist because the regression alone is satisfied by a listener that
 * stops looking: a scan that never queries and never records passes it. They hold the rest of the
 * behaviour still — the genuine removal must still be recorded, and the cursor and crafting-grid
 * suppressions proved by {@link ItemLossInventoryScanPresenceTest} must still cost no query at all,
 * which is what {@code scanOnlinePlayers} orders its checks for.
 *
 * <p>Unlike that test, the tracking service here is real code: only identity extraction from the
 * mocked stacks is stubbed, so {@code getLastRecordedAction} and {@code recordLossByIdentity} run
 * their production bodies against a mocked {@link DatabaseManager}. That is what makes the database
 * calls observable at all — a mocked service would prove only that the listener calls a method.
 */
class ItemLossScanMainThreadDatabaseTest {

    private static final String CODE = "ABC123";

    @Test
    void aGenuineRemovalDoesNotQueryTheDatabaseOnTheScanThread() {
        Fixture fixture = new Fixture();
        fixture.tick();

        // Genuinely gone: not on the cursor, not in the open view, nowhere the player can see it.
        // This is the case the listener exists to report, and the only case that reaches the
        // history read at all.
        fixture.contents.set(new ItemStack[] {null});

        Thread scanThread = Thread.currentThread();
        fixture.tick();

        List<DbCall> inline = fixture.callsOn(scanThread);
        assertTrue(inline.isEmpty(),
            "the inventory scan reached the database on the thread running the scan task, which "
                + "runTaskTimer makes the primary thread: " + inline);
    }

    @Test
    void aGenuineRemovalIsStillRecorded() throws Exception {
        // Positive control. Without it the regression above is satisfied by deleting the history
        // read and the loss row together, which restores the defect this listener was written for:
        // /clear takes an item and the records still show it as held.
        //
        // The route it verifies has moved, not the requirement. The loss is no longer written by
        // ItemTrackingService on the scan thread; it is submitted to a LossJournal, read and written
        // on that journal's own thread, and answered back through the dispatcher. So the control now
        // watches the journal — the far end of the same decision — rather than a method the listener
        // no longer calls.
        Fixture fixture = new Fixture();
        fixture.tick();

        fixture.contents.set(new ItemStack[] {null});
        fixture.tick();
        fixture.settle();

        assertEquals(1, fixture.journal.writes.size(),
            "the departure never reached the journal: " + fixture.journal.writes);
        LossRecord written = fixture.journal.writes.get(0);
        assertEquals(CODE, written.code());
        assertEquals(fixture.itemUuid, written.itemUuid());
        assertEquals(LossReason.CLEARED, written.reason());
        assertEquals(fixture.playerUuid, written.playerUuid());

        // And the answer came back: the code left the baseline, so the next pass is silent.
        fixture.tick();
        assertEquals(1, fixture.journal.writes.size(),
            "the same departure was reported twice: " + fixture.journal.writes);
    }

    @Test
    void aDepartureStillInFlightIsNotResubmittedByTheNextScan() {
        // Across-scan lifecycle. The scan runs every second; a journal that has not answered yet must
        // not have its pass superseded, or a slow answer is cancelled and retried forever and no loss
        // is ever recorded. The token must also survive that wait, so the retry that does happen is
        // recognised as the same departure rather than written twice.
        Fixture fixture = new Fixture();
        fixture.tick();

        fixture.contents.set(new ItemStack[] {null});
        fixture.tick();
        assertEquals(1, fixture.journal.reads.size(), "the first pass must submit exactly one read");

        // Two more passes while the journal stays silent.
        fixture.tick();
        fixture.tick();
        assertEquals(1, fixture.journal.reads.size(),
            "an in-flight departure was resubmitted while its read was still pending: "
                + fixture.journal.reads);

        // It answers, the write lands, and the code is released from the baseline.
        fixture.settle();
        assertEquals(1, fixture.journal.writes.size(), "the pending departure never completed");

        fixture.tick();
        assertEquals(1, fixture.journal.writes.size(),
            "a recorded departure was reported again after completing");
    }

    @Test
    void anItemHeldOnTheCursorCostsNoDatabaseCallAtAll() {
        // Control for the cursor suppression, stated as cost rather than as outcome. An item on the
        // cursor is in the snapshot, so the scan never reaches the history read for it; a fix that
        // buys speed by querying first and deciding afterwards would break this.
        Fixture fixture = new Fixture();
        fixture.tick();

        fixture.contents.set(new ItemStack[] {null});
        fixture.cursor.set(fixture.tracked);

        fixture.dbCalls.clear();
        fixture.tick();

        assertTrue(fixture.dbCalls.isEmpty(),
            "an item on the cursor is still held and must cost no database call: " + fixture.dbCalls);
        verify(fixture.tracking, never()).recordLossByIdentity(any(), any(), any(), any(), any());
    }

    @Test
    void anItemInTheCraftingInputGridCostsNoDatabaseCallAtAll() {
        // Same control for the other ordinary place a live item sits outside getContents(): the 2x2
        // input grid of the player's own crafting view.
        Fixture fixture = new Fixture();
        fixture.tick();

        fixture.contents.set(new ItemStack[] {null});
        fixture.topContents.set(new ItemStack[] {fixture.tracked, null, null, null, null});

        fixture.dbCalls.clear();
        fixture.tick();

        assertTrue(fixture.dbCalls.isEmpty(),
            "an item visible in the open view must cost no database call: " + fixture.dbCalls);
        verify(fixture.tracking, never()).recordLossByIdentity(any(), any(), any(), any(), any());
    }

    @Test
    void theScanIsScheduledOnTheSynchronousTimer() {
        // What makes "the scan thread" the primary thread in production rather than an assumption
        // about this test's own thread. Pinned so the regression above cannot be defused by reading
        // it as a statement about some background thread.
        Fixture fixture = new Fixture();

        verify(fixture.scheduler).runTaskTimer(
            eq(fixture.plugin), any(Runnable.class), anyLong(), anyLong());
        verify(fixture.scheduler, never()).runTaskTimerAsynchronously(
            any(Plugin.class), any(Runnable.class), anyLong(), anyLong());
    }

    @Test
    void aDepartureStrandedByOneRefusedDispatchIsRecoveredByALaterScan() {
        // One refused hop back and the departure is stranded for good.
        //
        // The coordinator parks an entry whose dispatch was refused rather than dropping it, because
        // the caller has already retired its baseline and only the coordinator still knows the code is
        // missing. Paying that debt back is beginGeneration's job: it is the one main-thread call that
        // discards a parked entry and rearms the baseline through it.
        //
        // The scan never makes that call again. request() returned true, so the listener left the code
        // out of the baseline; the next pass therefore finds nothing departed and returns at the empty
        // check, never reaching beginGeneration. The parked entry stays in pending, holding its slot
        // and its departure token, and the loss is never written. hasPending would block the call too,
        // but the code does not even get that far.
        //
        // What this test does not accept as a fix: a quit, which throws the whole snapshot away, or
        // opening a generation every tick, which cancels reads a slow journal has not answered yet.
        // Recovery has to come from an ordinary scan on an ordinary player.
        Fixture fixture = new Fixture();
        fixture.tick();

        fixture.contents.set(new ItemStack[] {null});
        fixture.tick();
        assertEquals(1, fixture.journal.reads.size(), "the departure was never submitted");

        // The journal answers while the scheduler is refusing, so the answer cannot land.
        fixture.rejectDispatch.set(true);
        fixture.journal.answerRead("PICKUP");
        fixture.rejectDispatch.set(false);
        assertTrue(fixture.journal.writes.isEmpty(),
            "nothing can be written while the answer cannot reach the main thread");

        // The scheduler is healthy again and the item is still gone. Ordinary scans follow.
        for (int pass = 0; pass < 4; pass++) {
            fixture.tick();
            fixture.settle();
        }

        assertEquals(1, fixture.journal.writes.size(),
            "a departure stranded by one refused dispatch was never picked up again: "
                + fixture.journal.writes);
        LossRecord written = fixture.journal.writes.get(0);
        assertEquals(CODE, written.code());
        assertEquals(fixture.itemUuid, written.itemUuid());
        assertEquals(LossReason.CLEARED, written.reason());
    }

    /** One database call: which method, and which thread made it. */
    private record DbCall(String method, Thread thread) {
        @Override
        public String toString() {
            return method + "() on " + thread.getName();
        }
    }

    /**
     * One online player holding one tracked stack, with the scan task driven by hand.
     *
     * <p>The listener is exercised through {@code start()} and the captured scheduler task, and the
     * tracking service is the production class with only identity extraction stubbed, so everything
     * between the scan and the database is real code.
     */
    private static final class Fixture {

        final ItemGuard plugin = mock(ItemGuard.class);
        final Player player = mock(Player.class);
        final PlayerInventory inventory = mock(PlayerInventory.class);
        final InventoryView view = mock(InventoryView.class);
        final Inventory topInventory = mock(Inventory.class);
        final BukkitScheduler scheduler = mock(BukkitScheduler.class);

        /**
         * A real ItemStack needs a Paper registry that an offline test does not have; only whether
         * the tracking service resolves an identity from it matters here.
         */
        final ItemStack tracked = mock(ItemStack.class);
        final ItemStack emptyCursor = mock(ItemStack.class);
        final UUID itemUuid = UUID.randomUUID();
        final UUID playerUuid = UUID.randomUUID();

        final List<DbCall> dbCalls = Collections.synchronizedList(new ArrayList<>());
        final AtomicReference<ItemStack[]> contents = new AtomicReference<>();
        final AtomicReference<ItemStack> cursor = new AtomicReference<>();
        final AtomicReference<ItemStack[]> topContents = new AtomicReference<>();
        final AtomicReference<Runnable> scan = new AtomicReference<>();

        /** The main thread the coordinator hops back to, drained by hand. */
        final Deque<Runnable> mainThread = new ArrayDeque<>();

        /** Stands in for the database's own executor: reads and writes answer when told to. */
        final TestJournal journal = new TestJournal();

        /** While set, the hop back to the main thread is refused the way a busy scheduler refuses. */
        final AtomicBoolean rejectDispatch = new AtomicBoolean();

        final DatabaseManager db;
        final ItemTrackingService tracking;

        Fixture() {
            // Every call on the database is logged with its calling thread, whatever the method, so
            // a future offload to some other database entry point is caught too.
            db = mock(DatabaseManager.class, withSettings().defaultAnswer(this::recordAndAnswer));

            // The production service, built without its constructor so that no live server, config
            // or connection is needed, then given the two collaborators these code paths use.
            tracking = mock(ItemTrackingService.class, CALLS_REAL_METHODS);
            inject("db", db);
            inject("plugin", plugin);
            // Only the tag reads are stubbed: those need a Paper PersistentDataContainer on the
            // mocked stacks. getLastRecordedAction and recordLossByIdentity stay production code.
            doReturn(CODE).when(tracking).getCodeFromItem(tracked);
            doReturn(itemUuid).when(tracking).getItemUuidFromItem(tracked);
            doReturn(null).when(tracking).getCodeFromItem(emptyCursor);
            doReturn(null).when(tracking).getItemUuidFromItem(emptyCursor);

            Server server = mock(Server.class);
            when(plugin.getTrackingService()).thenReturn(tracking);
            when(plugin.getServer()).thenReturn(server);
            when(plugin.getLogger()).thenReturn(Logger.getLogger(getClass().getName()));
            when(server.getScheduler()).thenReturn(scheduler);
            doReturn(List.of(player)).when(server).getOnlinePlayers();
            when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong()))
                .thenAnswer(call -> {
                    scan.set(call.getArgument(1, Runnable.class));
                    return mock(BukkitTask.class);
                });

            when(player.getUniqueId()).thenReturn(playerUuid);
            when(player.getName()).thenReturn("tester");
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

            // Still online when the journal answers: the revalidation reads the live inventory.
            when(player.isOnline()).thenReturn(true);

            ItemLossListener listener = new ItemLossListener(plugin);
            listener.useOffload(new ItemLossScanOffloadCoordinator(
                journal,
                task -> {
                    // A scheduler that will not take the task, which is what Bukkit does when the
                    // server is busy tearing down or the plugin is mid-reload. The coordinator is
                    // built to survive it; this makes it happen on demand.
                    if (rejectDispatch.get()) {
                        throw new IllegalStateException("scheduler refused the task");
                    }
                    mainThread.add(task);
                },
                () -> true,
                new ScanEpochGenerator()));
            listener.start();
            assertNotNull(scan.get(), "the inventory scan task must be scheduled by start()");
        }

        /**
         * What a held item's history actually says: it was picked up and nothing has happened since.
         * {@code UnexplainedRemovalPolicy} does not list PICKUP as an explained departure, so the
         * scan treats the disappearance as a removal — the genuine-clear case these tests are about.
         */
        private Object recordAndAnswer(InvocationOnMock invocation) throws Throwable {
            dbCalls.add(new DbCall(invocation.getMethod().getName(), Thread.currentThread()));
            if ("getHistory".equals(invocation.getMethod().getName())) {
                return List.of(new ItemHistory(
                    CODE, itemUuid, "PICKUP", "tester", playerUuid, "world (0, 0, 0)"));
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        }

        private void inject(String fieldName, Object value) {
            try {
                Field field = ItemTrackingService.class.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(tracking, value);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(
                    "ItemTrackingService." + fieldName + " is the seam this test injects through", e);
            }
        }

        /** Database calls made on one particular thread. */
        List<DbCall> callsOn(Thread thread) {
            synchronized (dbCalls) {
                return dbCalls.stream().filter(call -> call.thread() == thread).toList();
            }
        }

        /** Runs one pass of the 20-tick inventory scan. */
        void tick() {
            scan.get().run();
        }

        /**
         * Answers the journal and runs everything that comes back, read then write.
         *
         * <p>Two drains because the decision is in two halves: the read's answer is what reaches the
         * commit point and submits the write, and the write's answer is what releases the baseline.
         */
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

    /**
     * A journal that holds its answers.
     *
     * <p>Enough of the real one to drive the route: the calls are recorded, the futures stay pending
     * until the test completes them, and the write claims its permit the way a worker admitting the
     * write does.
     */
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
