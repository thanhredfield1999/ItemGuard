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
    private final ScanMetrics metrics;
    private final BukkitTask sweepAdvanceTask;
    private ChunkSweepCursor<Chunk> sweepCursor;
    private volatile boolean epochInitialized;
    private volatile Throwable epochInitializationFailure;

    /**
     * Keeps the previous one-argument shape for tests and any caller that does not need to read the
     * numbers back; the plugin itself passes the instance it exposes to the admin command, so the
     * counters a player sees are the ones the scheduler wrote.
     */
    public InventoryScanTask(ItemGuard plugin) {
        this(plugin, new ScanMetrics());
    }

    public InventoryScanTask(ItemGuard plugin, ScanMetrics metrics) {
        this.plugin = plugin;
        this.metrics = java.util.Objects.requireNonNull(metrics, "metrics");
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

    /** The scan's own numbers, for the admin diagnostics command. */
    public ScanMetrics metrics() {
        return metrics;
    }

    @Override
    public void run() {
        if (epochInitializationFailure != null || !epochInitialized) {
            return;
        }
        if (sweepCursor.isPassInFlight()) {
            metrics.recordSkippedBusyScan();
            plugin.getLogger().log(
                plugin.getConfigs().isDebug() ? Level.INFO : Level.FINE,
                "Skipping ItemGuard inventory scan tick: chunk container sweep still in flight"
            );
            return;
        }

        long startedNanos = System.nanoTime();
        try {
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
        } finally {
            metrics.recordScan(System.nanoTime() - startedNanos);
        }
    }

    private void startSweepPass(long scanEpoch) {
        List<Chunk> loadedChunks = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            loadedChunks.addAll(List.of(world.getLoadedChunks()));
        }
        sweepCursor = newSweepCursor();
        sweepCursor.startPass(loadedChunks, scanEpoch);
        metrics.setSweepInFlight(true);
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
        long startedNanos = System.nanoTime();
        ChunkSweepCursor.SweepBatch<Chunk> batch = sweepCursor.advance(Chunk::isLoaded);
        for (Chunk chunk : batch.visited()) {
            for (BlockState state : chunk.getTileEntities()) {
                plugin.getTrackingService().recordLoadedContainerObservations(
                    state, batch.epochId()
                );
            }
        }
        if (batch.passComplete()) {
            metrics.recordSweepPass(System.nanoTime() - startedNanos);
            finalizeEpoch(batch.epochId());
        }
    }

    private void finalizeEpoch(long scanEpoch) {
        metrics.recordEpoch();
        epochFinalizer.complete(
            scanEpoch,
            plugin.getConfigs().isAntiDupeEnabled(),
            plugin.getConfigs().getAntiDupeAction(),
            plugin.getConfigs().getDetectionCooldown()
        );
    }

    private void reportFindings(List<DuplicateFinding> findings) {
        for (DuplicateFinding finding : findings) {
            metrics.recordFinding();
            plugin.getLogger().warning(
                "ITEMGUARD_DUPLICATE_CONFIRMED code=" + finding.code()
                    + " uuid=" + finding.itemUuid()
                    + " epoch=" + finding.scanEpoch()
                    + " locations=" + finding.distinctLocations()
                    + " action=" + finding.action()
            );
            // Discord is its own channel with its own switch (`discord.enabled`): a server that
            // wants the alert in a staff channel but not in chat gets exactly that.
            plugin.getDiscordWebhook().sendDuplicateFinding(
                finding.code(),
                String.valueOf(finding.itemUuid()),
                finding.distinctLocations(),
                finding.scanEpoch()
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
            int alerted = 0;
            for (Player player : Bukkit.getOnlinePlayers()) {
                // `itemguard.notify` and not `itemguard.bypass`: bypass means "skip the checks", and
                // gating alerts on it meant a staff member trusted to see duplicates but not to skip
                // checks never received one. One node, both editions, declared in plugin.yml.
                if (player.hasPermission("itemguard.notify")) {
                    player.sendMessage(alert);
                    player.sendMessage(locations);
                    alerted++;
                }
            }
            metrics.recordAlert(alerted);
        }
    }
}
