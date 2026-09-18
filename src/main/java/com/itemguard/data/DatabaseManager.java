package com.itemguard.data;

import com.itemguard.ItemGuard;
import com.itemguard.catalog.CatalogRepositoryPort;
import com.itemguard.catalog.CatalogRepository;
import com.itemguard.catalog.MySqlCatalogRepository;
import com.itemguard.persistence.DatabaseBackend;
import com.itemguard.persistence.DatabaseBackendPolicy;
import com.itemguard.persistence.ItemSqliteRepository;
import com.itemguard.persistence.JdbcConnectionOwner;
import com.itemguard.persistence.MySqlConnectionOwner;
import com.itemguard.multiserver.ServerIdentity;
import com.itemguard.persistence.SqliteConnectionOwner;
import com.itemguard.dupe.ItemObservation;
import com.itemguard.dupe.DuplicateAction;
import com.itemguard.dupe.DuplicateFinding;
import com.itemguard.search.ItemSearchRequest;
import com.itemguard.search.SearchRequestStore;
import com.itemguard.snapshot.ItemSnapshot;
import com.itemguard.reclaim.ReclaimClaim;
import com.itemguard.reclaim.ReclaimClaimState;
import com.itemguard.reclaim.ReclaimClaimStore;
import com.itemguard.tracking.TagPublication;
import com.itemguard.tracking.TagReconciliationReceipt;
import com.itemguard.tracking.TagPublicationStore;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public final class DatabaseManager implements
    SearchRequestStore,
    ReclaimClaimStore,
    TagPublicationStore {

    private static final String KEY_NAMESPACE = "itemguard";
    private static final String KEY_ITEM_CODE = "item_code";
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(10);

    private final ItemGuard plugin;
    private final JdbcConnectionOwner connectionOwner;
    private final ItemSqliteRepository repository;
    private final CatalogRepositoryPort catalog;
    private final String serverId;

    public DatabaseManager(ItemGuard plugin) {
        this.plugin = plugin;
        DatabaseBackendPolicy backendPolicy = new DatabaseBackendPolicy();
        DatabaseBackend backend = backendPolicy.requireSupported(plugin.getConfigs().getDatabaseType());
        plugin.getDataFolder().mkdirs();
        if (backend == DatabaseBackend.SQLITE) {
            this.serverId = null;
            File databaseFile = new File(
                plugin.getDataFolder(),
                plugin.getConfigs().getSqliteFileName()
            );
            SqliteConnectionOwner sqlite = new SqliteConnectionOwner(
                databaseFile.toPath(),
                failure -> plugin.getLogger().log(
                    Level.SEVERE,
                    "ItemGuard database operation failed",
                    failure
                )
            );
            this.connectionOwner = sqlite;
            this.repository = new ItemSqliteRepository(sqlite);
            this.catalog = new CatalogRepository(sqlite);
            plugin.getLogger().info("SQLite database initialized: " + databaseFile.getName());
        } else {
            String serverId = plugin.getConfigs().getServerId();
            if (serverId == null || serverId.isBlank()) {
                throw new IllegalArgumentException(
                    "multi-server.server-id must be configured before enabling MySQL");
            }
            serverId = ServerIdentity.configured(serverId).name();
            this.serverId = serverId;
            MySqlConnectionOwner mysql = new MySqlConnectionOwner(
                MySqlConnectionOwner.hikariDataSource(
                    plugin.getConfigs().getMySqlUrl(),
                    plugin.getConfigs().getMySqlUser(),
                    plugin.getConfigs().getMySqlPassword(),
                    plugin.getConfigs().getPoolMaxSize(),
                    plugin.getConfigs().getPoolMinIdle(),
                    plugin.getConfigs().getPoolConnectionTimeout()),
                plugin.getConfigs().getPoolMaxSize(),
                failure -> plugin.getLogger().log(
                    Level.SEVERE,
                    "ItemGuard MySQL operation failed",
                    failure
                )
            );
            this.connectionOwner = mysql;
            this.repository = new ItemSqliteRepository(mysql, serverId);
            this.catalog = new MySqlCatalogRepository(mysql);
            plugin.getLogger().info("MySQL database initialized for server-id " + serverId);
        }
        int recoveredClaims = repository.recoverPendingReclaimClaims(
            System.currentTimeMillis(),
            "STARTUP_RECOVERY"
        );
        if (recoveredClaims > 0) {
            plugin.getLogger().warning(
                "Denied " + recoveredClaims
                    + " pending reclaim claim(s) left by an earlier shutdown"
            );
        }
    }

    public CatalogRepositoryPort getCatalog() { return catalog; }

    public String getServerId() { return serverId; }

    /**
     * The connection and the thread that owns it.
     *
     * <p>Exposed so a collaborator can run its own statements on the executor this manager already
     * owns, rather than opening a second connection to a file SQLite locks per process.
     */
    public JdbcConnectionOwner getConnectionOwner() { return connectionOwner; }

    public void saveItem(ItemData item) {
        repository.saveItem(item);
    }

    public void saveItemWithSnapshot(
        ItemData item,
        ItemSnapshot snapshot,
        long capturedAt
    ) {
        repository.saveItemWithSnapshot(item, snapshot, capturedAt);
    }

    public Optional<ItemSnapshot> getSnapshot(String code) {
        return repository.getSnapshot(code);
    }

    @Override
    public CompletableFuture<TagPublication> reserve(TagPublication proposed) {
        return repository.reserve(proposed);
    }

    @Override
    public CompletableFuture<Boolean> publish(UUID publicationId, long updatedAt) {
        return repository.publish(publicationId, updatedAt);
    }

    @Override
    public CompletableFuture<Boolean> reconcile(
        TagReconciliationReceipt receipt,
        long updatedAt
    ) {
        return repository.reconcile(receipt, updatedAt);
    }

    @Override
    public CompletableFuture<Boolean> abort(
        UUID publicationId,
        long updatedAt,
        String detail
    ) {
        return repository.abort(publicationId, updatedAt, detail);
    }

    @Override
    public boolean beginReclaimClaim(ReclaimClaim claim) {
        return repository.beginReclaimClaim(claim);
    }

    @Override
    public boolean transitionReclaimClaim(
        UUID claimId,
        ReclaimClaimState expectedState,
        ReclaimClaimState targetState,
        long updatedAt,
        String detail
    ) {
        return repository.transitionReclaimClaim(
            claimId,
            expectedState,
            targetState,
            updatedAt,
            detail
        );
    }

    @Override
    public Optional<ReclaimClaim> getReclaimClaim(UUID claimId) {
        return repository.getReclaimClaim(claimId);
    }

    public void updateItemLastAction(
        String code,
        String action,
        Location location,
        String ownerName,
        UUID ownerUuid
    ) {
        repository.updateLastAction(
            code,
            action,
            formatLocation(location),
            ownerName,
            ownerUuid,
            System.currentTimeMillis()
        );
    }

    public void updateItemLocationOnly(
        String code,
        Location location,
        String ownerName,
        UUID ownerUuid
    ) {
        repository.updateLocation(
            code,
            formatLocation(location),
            ownerName,
            ownerUuid,
            System.currentTimeMillis()
        );
    }

    public void logHistory(ItemHistory history) {
        repository.logHistory(history);
    }

    /**
     * Refreshes the most recent matching row instead of appending a duplicate.
     *
     * <p>Used when the same holder repeats the same action on the same item inside the suppression
     * window. The event is still reflected — its timestamp moves forward and a repeat counter rises —
     * so nothing is lost, but a held drop key no longer grows the table without bound.
     */
    public void touchHistory(ItemHistory history) {
        repository.touchHistory(history);
    }

    public void incrementDuplicateCount() {
        repository.incrementDuplicateCount(System.currentTimeMillis());
    }

    public void recordObservation(ItemObservation observation) {
        repository.recordObservation(observation);
    }

    public void completeObservationEpoch(long scanEpoch) {
        repository.completeObservationEpoch(scanEpoch);
    }

    public CompletableFuture<List<DuplicateFinding>> completeObservationEpochAndAudit(
        long scanEpoch,
        boolean antiDupeEnabled,
        DuplicateAction action,
        long detectionCooldownMillis,
        long completedAt
    ) {
        return repository.completeObservationEpochAndAudit(
            scanEpoch,
            antiDupeEnabled,
            action,
            detectionCooldownMillis,
            completedAt
        );
    }

    public long getMaximumPersistedObservationEpoch() {
        return repository.getMaximumPersistedObservationEpoch();
    }

    public CompletableFuture<Long> getMaximumPersistedObservationEpochAsync() {
        return repository.getMaximumPersistedObservationEpochAsync();
    }

    public Optional<ItemData> getItem(String code) {
        return repository.getItem(code);
    }

    public CompletableFuture<Optional<ItemData>> getItemAsync(String code) {
        return repository.getItemAsync(code);
    }

    public Optional<ItemData> getItemByUuid(UUID itemUuid) {
        return repository.getItemByUuid(itemUuid);
    }

    /**
     * Asynchronous read by item UUID. The public API needs this: it exposed only the blocking form, so
     * a plugin calling it on the server thread had nothing else to use (IG-R022).
     */
    public CompletableFuture<Optional<ItemData>> getItemByUuidAsync(UUID itemUuid) {
        return repository.getItemByUuidAsync(itemUuid);
    }

    public List<ItemHistory> getHistory(String code, int limit) {
        return repository.getHistory(code, limit);
    }

    public CompletableFuture<List<ItemHistory>> getHistoryAsync(String code, int limit) {
        return repository.getHistoryAsync(code, limit);
    }

    public CompletableFuture<List<ItemHistory>> getHistoryByPlayerAsync(
        UUID playerUuid,
        int limit
    ) {
        return repository.getHistoryByPlayerAsync(playerUuid, limit);
    }

    public CompletableFuture<List<ItemHistorySummary>> getHistorySummariesByPlayerAsync(
        UUID playerUuid,
        int limit,
        int offset
    ) {
        return repository.getHistorySummariesByPlayerAsync(playerUuid, limit, offset);
    }

    public List<ItemData> getItemsByPlayer(UUID playerUuid) {
        return repository.getItemsByPlayer(playerUuid);
    }

    public CompletableFuture<List<ItemData>> getItemsByPlayerAsync(UUID playerUuid) {
        return repository.getItemsByPlayerAsync(playerUuid);
    }

    public List<ItemData> searchItems(String query) {
        return repository.searchItems(query);
    }

    public CompletableFuture<List<ItemData>> searchItemsAsync(String query) {
        return repository.searchItemsAsync(query);
    }

    @Override
    public boolean startSearchRequest(ItemSearchRequest request) {
        return repository.startSearchRequest(request);
    }

    @Override
    public boolean stopSearchRequest(String code, long updatedAt) {
        return repository.stopSearchRequest(code, updatedAt);
    }

    @Override
    public Optional<ItemSearchRequest> getSearchRequest(String code) {
        return repository.getSearchRequest(code);
    }

    @Override
    public List<ItemSearchRequest> listActiveSearchRequests(
        long now,
        int offset,
        int limit
    ) {
        return repository.listActiveSearchRequests(now, offset, limit);
    }

    @Override
    public boolean removeSearchRequest(String code) {
        return repository.removeSearchRequest(code);
    }

    @Override
    public int clearSearchRequests() {
        return repository.clearSearchRequests();
    }

    public int getHistoryCount(String code) {
        return repository.getHistoryCount(code);
    }

    public CompletableFuture<Integer> getHistoryCountAsync(String code) {
        return repository.getHistoryCountAsync(code);
    }

    public PluginStats getStats() {
        return repository.getStats();
    }

    public CompletableFuture<PluginStats> getStatsAsync() {
        return repository.getStatsAsync();
    }

    public int getOnlineTrackedCount() {
        int count = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            for (ItemStack item : player.getInventory().getContents()) {
                if (item != null && item.getType() != Material.AIR) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    public void deleteOldHistory(int keepDays) {
        if (keepDays < 0) {
            throw new IllegalArgumentException("History retention days cannot be negative");
        }
        long cutoff = System.currentTimeMillis() - Math.multiplyExact(keepDays, 86_400_000L);
        int deleted = repository.deleteHistoryBefore(cutoff);
        plugin.getLogger().info(
            "Deleted " + deleted + " old history entries (older than " + keepDays + " days)"
        );
    }

    public CompletableFuture<Integer> deleteOldHistoryAsync(int keepDays) {
        if (keepDays < 0) {
            throw new IllegalArgumentException("History retention days cannot be negative");
        }
        long cutoff = System.currentTimeMillis() - Math.multiplyExact(keepDays, 86_400_000L);
        return repository.deleteHistoryBeforeAsync(cutoff);
    }

    public void flush() {
        connectionOwner.flush();
    }

    public void close() {
        if (!connectionOwner.close(CLOSE_TIMEOUT)) {
            plugin.getLogger().severe(
                "ItemGuard database did not close cleanly within " + CLOSE_TIMEOUT.toSeconds() + " seconds"
            );
            return;
        }
        plugin.getLogger().info("Database connection closed");
    }

    private String formatLocation(Location location) {
        if (location == null || location.getWorld() == null) {
            return "Unknown";
        }
        return String.format(
            "%s (%d, %d, %d)",
            location.getWorld().getName(),
            location.getBlockX(),
            location.getBlockY(),
            location.getBlockZ()
        );
    }

    public static String getKeyNamespace() {
        return KEY_NAMESPACE;
    }

    public static String getKeyItemCode() {
        return KEY_ITEM_CODE;
    }
}