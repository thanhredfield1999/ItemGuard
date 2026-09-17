package com.itemguard.persistence;

import com.itemguard.data.ItemData;
import com.itemguard.data.ItemHistory;
import com.itemguard.data.ItemHistorySummary;
import com.itemguard.data.PluginStats;
import com.itemguard.dupe.DuplicateAssessment;
import com.itemguard.dupe.DuplicateAction;
import com.itemguard.dupe.DuplicateDetector;
import com.itemguard.dupe.DuplicateFinding;
import com.itemguard.dupe.DuplicateStatus;
import com.itemguard.dupe.HolderType;
import com.itemguard.dupe.ItemObservation;
import com.itemguard.dupe.ObservationKey;
import com.itemguard.search.ItemSearchMode;
import com.itemguard.search.ItemSearchRequest;
import com.itemguard.search.ItemSearchState;
import com.itemguard.search.SearchRequestStore;
import com.itemguard.snapshot.ItemSnapshot;
import com.itemguard.tracking.TagPublication;
import com.itemguard.tracking.TagIdentityCollisionException;
import com.itemguard.tracking.TagReconciliationReceipt;
import com.itemguard.tracking.TagPublicationState;
import com.itemguard.tracking.TagPublicationStore;
import com.itemguard.reclaim.ReclaimClaim;
import com.itemguard.reclaim.ReclaimClaimState;
import com.itemguard.reclaim.ReclaimClaimStore;
import org.bukkit.Material;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class ItemSqliteRepository implements
    SearchRequestStore,
    ReclaimClaimStore,
    TagPublicationStore {

    private static final int MAX_DUPLICATE_REPORTS_PER_EPOCH = 64;

    private final SqliteConnectionOwner owner;
    private final DuplicateDetector duplicateDetector = new DuplicateDetector();

    public ItemSqliteRepository(SqliteConnectionOwner owner) {
        this.owner = owner;
    }

    public void saveItem(ItemData item) {
        owner.execute(connection -> {
            upsertItem(connection, item);
            return null;
        });
    }

    public void saveItemWithSnapshot(
        ItemData item,
        ItemSnapshot snapshot,
        long capturedAt
    ) {
        owner.call(connection -> {
            upsertItem(connection, item);
            upsertSnapshot(connection, item.getCode(), snapshot, capturedAt);
            return null;
        });
    }

    public void prepareTagPublication(TagPublication publication) {
        owner.call(connection -> {
            insertTagPublication(connection, publication);
            return null;
        });
    }

    public Optional<TagPublication> getTagPublication(UUID publicationId) {
        return owner.call(connection -> findTagPublication(connection, publicationId));
    }

    public boolean publishTagPublication(UUID publicationId, long updatedAt) {
        return owner.call(connection -> publishTagPublication(
            connection,
            publicationId,
            updatedAt
        ));
    }

    public boolean abortTagPublication(
        UUID publicationId,
        long updatedAt,
        String detail
    ) {
        return owner.call(connection -> abortTagPublication(
            connection,
            publicationId,
            updatedAt,
            detail
        ));
    }

    @Override
    public CompletableFuture<TagPublication> reserve(TagPublication proposed) {
        return owner.callAsync(connection -> reserveTagPublication(
            connection,
            proposed
        ));
    }

    @Override
    public CompletableFuture<Boolean> publish(UUID publicationId, long updatedAt) {
        return owner.callAsync(connection -> publishTagPublication(
            connection,
            publicationId,
            updatedAt
        ));
    }

    @Override
    public CompletableFuture<Boolean> reconcile(
        TagReconciliationReceipt receipt,
        long updatedAt
    ) {
        return owner.callAsync(connection -> reconcileTagPublication(
            connection,
            receipt,
            updatedAt
        ));
    }

    @Override
    public CompletableFuture<Boolean> abort(
        UUID publicationId,
        long updatedAt,
        String detail
    ) {
        return owner.callAsync(connection -> abortTagPublication(
            connection,
            publicationId,
            updatedAt,
            detail
        ));
    }

    public void updateLastAction(
        String code,
        String action,
        String location,
        String ownerName,
        UUID ownerUuid,
        long observedAt
    ) {
        owner.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE tracked_items
                SET last_seen_at = ?, last_action = ?, last_location = ?,
                    owner_name = ?, owner_uuid = ?
                WHERE code = ?
                """)) {
                statement.setLong(1, observedAt);
                statement.setString(2, action);
                statement.setString(3, location);
                statement.setString(4, ownerName);
                statement.setString(5, ownerUuid != null ? ownerUuid.toString() : null);
                statement.setString(6, code);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public void updateLocation(
        String code,
        String location,
        String ownerName,
        UUID ownerUuid,
        long observedAt
    ) {
        owner.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE tracked_items
                SET last_seen_at = ?, last_location = ?, owner_name = ?, owner_uuid = ?
                WHERE code = ?
                """)) {
                statement.setLong(1, observedAt);
                statement.setString(2, location);
                statement.setString(3, ownerName);
                statement.setString(4, ownerUuid != null ? ownerUuid.toString() : null);
                statement.setString(5, code);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public void logHistory(ItemHistory history) {
        owner.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO item_history
                (code, item_uuid, action, player_name, player_uuid, location,
                 world, x, y, z, timestamp, additional_data)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
                statement.setString(1, history.getCode());
                statement.setString(2, history.getItemUuid().toString());
                statement.setString(3, history.getAction());
                statement.setString(4, history.getPlayerName());
                statement.setString(5,
                    history.getPlayerUuid() != null ? history.getPlayerUuid().toString() : null);
                statement.setString(6, history.getLocation());
                statement.setString(7, history.getWorld());
                statement.setInt(8, history.getX());
                statement.setInt(9, history.getY());
                statement.setInt(10, history.getZ());
                statement.setLong(11, history.getTimestamp());
                statement.setString(12, history.getAdditionalData());
                statement.executeUpdate();
            }
            return null;
        });
    }

    /**
     * Folds a repeated event into the row that already records it.
     *
     * <p>Moves that row's timestamp forward and raises a repeat counter stored in
     * {@code additional_data}, so the behaviour stays auditable without appending a row per keypress.
     * Deliberately matches on identity, actor and action, and only touches the newest such row.
     *
     * <p>No schema change: the counter lives in the existing free-form column, so a database written
     * by an older build still opens.
     */
    public void touchHistory(ItemHistory history) {
        if (history == null || history.getItemUuid() == null || history.getPlayerUuid() == null) {
            return;
        }
        owner.execute(connection -> {
            long id = -1L;
            int repeats = 1;
            try (PreparedStatement select = connection.prepareStatement("""
                SELECT id, additional_data FROM item_history
                WHERE item_uuid = ? AND player_uuid = ? AND action = ?
                ORDER BY timestamp DESC, id DESC LIMIT 1
                """)) {
                select.setString(1, history.getItemUuid().toString());
                select.setString(2, history.getPlayerUuid().toString());
                select.setString(3, history.getAction());
                try (var rows = select.executeQuery()) {
                    if (rows.next()) {
                        id = rows.getLong(1);
                        repeats = parseRepeats(rows.getString(2)) + 1;
                    }
                }
            }
            if (id < 0) {
                // Nothing to fold into: record it rather than lose it.
                return null;
            }
            try (PreparedStatement update = connection.prepareStatement("""
                UPDATE item_history
                SET timestamp = ?, location = ?, world = ?, x = ?, y = ?, z = ?, additional_data = ?
                WHERE id = ?
                """)) {
                update.setLong(1, history.getTimestamp());
                update.setString(2, history.getLocation());
                update.setString(3, history.getWorld());
                update.setInt(4, history.getX());
                update.setInt(5, history.getY());
                update.setInt(6, history.getZ());
                update.setString(7, REPEAT_PREFIX + repeats);
                update.setLong(8, id);
                update.executeUpdate();
            }
            return null;
        });
    }

    /** Marker for the repeat counter kept in {@code additional_data}. */
    private static final String REPEAT_PREFIX = "repeats=";

    static int parseRepeats(String additionalData) {
        if (additionalData == null || !additionalData.startsWith(REPEAT_PREFIX)) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(additionalData.substring(REPEAT_PREFIX.length()).trim()));
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    public void incrementDuplicateCount(long updatedAt) {
        owner.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE plugin_stats
                SET duplicates_detected = duplicates_detected + 1, last_updated = ?
                WHERE id = 1
                """)) {
                statement.setLong(1, updatedAt);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public void recordObservation(ItemObservation observation) {
        owner.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO item_observations
                (item_uuid, code, scan_epoch, epoch_complete, holder_type,
                 holder_id, slot, observed_at)
                VALUES (?, ?, ?, 0, ?, ?, ?, ?)
                ON CONFLICT(scan_epoch, holder_type, holder_id, slot, item_uuid)
                DO UPDATE SET code = excluded.code, observed_at = excluded.observed_at
                """)) {
                statement.setString(1, observation.itemUuid().toString());
                statement.setString(2, observation.code());
                statement.setLong(3, observation.scanEpoch());
                statement.setString(4, observation.key().holderType().name());
                statement.setString(5, observation.key().holderId());
                statement.setInt(6, observation.key().slot());
                statement.setLong(7, observation.observedAt());
                statement.executeUpdate();
            }
            markSearchRequestFound(
                connection,
                observation.code(),
                observation.observedAt()
            );
            return null;
        });
    }

    public void completeObservationEpoch(long scanEpoch) {
        owner.execute(connection -> {
            markObservationEpochComplete(connection, scanEpoch);
            deleteOlderObservationEpochs(connection, scanEpoch);
            return null;
        });
    }

    public CompletableFuture<List<DuplicateFinding>> completeObservationEpochAndAudit(
        long scanEpoch,
        boolean antiDupeEnabled,
        DuplicateAction action,
        long detectionCooldownMillis,
        long createdAt
    ) {
        return owner.callAsync(connection -> {
            markObservationEpochComplete(connection, scanEpoch);
            DuplicateInsertResult created = antiDupeEnabled
                ? insertConfirmedDuplicateFindings(
                    connection,
                    scanEpoch,
                    action,
                    Math.max(0L, detectionCooldownMillis),
                    createdAt
                )
                : new DuplicateInsertResult(0, List.of());
            incrementDuplicateCount(connection, created.inserted(), createdAt);
            deleteOlderObservationEpochs(connection, scanEpoch);
            return created.reports();
        });
    }

    public int countDuplicateFindings(UUID itemUuid, long scanEpoch) {
        return owner.call(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) FROM duplicate_findings
                WHERE item_uuid = ? AND scan_epoch = ?
                """)) {
                statement.setString(1, itemUuid.toString());
                statement.setLong(2, scanEpoch);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    return result.getInt(1);
                }
            }
        });
    }

    /**
     * Number of distinct item identities in {@code duplicate_findings}. One duplicated item is
     * re-detected on every scan epoch and stores one row per epoch, so the row count alone reads as
     * that many duplicated items.
     */
    public int countDistinctDuplicateItems() {
        return owner.call(connection -> countDistinctDuplicateItems(connection));
    }

    public int countDuplicateFindingsForEpoch(long scanEpoch) {
        return owner.call(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) FROM duplicate_findings WHERE scan_epoch = ?
                """)) {
                statement.setLong(1, scanEpoch);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    return result.getInt(1);
                }
            }
        });
    }

    public long getMaximumPersistedObservationEpoch() {
        return owner.call(connection -> {
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("""
                     SELECT MAX(scan_epoch) FROM (
                         SELECT scan_epoch FROM item_observations
                         UNION ALL
                         SELECT scan_epoch FROM duplicate_findings
                     )
                     """)) {
                if (!result.next()) {
                    return Long.MIN_VALUE;
                }
                long maximum = result.getLong(1);
                return result.wasNull() ? Long.MIN_VALUE : maximum;
            }
        });
    }

    public int countObservations(UUID itemUuid, long scanEpoch) {
        return owner.call(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) FROM item_observations
                WHERE item_uuid = ? AND scan_epoch = ?
                """)) {
                statement.setString(1, itemUuid.toString());
                statement.setLong(2, scanEpoch);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    return result.getInt(1);
                }
            }
        });
    }

    public DuplicateAssessment assessDuplicate(UUID itemUuid, long scanEpoch) {
        return owner.call(connection -> {
            List<ItemObservation> observations = new ArrayList<>();
            boolean epochComplete = true;
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT code, epoch_complete, holder_type, holder_id, slot, observed_at
                FROM item_observations
                WHERE item_uuid = ? AND scan_epoch = ?
                """)) {
                statement.setString(1, itemUuid.toString());
                statement.setLong(2, scanEpoch);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        epochComplete &= result.getInt("epoch_complete") == 1;
                        observations.add(new ItemObservation(
                            itemUuid,
                            result.getString("code"),
                            scanEpoch,
                            new ObservationKey(
                                HolderType.valueOf(result.getString("holder_type")),
                                result.getString("holder_id"),
                                result.getInt("slot")
                            ),
                            result.getLong("observed_at")
                        ));
                    }
                }
            }
            if (observations.isEmpty()) {
                epochComplete = false;
            }
            return duplicateDetector.assess(
                itemUuid,
                scanEpoch,
                epochComplete,
                observations
            );
        });
    }

    public boolean startSearchRequest(ItemSearchRequest request) {
        return owner.call(connection -> {
            try (PreparedStatement expire = connection.prepareStatement("""
                UPDATE item_search_requests
                SET state = 'EXPIRED', updated_at = ?
                WHERE code = ? AND state = 'ACTIVE' AND expires_at <= ?
                """)) {
                expire.setLong(1, request.createdAt());
                expire.setString(2, request.code());
                expire.setLong(3, request.createdAt());
                expire.executeUpdate();
            }

            try (PreparedStatement existing = connection.prepareStatement("""
                SELECT state FROM item_search_requests WHERE code = ?
                """)) {
                existing.setString(1, request.code());
                try (ResultSet result = existing.executeQuery()) {
                    if (result.next()
                        && ItemSearchState.ACTIVE.name().equals(result.getString("state"))) {
                        return false;
                    }
                }
            }

            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO item_search_requests
                (code, mode, state, actor_uuid, actor_name,
                 created_at, expires_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(code) DO UPDATE SET
                    mode = excluded.mode,
                    state = excluded.state,
                    actor_uuid = excluded.actor_uuid,
                    actor_name = excluded.actor_name,
                    created_at = excluded.created_at,
                    expires_at = excluded.expires_at,
                    updated_at = excluded.updated_at
                """)) {
                bindSearchRequest(statement, request);
                return statement.executeUpdate() == 1;
            }
        });
    }

    public boolean stopSearchRequest(String code, long updatedAt) {
        return owner.call(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE item_search_requests
                SET state = 'STOPPED', updated_at = ?
                WHERE code = ? AND state = 'ACTIVE'
                """)) {
                statement.setLong(1, updatedAt);
                statement.setString(2, normalizeCode(code));
                return statement.executeUpdate() == 1;
            }
        });
    }

    public boolean markSearchRequestFound(String code, long updatedAt) {
        return owner.call(connection -> markSearchRequestFound(
            connection,
            code,
            updatedAt
        ));
    }

    public Optional<ItemSearchRequest> getSearchRequest(String code) {
        return owner.call(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM item_search_requests WHERE code = ?
                """)) {
                statement.setString(1, normalizeCode(code));
                try (ResultSet result = statement.executeQuery()) {
                    return result.next()
                        ? Optional.of(parseSearchRequest(result))
                        : Optional.empty();
                }
            }
        });
    }

    public List<ItemSearchRequest> listActiveSearchRequests(
        long now,
        int offset,
        int limit
    ) {
        int boundedOffset = Math.max(0, offset);
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        return owner.call(connection -> {
            try (PreparedStatement expire = connection.prepareStatement("""
                UPDATE item_search_requests
                SET state = 'EXPIRED', updated_at = ?
                WHERE state = 'ACTIVE' AND expires_at <= ?
                """)) {
                expire.setLong(1, now);
                expire.setLong(2, now);
                expire.executeUpdate();
            }

            List<ItemSearchRequest> requests = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM item_search_requests
                WHERE state = 'ACTIVE'
                ORDER BY created_at ASC, code ASC
                LIMIT ? OFFSET ?
                """)) {
                statement.setInt(1, boundedLimit);
                statement.setInt(2, boundedOffset);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        requests.add(parseSearchRequest(result));
                    }
                }
            }
            return List.copyOf(requests);
        });
    }

    public boolean removeSearchRequest(String code) {
        return owner.call(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM item_search_requests WHERE code = ?")) {
                statement.setString(1, normalizeCode(code));
                return statement.executeUpdate() == 1;
            }
        });
    }

    public int clearSearchRequests() {
        return owner.call(connection -> {
            try (Statement statement = connection.createStatement()) {
                return statement.executeUpdate("DELETE FROM item_search_requests");
            }
        });
    }

    public void saveSnapshot(String code, ItemSnapshot snapshot, long capturedAt) {
        owner.call(connection -> {
            String normalizedCode = normalizeCode(code);
            try (PreparedStatement tracked = connection.prepareStatement(
                "SELECT 1 FROM tracked_items WHERE code = ?")) {
                tracked.setString(1, normalizedCode);
                try (ResultSet result = tracked.executeQuery()) {
                    if (!result.next()) {
                        throw new SQLException(
                            "Cannot save snapshot for untracked ItemGuard code: "
                                + normalizedCode
                        );
                    }
                }
            }

            upsertSnapshot(connection, normalizedCode, snapshot, capturedAt);
            return null;
        });
    }

    public Optional<ItemSnapshot> getSnapshot(String code) {
        return owner.call(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT snapshot_version, payload, sha256
                FROM item_snapshots
                WHERE code = ?
                """)) {
                statement.setString(1, normalizeCode(code));
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new ItemSnapshot(
                        result.getInt("snapshot_version"),
                        result.getBytes("payload"),
                        result.getBytes("sha256")
                    ));
                }
            }
        });
    }

    public boolean beginReclaimClaim(ReclaimClaim claim) {
        return owner.call(connection -> {
            try (PreparedStatement tracked = connection.prepareStatement(
                "SELECT 1 FROM tracked_items WHERE code = ?")) {
                tracked.setString(1, normalizeCode(claim.code()));
                try (ResultSet result = tracked.executeQuery()) {
                    if (!result.next()) {
                        throw new SQLException(
                            "Cannot begin reclaim claim for untracked ItemGuard code: "
                                + claim.code()
                        );
                    }
                }
            }

            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT OR IGNORE INTO reclaim_claims
                (claim_id, idempotency_key, player_uuid, code, state,
                 requested_at, updated_at, detail)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
                bindReclaimClaim(statement, claim);
                return statement.executeUpdate() == 1;
            }
        });
    }

    public boolean transitionReclaimClaim(
        UUID claimId,
        ReclaimClaimState expectedState,
        ReclaimClaimState targetState,
        long updatedAt,
        String detail
    ) {
        if (!isAllowedReclaimTransition(expectedState, targetState)) {
            throw new IllegalArgumentException(
                "Invalid reclaim claim transition: "
                    + expectedState + " -> " + targetState
            );
        }
        return owner.call(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE reclaim_claims
                SET state = ?, updated_at = ?, detail = ?
                WHERE claim_id = ? AND state = ? AND updated_at <= ?
                """)) {
                statement.setString(1, targetState.name());
                statement.setLong(2, updatedAt);
                statement.setString(3, detail);
                statement.setString(4, claimId.toString());
                statement.setString(5, expectedState.name());
                statement.setLong(6, updatedAt);
                return statement.executeUpdate() == 1;
            }
        });
    }

    private boolean isAllowedReclaimTransition(
        ReclaimClaimState expectedState,
        ReclaimClaimState targetState
    ) {
        return switch (expectedState) {
            case PENDING -> targetState == ReclaimClaimState.PREPARED
                || targetState == ReclaimClaimState.DENIED;
            case PREPARED -> targetState == ReclaimClaimState.COMMITTED
                || targetState == ReclaimClaimState.DENIED;
            case COMMITTED, DENIED -> false;
        };
    }

    public Optional<ReclaimClaim> getReclaimClaim(UUID claimId) {
        return owner.call(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM reclaim_claims WHERE claim_id = ?")) {
                statement.setString(1, claimId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    return result.next()
                        ? Optional.of(parseReclaimClaim(result))
                        : Optional.empty();
                }
            }
        });
    }

    public int recoverPendingReclaimClaims(long updatedAt, String detail) {
        return owner.call(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE reclaim_claims
                SET state = 'DENIED', updated_at = ?, detail = ?
                WHERE state = 'PENDING' AND updated_at <= ?
                """)) {
                statement.setLong(1, updatedAt);
                statement.setString(2, detail);
                statement.setLong(3, updatedAt);
                return statement.executeUpdate();
            }
        });
    }

    public Optional<ItemData> getItem(String code) {
        return owner.call(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM tracked_items WHERE code = ?")) {
                statement.setString(1, code);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(parseItem(result)) : Optional.empty();
                }
            }
        });
    }

    public CompletableFuture<Optional<ItemData>> getItemAsync(String code) {
        Objects.requireNonNull(code, "code");
        return owner.callAsync(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM tracked_items WHERE code = ?")) {
                statement.setString(1, code);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(parseItem(result)) : Optional.empty();
                }
            }
        });
    }

    public Optional<ItemData> getItemByUuid(UUID itemUuid) {
        return owner.call(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM tracked_items WHERE item_uuid = ? LIMIT 1")) {
                statement.setString(1, itemUuid.toString());
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(parseItem(result)) : Optional.empty();
                }
            }
        });
    }

    public List<ItemHistory> getHistory(String code, int limit) {
        int boundedLimit = Math.max(0, Math.min(limit, 1_000));
        if (boundedLimit == 0) {
            return Collections.emptyList();
        }
        return owner.call(connection -> {
            List<ItemHistory> histories = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM item_history
                WHERE code = ?
                ORDER BY timestamp DESC, id DESC
                LIMIT ?
                """)) {
                statement.setString(1, code);
                statement.setInt(2, boundedLimit);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        histories.add(parseHistory(result));
                    }
                }
            }
            return List.copyOf(histories);
        });
    }

    public CompletableFuture<List<ItemHistory>> getHistoryAsync(String code, int limit) {
        Objects.requireNonNull(code, "code");
        int boundedLimit = Math.max(0, Math.min(limit, 1_000));
        if (boundedLimit == 0) {
            return CompletableFuture.completedFuture(List.of());
        }
        return owner.callAsync(connection -> {
            List<ItemHistory> histories = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM item_history
                WHERE code = ?
                ORDER BY timestamp DESC, id DESC
                LIMIT ?
                """)) {
                statement.setString(1, code);
                statement.setInt(2, boundedLimit);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        histories.add(parseHistory(result));
                    }
                }
            }
            return List.copyOf(histories);
        });
    }

    public CompletableFuture<List<ItemHistory>> getHistoryByPlayerAsync(
        UUID playerUuid,
        int limit
    ) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        int boundedLimit = Math.max(0, Math.min(limit, 500));
        if (boundedLimit == 0) {
            return CompletableFuture.completedFuture(List.of());
        }
        return owner.callAsync(connection -> {
            List<ItemHistory> histories = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT history.*
                FROM item_history history
                INNER JOIN tracked_items item ON item.code = history.code
                WHERE item.owner_uuid = ?
                ORDER BY history.timestamp DESC, history.id DESC
                LIMIT ?
                """)) {
                statement.setString(1, playerUuid.toString());
                statement.setInt(2, boundedLimit);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        histories.add(parseHistory(result));
                    }
                }
            }
            return List.copyOf(histories);
        });
    }

    /**
     * Returns one bounded page of tracked identities and totals over every retained row for each
     * identity. This deliberately describes stored history, never present custody.
     */
    public CompletableFuture<List<ItemHistorySummary>> getHistorySummariesByPlayerAsync(
        UUID playerUuid,
        int limit,
        int offset
    ) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        // One extra row is admitted only to determine whether the 45-slot UI has a next page.
        int boundedLimit = Math.max(0, Math.min(limit, 46));
        int boundedOffset = Math.max(0, Math.min(offset, 155));
        if (boundedLimit == 0) {
            return CompletableFuture.completedFuture(List.of());
        }
        return owner.callAsync(connection -> {
            Map<String, SummaryAccumulator> summaries = new LinkedHashMap<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                WITH page AS (
                    SELECT * FROM tracked_items
                    WHERE owner_uuid = ?
                    ORDER BY last_seen_at DESC, code ASC
                    LIMIT ? OFFSET ?
                )
                SELECT page.*, history.action, COUNT(history.id) AS action_count
                FROM page
                INNER JOIN item_history history
                    ON history.code = page.code AND history.item_uuid = page.item_uuid
                GROUP BY page.code, page.item_uuid, history.action
                ORDER BY page.last_seen_at DESC, page.code ASC, history.action ASC
                """)) {
                statement.setString(1, playerUuid.toString());
                statement.setInt(2, boundedLimit);
                statement.setInt(3, boundedOffset);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        String code = result.getString("code");
                        SummaryAccumulator summary = summaries.get(code);
                        if (summary == null) {
                            summary = new SummaryAccumulator(parseItem(result));
                            summaries.put(code, summary);
                        }
                        summary.add(result.getString("action"), result.getInt("action_count"));
                    }
                }
            }
            return summaries.values().stream().map(SummaryAccumulator::toSummary).toList();
        });
    }

    public List<ItemData> getItemsByPlayer(UUID playerUuid) {
        return owner.call(connection -> {
            List<ItemData> items = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM tracked_items
                WHERE owner_uuid = ?
                ORDER BY last_seen_at DESC
                LIMIT 200
                """)) {
                statement.setString(1, playerUuid.toString());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        items.add(parseItem(result));
                    }
                }
            }
            return List.copyOf(items);
        });
    }

    public CompletableFuture<List<ItemData>> getItemsByPlayerAsync(UUID playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        return owner.callAsync(connection -> {
            List<ItemData> items = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM tracked_items
                WHERE owner_uuid = ?
                ORDER BY last_seen_at DESC
                LIMIT 200
                """)) {
                statement.setString(1, playerUuid.toString());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        items.add(parseItem(result));
                    }
                }
            }
            return List.copyOf(items);
        });
    }

    public List<ItemData> searchItems(String query) {
        String normalizedQuery = query == null ? "" : query;
        return owner.call(connection -> {
            List<ItemData> items = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM tracked_items
                WHERE owner_name LIKE ? OR code LIKE ? OR item_name LIKE ?
                ORDER BY last_seen_at DESC
                LIMIT 100
                """)) {
                String like = "%" + normalizedQuery + "%";
                statement.setString(1, like);
                statement.setString(2, like);
                statement.setString(3, like);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        items.add(parseItem(result));
                    }
                }
            }
            return List.copyOf(items);
        });
    }

    public CompletableFuture<List<ItemData>> searchItemsAsync(String query) {
        String normalizedQuery = query == null ? "" : query;
        return owner.callAsync(connection -> {
            List<ItemData> items = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM tracked_items
                WHERE owner_name LIKE ? OR code LIKE ? OR item_name LIKE ?
                ORDER BY last_seen_at DESC
                LIMIT 100
                """)) {
                String like = "%" + normalizedQuery + "%";
                statement.setString(1, like);
                statement.setString(2, like);
                statement.setString(3, like);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        items.add(parseItem(result));
                    }
                }
            }
            return List.copyOf(items);
        });
    }

    public int getHistoryCount(String code) {
        return owner.call(connection -> count(
            connection,
            "SELECT COUNT(*) FROM item_history WHERE code = ?",
            code
        ));
    }

    public PluginStats getStats() {
        return owner.call(connection -> {
            PluginStats stats = new PluginStats();
            stats.setTotalItems(count(connection, "SELECT COUNT(*) FROM tracked_items", null));
            stats.setTotalHistory(count(connection, "SELECT COUNT(*) FROM item_history", null));
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                     "SELECT duplicates_detected FROM plugin_stats WHERE id = 1")) {
                if (result.next()) {
                    stats.setDuplicatesDetected(result.getInt(1));
                }
            }
            stats.setDistinctDuplicateItems(countDistinctDuplicateItems(connection));
            stats.setDatabaseType("SQLITE");
            stats.setDatabaseStatus("OK");
            return stats;
        });
    }

    public CompletableFuture<PluginStats> getStatsAsync() {
        return owner.callAsync(connection -> {
            PluginStats stats = new PluginStats();
            stats.setTotalItems(count(connection, "SELECT COUNT(*) FROM tracked_items", null));
            stats.setTotalHistory(count(connection, "SELECT COUNT(*) FROM item_history", null));
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                     "SELECT duplicates_detected FROM plugin_stats WHERE id = 1")) {
                if (result.next()) {
                    stats.setDuplicatesDetected(result.getInt(1));
                }
            }
            stats.setDistinctDuplicateItems(countDistinctDuplicateItems(connection));
            stats.setDatabaseType("SQLITE");
            stats.setDatabaseStatus("OK");
            return stats;
        });
    }

    private int countDistinctDuplicateItems(Connection connection) throws SQLException {
        return count(connection, "SELECT COUNT(DISTINCT item_uuid) FROM duplicate_findings", null);
    }

    public int deleteHistoryBefore(long cutoff) {
        return owner.call(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM item_history WHERE timestamp < ?")) {
                statement.setLong(1, cutoff);
                return statement.executeUpdate();
            }
        });
    }

    private void validateCanonicalIdentity(Connection connection, ItemData item) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT item_uuid FROM tracked_items WHERE code = ?")) {
            statement.setString(1, item.getCode());
            try (ResultSet result = statement.executeQuery()) {
                if (result.next() && !item.getItemUuid().toString().equals(result.getString(1))) {
                    throw new SQLException(
                        "Refusing to rebind ItemGuard code " + item.getCode() + " to another UUID"
                    );
                }
            }
        }
    }

    private void insertTagPublication(
        Connection connection,
        TagPublication publication
    ) throws SQLException {
        rejectTagIdentityCollision(connection, publication);
        ItemData item = publication.item();
        ItemSnapshot snapshot = publication.snapshot();
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO tag_publications
            (publication_id, source_key, source_digest, code, item_uuid, owner_uuid, owner_name,
             material, item_name, item_lore, created_item_at, last_seen_at,
             last_action, detection_count, last_location, snapshot_version,
             payload, sha256, captured_at, state, created_at, updated_at, detail)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
            statement.setString(1, publication.publicationId().toString());
            statement.setString(2, publication.sourceKey());
            statement.setBytes(3, publication.sourceDigest());
            statement.setString(4, item.getCode());
            statement.setString(5, item.getItemUuid().toString());
            statement.setString(6, item.getOwnerUuid() == null ? null : item.getOwnerUuid().toString());
            statement.setString(7, item.getOwnerName());
            statement.setString(8, item.getMaterial() == null ? null : item.getMaterial().name());
            statement.setString(9, item.getItemName());
            statement.setString(10, item.getItemLore());
            statement.setLong(11, item.getCreatedAt());
            statement.setLong(12, item.getLastSeenAt());
            statement.setString(13, item.getLastAction());
            statement.setInt(14, item.getDetectionCount());
            statement.setString(15, item.getLastLocation());
            statement.setInt(16, snapshot.version());
            statement.setBytes(17, snapshot.payload());
            statement.setBytes(18, snapshot.sha256());
            statement.setLong(19, publication.capturedAt());
            statement.setString(20, publication.state().name());
            statement.setLong(21, publication.createdAt());
            statement.setLong(22, publication.updatedAt());
            statement.setString(23, publication.detail());
            statement.executeUpdate();
        }
    }

    private Optional<TagPublication> findTagPublication(
        Connection connection,
        UUID publicationId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT * FROM tag_publications WHERE publication_id = ?
            """)) {
            statement.setString(1, publicationId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                    ? Optional.of(mapTagPublication(result))
                    : Optional.empty();
            }
        }
    }

    private void markObservationEpochComplete(Connection connection, long scanEpoch)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE item_observations
            SET epoch_complete = 1
            WHERE scan_epoch = ?
            """)) {
            statement.setLong(1, scanEpoch);
            statement.executeUpdate();
        }
    }

    private void deleteOlderObservationEpochs(Connection connection, long scanEpoch)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            DELETE FROM item_observations
            WHERE scan_epoch < ?
            """)) {
            statement.setLong(1, scanEpoch);
            statement.executeUpdate();
        }
    }

    private DuplicateInsertResult insertConfirmedDuplicateFindings(
        Connection connection,
        long scanEpoch,
        DuplicateAction action,
        long detectionCooldownMillis,
        long createdAt
    ) throws SQLException {
        int inserted = 0;
        List<DuplicateFinding> reports = new ArrayList<>(MAX_DUPLICATE_REPORTS_PER_EPOCH);
        long cooldownCutoff = detectionCooldownMillis > createdAt
            ? Long.MIN_VALUE
            : createdAt - detectionCooldownMillis;
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT OR IGNORE INTO duplicate_findings
            (item_uuid, code, scan_epoch, status, distinct_locations,
             action, created_at, detail)
            SELECT observations.item_uuid,
                   observations.code,
                   ?,
                   'CONFIRMED',
                   COUNT(*),
                   ?,
                   ?,
                   'distinct_locations=' || COUNT(*)
            FROM item_observations observations
            INNER JOIN tracked_items tracked
                ON tracked.item_uuid = observations.item_uuid
                AND tracked.code = observations.code
            WHERE observations.scan_epoch = ?
                AND observations.epoch_complete = 1
                AND EXISTS (
                    -- C1 (review 2026-09-16). An epoch spans real time: the player scan happens in
                    -- one tick, but the container sweep takes up to a minute and writes into the
                    -- same epoch. Two locations inside one epoch therefore do not prove two copies
                    -- -- an item carried and then stored produces exactly that. A confirmation now
                    -- also requires the identity to have been at two or more locations in the
                    -- previous epoch, which a moved item cannot be. The previous epoch is still
                    -- present here because older epochs are pruned after this audit runs.
                    SELECT 1
                    FROM item_observations prior
                    WHERE prior.item_uuid = observations.item_uuid
                        AND prior.code = observations.code
                        AND prior.scan_epoch < observations.scan_epoch
                        AND prior.epoch_complete = 1
                    GROUP BY prior.item_uuid, prior.code
                    HAVING COUNT(*) >= 2
                )
                AND NOT EXISTS (
                    -- H5 (review 2026-09-17). The window used to be wall-clock only, and a window is
                    -- only meaningful relative to how far apart two audits actually are: a sweep that
                    -- overruns a cycle, or a server below 20 TPS, makes the gap larger than the
                    -- configured window, so nothing is suppressed and a lowered setting is inert
                    -- again. The second clause closes that: when an admin has asked for a throttle at
                    -- all, an identity already reported in the immediately preceding audit is not
                    -- reported again, however far apart those audits were in real time.
                    --
                    -- It is gated on cooldown > 0 on purpose: `detection-cooldown-ms: 0` means "no
                    -- throttle, re-report every audit", which is a documented behaviour the epoch
                    -- count and the distinct-identity statistic both rely on.
                    SELECT 1 FROM duplicate_findings previous
                    WHERE previous.item_uuid = observations.item_uuid
                        AND (
                            previous.created_at > ?
                            OR (
                                ? > 0
                                AND previous.scan_epoch = (
                                    SELECT MAX(prior_epoch.scan_epoch) FROM item_observations prior_epoch
                                    WHERE prior_epoch.scan_epoch < observations.scan_epoch
                                        AND prior_epoch.epoch_complete = 1
                                )
                            )
                        )
                )
            GROUP BY observations.item_uuid, observations.code
            HAVING COUNT(*) >= 2
            RETURNING item_uuid, code, scan_epoch, status, distinct_locations,
                      action, created_at, detail
            """)) {
            statement.setLong(1, scanEpoch);
            statement.setString(2, action.name());
            statement.setLong(3, createdAt);
            statement.setLong(4, scanEpoch);
            statement.setLong(5, cooldownCutoff);
            statement.setLong(6, detectionCooldownMillis);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    inserted++;
                    if (reports.size() < MAX_DUPLICATE_REPORTS_PER_EPOCH) {
                        reports.add(new DuplicateFinding(
                            UUID.fromString(result.getString("item_uuid")),
                            result.getString("code"),
                            result.getLong("scan_epoch"),
                            DuplicateStatus.valueOf(result.getString("status")),
                            result.getInt("distinct_locations"),
                            DuplicateAction.valueOf(result.getString("action")),
                            result.getLong("created_at"),
                            result.getString("detail")
                        ));
                    }
                }
            }
        }
        return new DuplicateInsertResult(inserted, List.copyOf(reports));
    }

    private record DuplicateInsertResult(int inserted, List<DuplicateFinding> reports) {}

    private void incrementDuplicateCount(
        Connection connection,
        int delta,
        long updatedAt
    ) throws SQLException {
        if (delta <= 0) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE plugin_stats
            SET duplicates_detected = duplicates_detected + ?, last_updated = ?
            WHERE id = 1
            """)) {
            statement.setInt(1, delta);
            statement.setLong(2, updatedAt);
            statement.executeUpdate();
        }
    }

    private TagPublication reserveTagPublication(
        Connection connection,
        TagPublication proposed
    ) throws SQLException {
        Optional<TagPublication> existing = findPreparedTagPublicationBySource(
            connection,
            proposed.sourceKey()
        );
        if (existing.isPresent()) {
            TagPublication prepared = existing.get();
            if (MessageDigest.isEqual(
                prepared.sourceDigest(),
                proposed.sourceDigest()
            )) {
                return prepared;
            }
            updateTagPublicationState(
                connection,
                prepared.publicationId(),
                TagPublicationState.PREPARED,
                TagPublicationState.ABORTED,
                proposed.createdAt(),
                "SOURCE_CHANGED_BEFORE_RESERVE"
            );
        }
        insertTagPublication(connection, proposed);
        return proposed;
    }

    private Optional<TagPublication> findPreparedTagPublicationBySource(
        Connection connection,
        String sourceKey
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT * FROM tag_publications
            WHERE source_key = ? AND state = 'PREPARED'
            """)) {
            statement.setString(1, sourceKey);
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                    ? Optional.of(mapTagPublication(result))
                    : Optional.empty();
            }
        }
    }

    private void rejectTagIdentityCollision(
        Connection connection,
        TagPublication proposed
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT 1 FROM tracked_items WHERE code = ? OR item_uuid = ?
            UNION ALL
            SELECT 1 FROM tag_publications WHERE code = ? OR item_uuid = ?
            LIMIT 1
            """)) {
            // Compare the exact values inserted below. Existing IDs are never normalized/rebound.
            // ABORTED rows deliberately keep their identities reserved by the unique indexes.
            statement.setString(1, proposed.item().getCode());
            statement.setString(2, proposed.item().getItemUuid().toString());
            statement.setString(3, proposed.item().getCode());
            statement.setString(4, proposed.item().getItemUuid().toString());
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    throw new TagIdentityCollisionException();
                }
            }
        }
    }

    private TagPublication mapTagPublication(ResultSet result) throws SQLException {
        ItemData item = new ItemData(
            result.getString("code"),
            UUID.fromString(result.getString("item_uuid"))
        );
        String ownerUuid = result.getString("owner_uuid");
        item.setOwnerUuid(ownerUuid == null ? null : UUID.fromString(ownerUuid));
        item.setOwnerName(result.getString("owner_name"));
        String material = result.getString("material");
        item.setMaterial(material == null ? null : Material.valueOf(material));
        item.setItemName(result.getString("item_name"));
        item.setItemLore(result.getString("item_lore"));
        item.setCreatedAt(result.getLong("created_item_at"));
        item.setLastSeenAt(result.getLong("last_seen_at"));
        item.setLastAction(result.getString("last_action"));
        item.setDetectionCount(result.getInt("detection_count"));
        item.setLastLocation(result.getString("last_location"));
        ItemSnapshot snapshot = new ItemSnapshot(
            result.getInt("snapshot_version"),
            result.getBytes("payload"),
            result.getBytes("sha256")
        );
        return new TagPublication(
            UUID.fromString(result.getString("publication_id")),
            result.getString("source_key"),
            result.getBytes("source_digest"),
            item,
            snapshot,
            result.getLong("captured_at"),
            TagPublicationState.valueOf(result.getString("state")),
            result.getLong("created_at"),
            result.getLong("updated_at"),
            result.getString("detail")
        );
    }

    private boolean publishTagPublication(
        Connection connection,
        UUID publicationId,
        long updatedAt
    ) throws SQLException {
        Optional<TagPublication> found = findTagPublication(connection, publicationId);
        if (found.isEmpty() || found.get().state() != TagPublicationState.PREPARED) {
            return false;
        }
        TagPublication publication = found.get();
        if (updatedAt < publication.updatedAt()) {
            return false;
        }
        upsertItem(connection, publication.item());
        upsertSnapshot(
            connection,
            publication.item().getCode(),
            publication.snapshot(),
            publication.capturedAt()
        );
        boolean transitioned = updateTagPublicationState(
            connection,
            publicationId,
            TagPublicationState.PREPARED,
            TagPublicationState.PUBLISHED,
            updatedAt,
            null
        );
        if (!transitioned) {
            throw new SQLException(
                "Tag publication state changed during canonical publish"
            );
        }
        return true;
    }

    private boolean reconcileTagPublication(
        Connection connection,
        TagReconciliationReceipt receipt,
        long updatedAt
    ) throws SQLException {
        String normalizedCode = normalizeCode(receipt.code());
        try (PreparedStatement canonical = connection.prepareStatement("""
            SELECT 1 FROM tracked_items WHERE code = ? AND item_uuid = ?
            """)) {
            canonical.setString(1, normalizedCode);
            canonical.setString(2, receipt.itemUuid().toString());
            try (ResultSet result = canonical.executeQuery()) {
                if (result.next()) {
                    return true;
                }
            }
        }
        try (PreparedStatement prepared = connection.prepareStatement("""
            SELECT publication_id, sha256 FROM tag_publications
            WHERE code = ? AND item_uuid = ? AND source_key = ? AND state = 'PREPARED'
            """)) {
            prepared.setString(1, normalizedCode);
            prepared.setString(2, receipt.itemUuid().toString());
            prepared.setString(3, receipt.sourceKey());
            try (ResultSet result = prepared.executeQuery()) {
                if (!result.next()) {
                    // C3 (review 2026-09-16). No in-flight publication for this physical source.
                    // Before refusing, ask whether the journal has ever seen this identity at all.
                    // If it has not, the item is carrying an identity this database never issued
                    // — a replaced or restored database, an item that arrived from another server
                    // — and refusing forever means the item cannot be picked up and therefore
                    // despawns. Adopting keeps the item and labels the row so the audit trail
                    // still shows it was not an original issuance. If the journal does know the
                    // identity, something really is wrong with it and the refusal stands.
                    if (identityIsUnknownToJournal(connection, normalizedCode, receipt.itemUuid())) {
                        return adoptIdentity(
                            connection,
                            normalizedCode,
                            receipt.itemUuid(),
                            updatedAt
                        );
                    }
                    return false;
                }
                if (!MessageDigest.isEqual(
                    result.getBytes("sha256"),
                    receipt.taggedDigest()
                )) {
                    return false;
                }
                return publishTagPublication(
                    connection,
                    UUID.fromString(result.getString("publication_id")),
                    updatedAt
                );
            }
        }
    }

    /**
     * Whether neither the publication journal nor the canonical table has ever held this code or
     * this item uuid. Adoption is only allowed in that case: it is the difference between an
     * identity this database never issued and one it did issue and is now missing, and only the
     * first may be recreated from what the item carries.
     */
    private boolean identityIsUnknownToJournal(
        Connection connection,
        String code,
        UUID itemUuid
    ) throws SQLException {
        String uuid = itemUuid.toString();
        if (identityRowExists(
            connection,
            "SELECT 1 FROM tag_publications WHERE code = ? OR item_uuid = ? LIMIT 1",
            code,
            uuid
        )) {
            return false;
        }
        return !identityRowExists(
            connection,
            "SELECT 1 FROM tracked_items WHERE code = ? OR item_uuid = ? LIMIT 1",
            code,
            uuid
        );
    }

    private boolean identityRowExists(
        Connection connection,
        String sql,
        String code,
        String itemUuid
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, code);
            statement.setString(2, itemUuid);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    /**
     * Recreates the canonical row for an identity the journal never issued, from what the item
     * carries, and says so in the history so the audit trail cannot mistake it for an issuance.
     *
     * <p>The row deliberately records no owner, material, name or location: the item's own tags
     * do not contain them, and inventing them would put claims into the record that nothing
     * supports. The first real observation fills them in.
     *
     * <p>`created_at` is written as 0 for the same reason, and it is the durable marker of an
     * adopted row (M4/H4, review 2026-09-17). Writing the adoption time there made a two-year-old
     * sword look like it was made today in `/ig check` — the number staff would use to convict
     * someone — and the first pickup or drop rewrites `last_action`, so `last_action = 'ADOPTED'`
     * stopped identifying the row after any interaction at all. 0 cannot be confused with a real
     * timestamp, and nothing sorts on this column.
     */
    private boolean adoptIdentity(
        Connection connection,
        String code,
        UUID itemUuid,
        long updatedAt
    ) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
            INSERT INTO tracked_items
            (code, item_uuid, created_at, last_seen_at, last_action, detection_count)
            VALUES (?, ?, 0, ?, 'ADOPTED', 0)
            """)) {
            insert.setString(1, code);
            insert.setString(2, itemUuid.toString());
            insert.setLong(3, updatedAt);
            insert.executeUpdate();
        }
        try (PreparedStatement history = connection.prepareStatement("""
            INSERT INTO item_history (code, item_uuid, action, timestamp, additional_data)
            VALUES (?, ?, 'ADOPTED', ?, ?)
            """)) {
            history.setString(1, code);
            history.setString(2, itemUuid.toString());
            history.setLong(3, updatedAt);
            history.setString(4, "identity carried by the item but never issued by this database");
            history.executeUpdate();
        }
        return true;
    }

    private boolean abortTagPublication(
        Connection connection,
        UUID publicationId,
        long updatedAt,
        String detail
    ) throws SQLException {
        Optional<TagPublication> found = findTagPublication(connection, publicationId);
        if (found.isEmpty()) return false;
        TagPublication publication = found.get();
        if (publication.state() == TagPublicationState.PUBLISHED
            || publication.state() == TagPublicationState.ABORTED) {
            return false;
        }
        return updateTagPublicationState(
            connection,
            publicationId,
            publication.state(),
            TagPublicationState.ABORTED,
            updatedAt,
            detail
        );
    }


    private boolean updateTagPublicationState(
        Connection connection,
        UUID publicationId,
        TagPublicationState expected,
        TagPublicationState target,
        long updatedAt,
        String detail
    ) throws SQLException {
        String boundedDetail = detail == null
            ? null
            : detail.substring(0, Math.min(256, detail.length()));
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE tag_publications
            SET state = ?, updated_at = ?, detail = ?
            WHERE publication_id = ? AND state = ? AND updated_at <= ?
            """)) {
            statement.setString(1, target.name());
            statement.setLong(2, updatedAt);
            statement.setString(3, boundedDetail);
            statement.setString(4, publicationId.toString());
            statement.setString(5, expected.name());
            statement.setLong(6, updatedAt);
            return statement.executeUpdate() == 1;
        }
    }

    private void upsertItem(Connection connection, ItemData item) throws SQLException {
        validateCanonicalIdentity(connection, item);
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO tracked_items
            (code, item_uuid, owner_uuid, owner_name, material, item_name, item_lore,
             created_at, last_seen_at, last_action, detection_count, last_location)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(code) DO UPDATE SET
                owner_uuid = excluded.owner_uuid,
                owner_name = excluded.owner_name,
                material = excluded.material,
                item_name = excluded.item_name,
                item_lore = excluded.item_lore,
                last_seen_at = excluded.last_seen_at,
                last_action = excluded.last_action,
                detection_count = excluded.detection_count,
                last_location = excluded.last_location
            WHERE tracked_items.item_uuid = excluded.item_uuid
            """)) {
            bindItem(statement, item);
            statement.executeUpdate();
        }
    }

    private void upsertSnapshot(
        Connection connection,
        String code,
        ItemSnapshot snapshot,
        long capturedAt
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO item_snapshots
            (code, snapshot_version, payload, sha256, captured_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(code) DO UPDATE SET
                snapshot_version = excluded.snapshot_version,
                payload = excluded.payload,
                sha256 = excluded.sha256,
                captured_at = excluded.captured_at
            """)) {
            statement.setString(1, normalizeCode(code));
            statement.setInt(2, snapshot.version());
            statement.setBytes(3, snapshot.payload());
            statement.setBytes(4, snapshot.sha256());
            statement.setLong(5, capturedAt);
            statement.executeUpdate();
        }
    }

    private void bindItem(PreparedStatement statement, ItemData item) throws SQLException {
        statement.setString(1, item.getCode());
        statement.setString(2, item.getItemUuid().toString());
        statement.setString(3,
            item.getOwnerUuid() != null ? item.getOwnerUuid().toString() : null);
        statement.setString(4, item.getOwnerName());
        statement.setString(5, item.getMaterial() != null ? item.getMaterial().name() : null);
        statement.setString(6, item.getItemName());
        statement.setString(7, item.getItemLore());
        statement.setLong(8, item.getCreatedAt());
        statement.setLong(9, item.getLastSeenAt());
        statement.setString(10, item.getLastAction());
        statement.setInt(11, item.getDetectionCount());
        statement.setString(12, item.getLastLocation());
    }

    private void bindSearchRequest(
        PreparedStatement statement,
        ItemSearchRequest request
    ) throws SQLException {
        statement.setString(1, request.code());
        statement.setString(2, request.mode().name());
        statement.setString(3, request.state().name());
        statement.setString(4,
            request.actorUuid() != null ? request.actorUuid().toString() : null);
        statement.setString(5, request.actorName());
        statement.setLong(6, request.createdAt());
        statement.setLong(7, request.expiresAt());
        statement.setLong(8, request.updatedAt());
    }

    private void bindReclaimClaim(
        PreparedStatement statement,
        ReclaimClaim claim
    ) throws SQLException {
        statement.setString(1, claim.claimId().toString());
        statement.setString(2, claim.idempotencyKey());
        statement.setString(3, claim.playerUuid().toString());
        statement.setString(4, normalizeCode(claim.code()));
        statement.setString(5, claim.state().name());
        statement.setLong(6, claim.requestedAt());
        statement.setLong(7, claim.updatedAt());
        statement.setString(8, claim.detail());
    }

    private boolean markSearchRequestFound(
        Connection connection,
        String code,
        long updatedAt
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE item_search_requests
            SET state = 'FOUND', updated_at = ?
            WHERE code = ? AND state = 'ACTIVE' AND expires_at > ?
            """)) {
            statement.setLong(1, updatedAt);
            statement.setString(2, normalizeCode(code));
            statement.setLong(3, updatedAt);
            return statement.executeUpdate() == 1;
        }
    }

    private ItemSearchRequest parseSearchRequest(ResultSet result) throws SQLException {
        String actorUuid = result.getString("actor_uuid");
        return new ItemSearchRequest(
            result.getString("code"),
            ItemSearchMode.valueOf(result.getString("mode")),
            ItemSearchState.valueOf(result.getString("state")),
            actorUuid != null ? UUID.fromString(actorUuid) : null,
            result.getString("actor_name"),
            result.getLong("created_at"),
            result.getLong("expires_at"),
            result.getLong("updated_at")
        );
    }

    private ReclaimClaim parseReclaimClaim(ResultSet result) throws SQLException {
        return new ReclaimClaim(
            UUID.fromString(result.getString("claim_id")),
            result.getString("idempotency_key"),
            UUID.fromString(result.getString("player_uuid")),
            result.getString("code"),
            ReclaimClaimState.valueOf(result.getString("state")),
            result.getLong("requested_at"),
            result.getLong("updated_at"),
            result.getString("detail")
        );
    }

    private String normalizeCode(String code) {
        return code == null ? "" : code.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private int count(Connection connection, String sql, String parameter) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (parameter != null) {
                statement.setString(1, parameter);
            }
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private ItemData parseItem(ResultSet result) throws SQLException {
        ItemData item = new ItemData(
            result.getString("code"),
            UUID.fromString(result.getString("item_uuid"))
        );
        String ownerUuid = result.getString("owner_uuid");
        if (ownerUuid != null) {
            item.setOwnerUuid(UUID.fromString(ownerUuid));
        }
        item.setOwnerName(result.getString("owner_name"));
        String material = result.getString("material");
        if (material != null) {
            try {
                item.setMaterial(Material.valueOf(material));
            } catch (IllegalArgumentException ignored) {
                // Unknown material remains null and is surfaced by the caller.
            }
        }
        item.setItemName(result.getString("item_name"));
        item.setItemLore(result.getString("item_lore"));
        item.setCreatedAt(result.getLong("created_at"));
        item.setLastSeenAt(result.getLong("last_seen_at"));
        item.setLastAction(result.getString("last_action"));
        item.setDetectionCount(result.getInt("detection_count"));
        item.setLastLocation(result.getString("last_location"));
        return item;
    }

    private ItemHistory parseHistory(ResultSet result) throws SQLException {
        ItemHistory history = new ItemHistory();
        history.setId(result.getLong("id"));
        history.setCode(result.getString("code"));
        history.setItemUuid(UUID.fromString(result.getString("item_uuid")));
        history.setAction(result.getString("action"));
        history.setPlayerName(result.getString("player_name"));
        String playerUuid = result.getString("player_uuid");
        if (playerUuid != null) {
            history.setPlayerUuid(UUID.fromString(playerUuid));
        }
        history.setLocation(result.getString("location"));
        history.setWorld(result.getString("world"));
        history.setX(result.getInt("x"));
        history.setY(result.getInt("y"));
        history.setZ(result.getInt("z"));
        history.setTimestamp(result.getLong("timestamp"));
        history.setAdditionalData(result.getString("additional_data"));
        return history;
    }

    private static final class SummaryAccumulator {
        private final ItemData item;
        private final Map<String, Integer> actionCounts = new LinkedHashMap<>();
        private int totalEvents;

        private SummaryAccumulator(ItemData item) {
            this.item = item;
        }

        private void add(String action, int count) {
            int boundedCount = Math.max(0, count);
            actionCounts.merge(action == null ? "UNKNOWN" : action, boundedCount, Integer::sum);
            totalEvents += boundedCount;
        }

        private ItemHistorySummary toSummary() {
            return new ItemHistorySummary(item, totalEvents, actionCounts);
        }
    }
}
