package com.itemguard.persistence;

import com.itemguard.listeners.LossJournal;
import com.itemguard.listeners.LossRecord;
import com.itemguard.tracking.UnexplainedRemovalPolicy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/**
 * The JDBC side of {@link LossJournal}: the loss scan's history read and loss write, both carried out
 * on the database's own serial executor.
 *
 * <p>Every statement runs inside {@link SqliteConnectionOwner}, so the connection is touched from one
 * thread only and the caller — a main-thread scan tick — never waits on a disk seek. Refusing work is
 * normal: a closed owner throws {@link java.util.concurrent.RejectedExecutionException} out of the
 * submitting call, which the coordinator reads as "not now".
 *
 * <h2>Reading</h2>
 * <p>The answer is the newest {@code item_history} row for the code, ordered {@code timestamp DESC,
 * id DESC} exactly as {@link ItemSqliteRepository#getHistory} orders it. The tie-break on {@code id}
 * is not decoration: several actions can share a millisecond, and without it the "last" action is
 * whichever row SQLite happens to return, so the same database could answer both "explained" and
 * "unexplained" for one code. When a code has no history at all the stored {@code last_action}
 * summary is the fallback, and an untracked code answers null.
 *
 * <h2>Writing</h2>
 * <p>One transaction does three things in order, and the order is the contract:
 *
 * <ol>
 *   <li><b>Replay check.</b> A departure already on record completes {@code true} without appending a
 *       row. Idempotency is keyed on the departure — the code, the item UUID and the scan generation
 *       that approved it — not on "a loss is already the latest action". The blanket form is wrong on
 *       a code that was lost, restored and lost again: the second loss is a different event and must
 *       be its own row. The key is carried in the existing free-form {@code additional_data} column,
 *       so no schema change is needed and a database written by an older build still opens.</li>
 *   <li><b>The fence.</b> The newest recorded action is re-read here, inside this transaction, on the
 *       worker that is about to write. The coordinator's read happened earlier and the queue may have
 *       been deep since; an action that explains the departure (a drop, a death, a loss already
 *       recorded) can have landed in between. Judging on the state at submission time would write a
 *       loss for an item whose disappearance is now accounted for — a false confirmed loss, which is
 *       a restore path for an item that still exists. Refusal completes {@code false}.</li>
 *   <li><b>The write.</b> {@code tracked_items.last_action} moves and the history row is appended
 *       together. Half of that pair is worse than neither: a summary saying {@code CLEARED} with no
 *       history row behind it, or a history row the summary contradicts. A failure in either
 *       statement rolls the whole transaction back and completes the future exceptionally.</li>
 * </ol>
 *
 * <p>{@code false} and an exceptional completion are different answers. {@code false} is a decision —
 * the journal looked and declined. Exceptional is an absence of one, and the coordinator rearms the
 * baseline so the next scan pass looks again.
 *
 * <p>The location is left alone. Off the main thread there is no trustworthy position for the item,
 * and overwriting the last known one with a guess would destroy the only clue an admin has.
 *
 * <h2>What the replay check does and does not promise</h2>
 * <p>The departure key is a column value, not a set in memory, and it is written by the same
 * transaction as the loss it identifies. So the check survives a restart, and it survives a crash:
 * after recovery either both the key and the row exist or neither does, and a resubmitted departure
 * reads its own committed evidence. Nothing accumulates in the process: the check holds no state at
 * all. Its cost is bounded by the history of the one code being written — {@code idx_history_identity_time}
 * leads with {@code code}, so the seek is indexed, and the departure key is then matched over that
 * code's rows rather than the table. No new index and no schema change are involved.
 *
 * <p>It is not exactly-once. Two limits are worth stating plainly. History retention deletes old rows
 * ({@code deleteHistoryBefore}), and a departure whose loss row has been pruned is no longer on
 * record, so a resubmission that old would write a second row — the guarantee lasts exactly as long
 * as the evidence does. And the key identifies a departure, not an item: two genuinely different
 * departures of the same code are two rows by design, which is the point of keying on the scan
 * generation rather than on "a loss is already the latest action".
 */
public final class SqliteLossJournal implements LossJournal {

    /** Marks a history row as the record of one departure, and names which departure it was. */
    private static final String DEPARTURE_KEY_PREFIX = "loss-departure:";

    /** The actor written for a removal no player performed, as the synchronous loss path names it. */
    private static final String SERVER_ACTOR = "server";

    private final SqliteConnectionOwner owner;
    private final LongSupplier clock;
    private final UnexplainedRemovalPolicy removalPolicy = new UnexplainedRemovalPolicy();

    public SqliteLossJournal(SqliteConnectionOwner owner) {
        this(owner, System::currentTimeMillis);
    }

    public SqliteLossJournal(SqliteConnectionOwner owner, LongSupplier clock) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public CompletableFuture<String> lastRecordedAction(String code) {
        Objects.requireNonNull(code, "code");
        return owner.callAsync(connection -> newestAction(connection, code));
    }

