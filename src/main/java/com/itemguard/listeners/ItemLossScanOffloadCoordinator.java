package com.itemguard.listeners;

import com.itemguard.dupe.ScanEpochGenerator;
import com.itemguard.tracking.UnexplainedRemovalPolicy;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Takes the loss scan's history read and loss write off the scan thread without turning a slow tick
 * into a false loss.
 *
 * <p>The synchronous code could not be wrong about the world, because nothing could change between
 * its read and its write. Every asynchronous remedy opens that window: while the journal is being
 * asked what the code's last action was, the stack can come back, the player can drop it on purpose,
 * the player can quit, a newer scan pass can start, or the plugin can disable. So the decision is
 * split in two. Off-thread the coordinator only moves data; every judgement — revalidation, the
 * commit, the callbacks — happens on the main thread, on identifiers snapshotted at request time so
 * that no Bukkit-backed object is ever touched from a journal thread.
 *
 * <p>The approval is a compare-and-set on the entry's own state, not a re-read of the flags.
 * Rechecking and then submitting leaves a window in which an invalidation lands after the check and
 * the write still reaches the journal; the coordinator would be recording a loss it had already
 * agreed to abandon.
 *
 * <p>Approval is not the commit, though it used to be. Treating the submission as the last word made
 * the queue itself a source of false history: the main thread keeps running while a write waits its
 * turn, so the exact stack can be picked up again in that gap, and the journal's own fence is blind
 * to it — a sighting appends no row for the fence to read. The write then recorded a loss for an item
 * the server could see.
 *
 * <p>So the commit moved to where the work actually happens. The entry stays revocable through
 * {@code SUBMITTED}, and the worker takes it by claiming the record's
 * {@link LossJournal.WritePermit} inside the writing transaction, before touching a row. That is the
 * linearisation boundary, and it is worth stating exactly: <b>a revocation that wins the entry before
 * the worker claims it prevents the write entirely; one that arrives after cannot undo history that
 * is already committed.</b> The window is closed, not abolished — an item seen again while the
 * transaction is mid-flight is recorded as lost, and the remedy for that is the restore path, not a
 * compensating write.
 *
 * <p>Giving up is not free either. A refused submission or a timed-out read must rearm the baseline,
 * because a code dropped silently is retired from {@code lastSeen} and no later scan will ever look
 * at it again. Under-reporting a loss once is recoverable; losing the only record of it is not.
 *
 * <p>That obligation outlives the thread that discovers it. When the scheduler refuses the hop back
 * to the main thread, the refusal surfaces on a journal thread, where the candidate may not be
 * touched at all. The entry is therefore parked rather than dropped, and the debt is paid by the
 * next main-thread pass that belongs to the player. A disable is the one refusal with no debt: the
 * entry has already been revoked and there is no later pass to recover into.
 */
public final class ItemLossScanOffloadCoordinator {

    /** Ceiling on concurrently in-flight candidates, so a stalled journal cannot grow the queue. */
    public static final int DEFAULT_MAX_PENDING = 256;

    private final LossJournal journal;
    private final Consumer<Runnable> mainThreadDispatcher;
    private final BooleanSupplier pluginEnabled;
    private final ScanEpochGenerator epochs;
    private final int maxPending;
    private final UnexplainedRemovalPolicy removalPolicy = new UnexplainedRemovalPolicy();

    /** The generation each player's current scan pass belongs to. Dropped when the player leaves. */
    private final ConcurrentHashMap<UUID, Long> generations = new ConcurrentHashMap<>();

    /** One in-flight candidate per code. */
    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();

    private final AtomicBoolean shutdown = new AtomicBoolean();

    public ItemLossScanOffloadCoordinator(
        LossJournal journal,
        Consumer<Runnable> mainThreadDispatcher,
        BooleanSupplier pluginEnabled,
        ScanEpochGenerator epochs
    ) {
        this(journal, mainThreadDispatcher, pluginEnabled, epochs, DEFAULT_MAX_PENDING);
    }

