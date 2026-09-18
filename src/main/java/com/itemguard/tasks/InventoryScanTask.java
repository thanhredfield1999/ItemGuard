package com.itemguard.tasks;

import com.itemguard.ItemGuard;
import com.itemguard.dupe.ChunkSweepCursor;
import com.itemguard.dupe.DuplicateFinding;
import com.itemguard.dupe.ObservationEpochFinalizer;
import com.itemguard.dupe.ScanEpochGenerator;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class InventoryScanTask extends BukkitRunnable {

    private final ItemGuard plugin;
    private final ScanEpochGenerator epochGenerator;
    private final ObservationEpochFinalizer epochFinalizer;
    private final ObservationEpochScanner<Player> epochScanner;
    private final BukkitTask sweepAdvanceTask;
    private ChunkSweepCursor<Chunk> sweepCursor;
    private volatile boolean epochInitialized;
    private volatile Throwable epochInitializationFailure;

    public InventoryScanTask(ItemGuard plugin) {
        this.plugin = plugin;
        this.epochGenerator = new ScanEpochGenerator(System::currentTimeMillis);
        this.epochFinalizer = new ObservationEpochFinalizer(
            plugin.getDB()::completeObservationEpochAndAudit,
            runnable -> plugin.getServer().getScheduler().runTask(plugin, runnable),
            this::reportFindings,
            failure -> plugin.getLogger().log(
                Level.SEVERE,
                "Failed to finalize ItemGuard observation epoch",
                failure
            ),
            System::currentTimeMillis
        );
        this.epochScanner = new ObservationEpochScanner<>(
            plugin.getTrackingService()::scanPlayerInventory,
            plugin.getTrackingService()::scanOpenBlockContainerInventory,
            this::finalizeEpoch
        );
        this.sweepCursor = newSweepCursor();
        // Scheduled unconditionally, independent of anti-dupe.sweep.enabled: this is what makes it
        // structurally impossible for run() to ever open a sweep pass that nothing advances, even if
        // sweep is toggled on later via /ig reload after being off at startup.
        this.sweepAdvanceTask = plugin.getServer().getScheduler().runTaskTimer(
            plugin,
            this::advanceSweep,
            1L,
            1L
        );
        initializeEpochFloorAsync();
    }

    private void initializeEpochFloorAsync() {
        CompletableFuture<Long> persistedEpoch = plugin.getDB()
            .getMaximumPersistedObservationEpochAsync();
        if (persistedEpoch == null) {
            // The production DatabaseManager always returns a future. A null test seam represents
            // an empty store and must not make construction block the server thread.
            persistedEpoch = CompletableFuture.completedFuture(Long.MIN_VALUE);
        }
        persistedEpoch.whenComplete((floor, failure) -> {
            if (failure != null) {
                epochInitializationFailure = failure;
                plugin.getLogger().log(
                    Level.SEVERE,
                    "Failed to initialize ItemGuard observation epoch; scan remains disabled",
                    failure
                );
                return;
            }
            epochGenerator.initializeEpochFloor(
                floor == null ? Long.MIN_VALUE : floor
            );
            epochInitialized = true;
        });
    }

    private ChunkSweepCursor<Chunk> newSweepCursor() {
        // Re-read live so anti-dupe.sweep.chunks-per-tick changes apply to the next pass without a
        // restart, instead of being frozen at whatever the task's construction-time value was.
        return new ChunkSweepCursor<>(Math.max(1, plugin.getConfigs().getSweepChunksPerTick()));
    }

    public void cancelSweepTicker() {
        sweepAdvanceTask.cancel();
    }

    @Override
    public void run() {
        if (epochInitializationFailure != null || !epochInitialized) {
            return;
        }
        if (sweepCursor.isPassInFlight()) {
            plugin.getLogger().log(
                plugin.getConfigs().isDebug() ? Level.INFO : Level.FINE,
                "Skipping ItemGuard inventory scan tick: chunk container sweep still in flight"
            );
            return;
        }

        long scanEpoch = epochGenerator.next();
        if (!plugin.getConfigs().isSweepEnabled()) {
            epochScanner.scan(scanEpoch, Bukkit.getOnlinePlayers());
            return;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            plugin.getTrackingService().scanPlayerInventory(player, scanEpoch);
            plugin.getTrackingService().scanOpenBlockContainerInventory(player, scanEpoch);
        }
        startSweepPass(scanEpoch);
    }

    private void startSweepPass(long scanEpoch) {
        List<Chunk> loadedChunks = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            loadedChunks.addAll(List.of(world.getLoadedChunks()));
        }
        sweepCursor = newSweepCursor();
        sweepCursor.startPass(loadedChunks, scanEpoch);
    }

    /**
     * Advances the in-flight chunk container sweep by one tick's chunk budget; a no-op when no
     * sweep is in flight. Driven by a separate per-tick scheduler entry so the sweep can span many
     * ticks while {@link #run()} still only fires on the configured scan interval.
     */
    public void advanceSweep() {
        if (!sweepCursor.isPassInFlight()) {
            return;
        }
        ChunkSweepCursor.SweepBatch<Chunk> batch = sweepCursor.advance(Chunk::isLoaded);
        for (Chunk chunk : batch.visited()) {
            for (BlockState state : chunk.getTileEntities()) {
                plugin.getTrackingService().recordLoadedContainerObservations(
                    state, batch.epochId()
                );
            }
        }
        if (batch.passComplete()) {
            finalizeEpoch(batch.epochId());
        }
    }

    private void finalizeEpoch(long scanEpoch) {
        epochFinalizer.complete(
            scanEpoch,
            plugin.getConfigs().isAntiDupeEnabled(),
            plugin.getConfigs().getAntiDupeAction(),
            plugin.getConfigs().getDetectionCooldown()
        );
    }

    private void reportFindings(List<DuplicateFinding> findings) {
        for (DuplicateFinding finding : findings) {
            plugin.getLogger().warning(
                "ITEMGUARD_DUPLICATE_CONFIRMED code=" + finding.code()
                    + " uuid=" + finding.itemUuid()
                    + " epoch=" + finding.scanEpoch()
                    + " locations=" + finding.distinctLocations()
                    + " action=" + finding.action()
            );
            if (!plugin.getConfigs().isNotifyStaff()) {
                continue;
            }
            String alert = plugin.getMessages().getRaw("dupe-detected", Map.of(
                "item", finding.code(),
                "code", finding.code()
            ));
            String locations = plugin.getMessages().getRaw("dupe-locations", Map.of(
                "locations", String.valueOf(finding.distinctLocations())
            ));
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission(plugin.isLiteEdition() ? "itemguard.notify" : "itemguard.bypass")) {
                    player.sendMessage(alert);
                    player.sendMessage(locations);
                }
            }
        }
    }
}