    @Override
    public CompletableFuture<Boolean> recordLoss(LossRecord record) {
        Objects.requireNonNull(record, "record");
        String code = Objects.requireNonNull(record.code(), "code");
        String action = Objects.requireNonNull(record.reason(), "reason").action();
        String departureKey = departureKey(record);
        LossJournal.WritePermit permit = Objects.requireNonNull(record.permit(), "permit");
        // Everything below runs on the database worker, inside one transaction. Nothing about the
        // record is screened here: a record that cannot be written must fail where the write happens,
        // so the half-applied state it leaves behind is undone by the same rollback.
        return owner.callAsync(connection -> {
            if (alreadyRecorded(connection, code, departureKey)) {
                return Boolean.TRUE;
            }
            if (!removalPolicy.isUnexplained(newestAction(connection, code))) {
                return Boolean.FALSE;
            }
            // The last gate, and the only one that can see a sighting. The fence above asks the
            // database what happened, and "the stack is back in the player's hand" is not something
            // the database was told: no row is appended for it. Claiming here, on the worker and
            // before any mutation, is what makes the queue revocable — up to this line the loss can
            // still be called off, and past it the row is going to exist.
            if (!permit.claim()) {
                return Boolean.FALSE;
            }
            long recordedAt = clock.getAsLong();
            if (!markCleared(connection, code, action, record.location(), recordedAt)) {
                // Nothing to lose: the code is not tracked, so a history row would be an orphan.
                // Not false, which would mean "refused, try again": the row is pruned and is not
                // coming back, while the PDC tag keeps the code turning up on every scan. Raising
                // it rolls the transaction back and tells the coordinator this one is finished.
                throw new LossJournal.UntrackedCodeException(code);
            }
            appendHistory(connection, record, action, recordedAt, departureKey);
            return Boolean.TRUE;
        });
    }

    /**
     * The newest action on record, read the same way by the coordinator's read and by the fence.
     *
     * <p>{@code id DESC} is the tie-break, and it is load-bearing: rows sharing a millisecond are
     * common, and without it "the last action" is whatever order the query planner returns. The
     * stored summary is the fallback only when the code has no history at all — it is a column any
     * writer can forget to update, while history is append-only.
     */
    private String newestAction(Connection connection, String code) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT action FROM item_history
            WHERE code = ?
            ORDER BY timestamp DESC, id DESC
            LIMIT 1
            """)) {
            statement.setString(1, code);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return result.getString(1);
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT last_action FROM tracked_items WHERE code = ?"
        )) {
            statement.setString(1, code);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    /** Whether this exact departure is already on record, read inside the writing transaction. */
    private boolean alreadyRecorded(Connection connection, String code, String departureKey)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT 1 FROM item_history
            WHERE code = ? AND additional_data = ?
            LIMIT 1
            """)) {
            statement.setString(1, code);
            statement.setString(2, departureKey);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    /**
     * Moves the summary action, and the position if one was captured.
     *
     * <p>{@code COALESCE} is the null contract in one word: a record carrying no position leaves
     * whatever the row already said. Overwriting the last known position with a blank would destroy
     * the only clue an admin has, and writing "Unknown" over it would be worse — it would look like
     * an answer.
     *
     * @return false when no tracked item carries this code
     */
    private boolean markCleared(
        Connection connection,
        String code,
        String action,
        String location,
        long recordedAt
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE tracked_items
            SET last_action = ?, last_seen_at = ?, last_location = COALESCE(?, last_location)
            WHERE code = ?
            """)) {
            statement.setString(1, action);
            statement.setLong(2, recordedAt);
            statement.setString(3, location);
            statement.setString(4, code);
            return statement.executeUpdate() > 0;
        }
    }

    /**
     * Appends the loss row carrying its departure key.
     *
     * <p>The item UUID is bound as it arrives, null included. The column rejects null, and that
     * rejection is the point: a record without an identity cannot become a history row, and the
     * transaction that already moved the summary is rolled back with it.
     *
     * <p>The actor is the server — no player performed this removal, which is what makes it a loss.
     * The player column holds the holder whose inventory it left, because that is the only person the
     * record actually knows about.
     */
    private void appendHistory(
        Connection connection,
        LossRecord record,
        String action,
        long recordedAt,
        String departureKey
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO item_history
            (code, item_uuid, action, player_name, player_uuid, location,
             world, x, y, z, timestamp, additional_data)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
            statement.setString(1, record.code());
            setUuid(statement, 2, record.itemUuid());
            statement.setString(3, action);
            statement.setString(4, SERVER_ACTOR);
            setUuid(statement, 5, record.playerUuid());
            // Where it was noticed missing, as the main thread formatted it. The world and block
            // columns stay empty: the string is what every other writer records and what the history
            // views read, and splitting it here would mean parsing it back out.
            statement.setString(6, record.location());
            statement.setNull(7, Types.VARCHAR);
            statement.setNull(8, Types.INTEGER);
            statement.setNull(9, Types.INTEGER);
            statement.setNull(10, Types.INTEGER);
            statement.setLong(11, recordedAt);
            statement.setString(12, departureKey);
            statement.executeUpdate();
        }
    }

    private void setUuid(PreparedStatement statement, int index, java.util.UUID value)
        throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
            return;
        }
        statement.setString(index, value.toString());
    }

    /**
     * Identifies the disappearance, not the item and not the pass that noticed it.
     *
     * <p>The scan generation used to serve here and could not: a fresh {@code ScanEpochGenerator}
     * reissues epochs after a restart, so a genuinely new departure that drew a reused epoch was
     * discarded as a replay and never recorded. The token comes from the baseline instead and lives
     * exactly as long as the code stays missing, which is the span over which two writes really are
     * one loss.
     */
    private static String departureKey(LossRecord record) {
        return DEPARTURE_KEY_PREFIX + Objects.requireNonNull(
            record.departureToken(), "departureToken"
        );
    }
}
