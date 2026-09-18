package com.itemguard.tasks;

import com.itemguard.ItemGuard;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for the reload lifecycle bug: a chunk container sweep that is off at boot and
 * later turned on via {@code /ig reload} must not strand its epoch, because nothing outside
 * {@link InventoryScanTask} itself was ever guaranteed to call {@link InventoryScanTask#advanceSweep()}.
 */
class InventoryScanTaskSweepLifecycleTest {

    private static ItemGuard plugin(AtomicBoolean sweepEnabled, int chunksPerTick) {
        ItemGuard plugin = mock(ItemGuard.class, RETURNS_DEEP_STUBS);
        when(plugin.getConfigs().isSweepEnabled()).thenAnswer(inv -> sweepEnabled.get());
        when(plugin.getConfigs().getSweepChunksPerTick()).thenReturn(chunksPerTick);
        when(plugin.getDB().getMaximumPersistedObservationEpochAsync())
            .thenReturn(CompletableFuture.completedFuture(Long.MIN_VALUE));
        return plugin;
    }

    private static Chunk loadedChunk() {
        Chunk chunk = mock(Chunk.class);
        when(chunk.isLoaded()).thenReturn(true);
        when(chunk.getTileEntities()).thenReturn(new BlockState[0]);
        return chunk;
    }

    @Test
    void constructingTaskAlwaysSchedulesItsOwnSweepAdvanceTickerEvenWhenSweepStartsDisabled() {
        ItemGuard plugin = plugin(new AtomicBoolean(false), 5);

        new InventoryScanTask(plugin);

        BukkitScheduler scheduler = plugin.getServer().getScheduler();
        verify(scheduler).runTaskTimer(eq(plugin), any(Runnable.class), eq(1L), eq(1L));
    }

    @Test
    void enablingSweepAfterConstructionStillGetsAdvancedAndFinalizedByTheSelfOwnedTicker() {
        AtomicBoolean sweepEnabled = new AtomicBoolean(false);
        ItemGuard plugin = plugin(sweepEnabled, 5);
        BukkitScheduler scheduler = plugin.getServer().getScheduler();
        ArgumentCaptor<Runnable> tickerCaptor = ArgumentCaptor.forClass(Runnable.class);

        InventoryScanTask task = new InventoryScanTask(plugin);
        verify(scheduler).runTaskTimer(eq(plugin), tickerCaptor.capture(), eq(1L), eq(1L));
        Runnable ticker = tickerCaptor.getValue();

        // Simulate an admin flipping anti-dupe.sweep.enabled on and running /ig reload. That key is
        // reload-safe (absent from RestartSensitiveSettings), so no restart happens in between.
        sweepEnabled.set(true);

        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
            bukkit.when(Bukkit::getWorlds).thenReturn(List.<World>of());

            task.run();
            ticker.run();
        }

        verify(plugin.getDB(), atLeastOnce()).completeObservationEpochAndAudit(
            anyLong(), anyBoolean(), any(), anyLong(), anyLong()
        );
    }

    @Test
    void runNeverOpensASweepPassBeforeItsOwnAdvancerIsAlreadyScheduled() {
        ItemGuard plugin = plugin(new AtomicBoolean(true), 1);
        BukkitScheduler scheduler = plugin.getServer().getScheduler();
        ArgumentCaptor<Runnable> tickerCaptor = ArgumentCaptor.forClass(Runnable.class);

        InventoryScanTask task = new InventoryScanTask(plugin);

        // The advancer must already exist before run() is even reachable by any scheduler, not just
        // after run() happens to open a pass - otherwise a first-tick race could still strand an epoch.
        verify(scheduler).runTaskTimer(eq(plugin), tickerCaptor.capture(), eq(1L), eq(1L));
        Runnable ticker = tickerCaptor.getValue();

        World world = mock(World.class);
        Chunk first = loadedChunk();
        Chunk second = loadedChunk();
        when(world.getLoadedChunks()).thenReturn(new Chunk[]{first, second});

        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
            bukkit.when(Bukkit::getWorlds).thenReturn(List.of(world));

            task.run();
            // Budget is 1 chunk/tick over 2 chunks: the pass is still in flight after one tick and
            // must not be left to nothing but this pre-existing, already-scheduled ticker.
            ticker.run();
            ticker.run();
        }

        verify(plugin.getDB()).completeObservationEpochAndAudit(
            anyLong(), anyBoolean(), any(), anyLong(), anyLong()
        );
    }

    @Test
    void disabledSweepStillFinalizesItsEpochThroughTheDirectScanPath() {
        ItemGuard plugin = plugin(new AtomicBoolean(false), 5);
        InventoryScanTask task = new InventoryScanTask(plugin);

        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());

            task.run();

            bukkit.verify(Bukkit::getWorlds, never());
        }

        verify(plugin.getDB()).completeObservationEpochAndAudit(
            anyLong(), anyBoolean(), any(), anyLong(), anyLong()
        );
    }
}