    public ItemLossScanOffloadCoordinator(
        LossJournal journal,
        Consumer<Runnable> mainThreadDispatcher,
        BooleanSupplier pluginEnabled,
        ScanEpochGenerator epochs,
        int maxPending
    ) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.mainThreadDispatcher = Objects.requireNonNull(mainThreadDispatcher, "mainThreadDispatcher");
        this.pluginEnabled = Objects.requireNonNull(pluginEnabled, "pluginEnabled");
        this.epochs = Objects.requireNonNull(epochs, "epochs");
        if (maxPending < 1) {
            throw new IllegalArgumentException("maxPending must be positive");
        }
        this.maxPending = maxPending;
    }

    /**
     * Opens a scan pass for a player and supersedes the previous one.
     *
     * <p>Anything still waiting on the journal under the older generation is revoked here rather
     * than when its answer arrives: a callback that outlives its pass has nothing left to say about
     * an inventory two passes newer. This is also the first legal thread on which a candidate
     * stranded by a refused dispatch can be handed back, so the older pass is cleared through
     * {@link #discard(Pending)} rather than a bare revocation.
     *
     * <p>Main thread only.
     */
    public long beginGeneration(UUID playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        long generation = epochs.next();
        if (shutdown.get()) {
            // Still monotonic, but nothing is tracked after disable: the map must not refill.
            return generation;
        }
        generations.put(playerUuid, generation);
        for (Pending entry : pending.values()) {
            if (playerUuid.equals(entry.playerUuid) && entry.generation != generation) {
                discard(entry);
            }
        }
        return generation;
    }

    /**
     * Submits one departed code for adjudication.
     *
     * @return true when the candidate was accepted and the caller may retire its baseline; false
     *     when it was refused, in which case the baseline has already been rearmed for any refusal
     *     the caller did not cause itself
     */
    public boolean request(LossCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (shutdown.get() || !pluginEnabled.getAsBoolean()) {
            return rearm(candidate);
        }

        Pending entry = new Pending(candidate);
        if (pending.size() >= maxPending) {
            // The journal is behind. Hand the code back rather than queue without bound.
            return rearm(candidate);
        }
        if (pending.putIfAbsent(entry.code, entry) != null) {
            // One departure per code is already in flight. On a duped code that is not a duplicate
            // sighting: it is a second stack, in a second inventory, with a baseline of its own that
            // the in-flight entry knows nothing about. Refusing the slot is right, but refusing it
            // silently retires this candidate's code from lastSeen. Hand it back. The candidate that
            // owns the slot is not touched, so its own baseline stays in the coordinator's hands.
            return rearm(candidate);
        }

        final CompletableFuture<String> read;
        try {
            read = Objects.requireNonNull(journal.lastRecordedAction(entry.code), "lastRecordedAction");
        } catch (RuntimeException refused) {
            pending.remove(entry.code, entry);
            return rearm(candidate);
        }
        read.whenComplete((action, failure) -> dispatch(entry, () -> settle(entry, action, failure)));
        return true;
    }

    /**
     * The code was seen again. Any in-flight verdict about it is now stale.
     *
     * <p>The holder is not compared: an item that turned up in someone else's inventory is still an
     * item that exists, which is the only thing being refuted here.
     *
     * <p>Main thread only.
     */
    public void observePresence(String code, UUID itemUuid, UUID playerUuid) {
        revokeSighting(code, itemUuid);
    }

    /** The code left for a reason already accounted for, such as a deliberate drop. Main thread only. */
    public void observeExplainedDeparture(String code, UUID itemUuid, UUID playerUuid) {
        revokeSighting(code, itemUuid);
    }

    /**
     * Drops everything in flight for a player, on quit or on any other event that ends the pass.
     *
     * <p>The baseline is not rearmed: a quit takes the player's whole snapshot with it, so there is
     * nothing left to rearm against. A write already committed is left to finish — it was true when
     * it was committed, and revoking it after the fact would be rewriting history.
     *
     * <p>Main thread only.
     */
    public void invalidate(UUID playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        generations.remove(playerUuid);
        for (Pending entry : pending.values()) {
            if (playerUuid.equals(entry.playerUuid)) {
                revoke(entry);
            }
        }
    }

    /**
     * Whether anything is still in flight for this player.
     *
     * <p>Exists so a caller can decide not to open a new pass yet. {@link #beginGeneration} supersedes
     * the previous pass, and a scan that opens one every tick cancels reads that have not answered
     * within a tick — for a journal that is even slightly behind, every read is cancelled and retried
     * forever and no loss is ever recorded. Main thread only.
     */
    /**
     * Hands back every candidate of this player's that a refused dispatch stranded. Main thread only.
     *
     * <p>Parking is a debt. The caller retired its baseline on a {@code true} from {@link #request},
     * the answer could not reach the main thread, and from then on only this coordinator knows the
     * code is missing. {@link #beginGeneration} used to be the only place that debt was paid, and a
     * scan cannot get there: the code is out of its baseline, so it sees nothing departed and never
     * opens a pass, while the parked entry keeps its slot and its token for as long as the server
     * runs. This is the call that closes that loop, and it is meant to run every pass, before
     * anything asks {@link #hasPending}.
     *
     * <p>Only {@code PARKED} entries are touched. A read still in flight and a write already approved
     * or claimed are left exactly alone — cancelling those is what opening a generation every tick
     * would do, and it would mean a journal slightly behind never finishes anything.
     *
     * <p>Silent after shutdown: the entries are already revoked and there is no baseline left to owe,
     * so nothing calls back into a candidate while the server is tearing down.
     */
    public void recoverParked(UUID playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        if (shutdown.get()) {
            return;
        }
        for (Pending entry : pending.values()) {
            if (playerUuid.equals(entry.playerUuid) && entry.parked()) {
                discard(entry);
            }
        }
    }

    public boolean hasPending(UUID playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        for (Pending entry : pending.values()) {
            if (playerUuid.equals(entry.playerUuid)) {
                return true;
            }
        }
        return false;
    }

    /** Disable. Refuses new work and revokes every candidate that has not committed. */
    public void shutdown() {
        if (!shutdown.compareAndSet(false, true)) {
            return;
        }
        for (Pending entry : pending.values()) {
            revoke(entry);
        }
        generations.clear();
    }

    private void revokeSighting(String code, UUID itemUuid) {
        if (code == null) {
            return;
        }
        Pending entry = pending.get(code);
        if (entry != null && (itemUuid == null || itemUuid.equals(entry.itemUuid))) {
            revoke(entry);
        }
    }

    /** The journal answered. Runs on the main thread; this is where every judgement is made. */
    private void settle(Pending entry, String lastRecordedAction, Throwable failure) {
        if (!entry.reading()) {
            // Already revoked by a reappearance, a drop, a newer generation, a quit or a disable.
            return;
        }
        if (failure != null) {
            // The read never answered, so nothing is known. Keep the code visible to the next scan.
            abandon(entry);
            return;
        }
        if (!removalPolicy.isUnexplained(lastRecordedAction)) {
            revoke(entry);
            return;
        }
        if (!live(entry)) {
            revoke(entry);
            return;
        }

        final boolean stillMissing;
        try {
            stillMissing = entry.candidate.stillMissing();
        } catch (RuntimeException revalidationFailure) {
            abandon(entry);
            return;
        }
        if (!stillMissing) {
            revoke(entry);
            return;
        }

        // The fence. Everything above is a precondition that stopped being true the instant it was
        // read: revalidation itself can fire the quit handler, and another thread can invalidate
        // while this line runs. Re-reading the flags closes the window for state that is not an
        // entry transition; the compare-and-set is what makes the decision atomic against one.
        //
        // Winning it approves the write and submits it. It does not perform it, and it is not the
        // last word: the entry stays revocable until the journal's worker claims its permit.
        if (!live(entry) || !entry.submit()) {
            revoke(entry);
            return;
        }
        write(entry);
    }

    /** Approved and on its way to the journal. The permit is what can still stop it. */
    private void write(Pending entry) {
        LossRecord record = new LossRecord(
            entry.code,
            entry.itemUuid,
            ItemLossPolicy.forInventoryRemoval(),
            entry.playerUuid,
            entry.generation,
            entry.departureToken,
            entry::claimWrite,
            entry.location
        );

        final CompletableFuture<Boolean> written;
        try {
            written = Objects.requireNonNull(journal.recordLoss(record), "recordLoss");
        } catch (RuntimeException refused) {
            release(entry);
            entry.candidate.rearmBaseline();
            return;
        }
        written.whenComplete((recorded, failure) ->
            dispatch(entry, () -> finish(entry, recorded, failure)));
    }

    /** The write answered. Main thread. */
    private void finish(Pending entry, Boolean recorded, Throwable failure) {
        release(entry);
        if (failure == null && Boolean.TRUE.equals(recorded)) {
            entry.candidate.recorded();
            return;
        }
        // The one answer that cannot improve by being asked again. Everything else — a refusal, a
        // timeout, a closed connection — stays a retry, so a broken database is never quietly
        // retired as though the code had been adjudicated.
        LossJournal.UntrackedCodeException untracked = untracked(failure);
        if (untracked != null) {
            entry.candidate.unrecordable(untracked.getMessage());
            return;
        }
        entry.candidate.rearmBaseline();
    }

    /** The untracked verdict, wherever the future wrapped it, or null for any other failure. */
    private LossJournal.UntrackedCodeException untracked(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof LossJournal.UntrackedCodeException verdict) {
                return verdict;
            }
            if (cause == cause.getCause()) {
                break;
            }
        }
        return null;
    }

    private boolean live(Pending entry) {
        if (shutdown.get() || !pluginEnabled.getAsBoolean()) {
            return false;
        }
        Long current = generations.get(entry.playerUuid);
        return current != null && current.longValue() == entry.generation;
    }

    /** Gives up on a candidate and hands the code back to the baseline. Main thread. */
    private void abandon(Pending entry) {
        if (revoke(entry)) {
            entry.candidate.rearmBaseline();
        }
    }

    /**
     * Clears a candidate whose pass is over, paying the baseline back only if it is owed.
     *
     * <p>An ordinary in-flight entry is superseded, not lost: the newer pass will see the code again
     * and the caller's baseline was never retired on its behalf. A parked entry is the opposite — the
     * caller retired its baseline on a {@code true} from {@link #request}, and only the coordinator
     * still knows the code is missing, so this is the call that owes it back. Main thread.
     */
    private void discard(Pending entry) {
        boolean owed = entry.parked();
        if (revoke(entry) && owed) {
            entry.candidate.rearmBaseline();
        }
    }

    /**
     * Kills a candidate that has not committed.
     *
     * @return true when this call was the one that killed it, so the caller owns the cleanup
     */
    private boolean revoke(Pending entry) {
        if (!entry.kill()) {
            return false;
        }
        pending.remove(entry.code, entry);
        return true;
    }

    /** Frees the slot for a candidate that is finished, committed or not. */
    private void release(Pending entry) {
        entry.retire();
        pending.remove(entry.code, entry);
    }

    private boolean rearm(LossCandidate candidate) {
        candidate.rearmBaseline();
        return false;
    }

    /**
     * Hops back to the main thread. Runs on a journal thread, so the candidate is out of reach here
     * however the hop ends.
     *
     * <p>A refusal while the plugin is still up is a bounded queue, not a disable, and the caller has
     * already retired its baseline on the strength of a {@code true} from {@link #request}. The entry
     * is parked in {@code pending} instead of being dropped, holding its code until a main thread
     * that belongs to this player arrives to hand the baseline back. Once the coordinator is shut
     * down there is no such thread and no baseline left to owe, so the slot is simply freed.
     */
    private void dispatch(Pending entry, Runnable task) {
        try {
            mainThreadDispatcher.accept(task);
        } catch (RuntimeException dispatchFailure) {
            if (shutdown.get() || !entry.park()) {
                release(entry);
            }
        }
    }

    private enum Phase {
        /** Waiting on the journal read; still revocable. */
        READING,
        /** The hop back was refused; revocable, and owed a baseline on the next main-thread pass. */
        PARKED,
        /**
         * Approved and handed to the journal, still revocable.
         *
         * <p>A queued write has not happened yet. The main thread keeps running while it waits, so
         * the stack can be seen again before any worker touches it — and the database fence cannot
         * catch that, because a sighting appends no row. The entry stays killable until the worker
         * claims its permit.
         */
        SUBMITTED,
        /** The worker claimed the write. It is going to disk and can no longer be called off. */
        COMMITTED,
        /** Revoked or finished. */
        RETIRED
    }

    /**
     * One in-flight candidate, plus the identifiers copied off it on the main thread.
     *
     * <p>The copies are what let the journal path exist at all: the candidate itself is only ever
     * touched from the main thread.
     */
    private static final class Pending {

        private final LossCandidate candidate;
        private final String code;
        private final UUID itemUuid;
        private final UUID playerUuid;
        private final long generation;

        /**
         * Copied, never minted. An entry is one attempt at a departure, and attempts are exactly what
         * must share an identity: a rearm after an ambiguous write brings the same disappearance back
         * through a new entry, and minting here would make that resubmission look like a new loss.
         */
        private final UUID departureToken;

        /** Formatted on the main thread at request time; a string, so it crosses threads safely. */
        private final String location;

        private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.READING);

        Pending(LossCandidate candidate) {
            this.candidate = candidate;
            this.code = Objects.requireNonNull(candidate.code(), "code");
            this.itemUuid = candidate.itemUuid();
            this.playerUuid = Objects.requireNonNull(candidate.playerUuid(), "playerUuid");
            this.generation = candidate.generation();
            this.departureToken = Objects.requireNonNull(
                candidate.departureToken(), "departureToken"
            );
            this.location = candidate.location();
        }

        boolean reading() {
            return phase.get() == Phase.READING;
        }

        /**
         * Approves the write and hands it to the journal. One winner, and only before a revocation.
         *
         * <p>Approval, not commitment: the entry is still killable afterwards.
         */
        boolean submit() {
            return phase.compareAndSet(Phase.READING, Phase.SUBMITTED);
        }

        /**
         * The commit point, and it belongs to the worker about to write.
         *
         * <p>Called from a journal thread and raced against every main-thread revocation. Winning it
         * means the loss is going to disk; losing it means a sighting, a quit or a disable got there
         * first and there is nothing left to record.
         */
        boolean claimWrite() {
            return phase.compareAndSet(Phase.SUBMITTED, Phase.COMMITTED);
        }

        /**
         * Strands an entry whose hop back was refused. Loses to a revocation that got there first,
         * and cannot touch an entry past the fence: a committed write is already the journal's.
         */
        boolean park() {
            return phase.compareAndSet(Phase.READING, Phase.PARKED);
        }

        boolean parked() {
            return phase.get() == Phase.PARKED;
        }

        /**
         * Revocation. Fails once the worker has claimed the write, which is what makes it safe.
         *
         * <p>A submitted write is fair game: it is queued, not done, and cancelling it costs nothing
         * but a loss nobody wanted recorded.
         */
        boolean kill() {
            for (Phase current = phase.get();
                 current == Phase.READING || current == Phase.PARKED || current == Phase.SUBMITTED;
                 current = phase.get()) {
                if (phase.compareAndSet(current, Phase.RETIRED)) {
                    return true;
                }
            }
            return false;
        }

        void retire() {
            phase.set(Phase.RETIRED);
        }
    }
}
