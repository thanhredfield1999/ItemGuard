package smoke;

/**
 * The armed-crafting-close state machine, isolated from Paper so it can be tested without a server.
 *
 * <p>Everything the crafting-close cases get wrong when they get it wrong lives here and nowhere
 * else: which close belongs to which armed case, what happens to the listener on each terminal
 * path, what a throwing server call means, and which inventory slots a recovery is allowed to
 * clear. None of that needs a world, a player or a window to be decided, so none of it is decided
 * inside an event handler where the only way to observe it is a 15-minute server run.
 *
 * <p>{@code LiteProbe} owns the Bukkit side — registering listeners, reading capacity, stamping and
 * clearing slots — and asks this class what those actions mean. Deliberately no Bukkit import: a
 * test that needed paper-api on the classpath would be a second integration harness.
 */
public final class CraftCloseLifecycle {

    /** How an armed case ended. Every one of these is terminal and every one must release. */
    public enum Outcome {
        /** Still waiting for its own close. Not terminal. */
        ARMED,
        /** The case observed its close and reported a receipt. */
        SUCCESS,
        /** The identity was in neither the slots nor the world after the close. */
        UNACCOUNTED,
        /** A server call this case depends on threw. Nothing is claimed about the close. */
        EXCEPTION,
        /** The armed case never saw its own close inside its budget. */
        TIMEOUT
    }

    /**
     * A number read off the live server, which can fail exactly as the Bukkit call it stands for.
     *
     * <p>Modelled as throwing on purpose: a capacity read that blew up must not leave the case
     * looking like a close that simply had no acknowledgement.
     */
    public interface Capacity {
        int read() throws Exception;
    }

    /**
     * Gives back what the case took from the world, whatever ended it.
     *
     * <p>Registered at arm time and run by {@link #finish}, so returning the filler is an obligation
     * of ENDING rather than of succeeding: a case that timed out or threw took exactly as much room
     * as one that reported a receipt, and leaving it taken changes the world the next case is read
     * against.
     */
    public interface Cleanup {
        void run();
    }

    /** Writes this case's stamped filler into one slot, and can fail as the Bukkit call can. */
    public interface SlotWriter {
        void write(int slot) throws Exception;
    }

    /** Takes one slot's contents back out. Used both for rollback and for the release cleanup. */
    public interface SlotClearer {
        void clear(int slot);
    }

    /**
     * Takes the room and arms the case as ONE step: either the returned case owns every slot that
     * was written, or nothing is left written.
     *
     * <p>Filling before arming is what strands filler. A write that fails half way through, or an
     * arm that rejects its inputs after the room is already taken, leaves stacks in an inventory no
     * lifecycle knows about, so no terminal path can ever give them back. Inputs are validated
     * before the first write, and a write that throws rolls back the ones that succeeded.
     *
     * @throws Exception whatever the writer threw, after the rollback has run
     */
    public static CraftCloseLifecycle armFilled(String label, Object view, String marker,
                                                int[] slots, SlotWriter writer,
                                                SlotClearer clearer) throws Exception {
        // Validated before the first write. Rejecting afterwards would be the same stranded filler
        // by a different route: room taken for a case that then refuses to exist.
        if (view == null) {
            throw new IllegalArgumentException("a case cannot be armed without a view");
        }
        if (marker == null || marker.isEmpty()) {
            throw new IllegalArgumentException("a case cannot be armed without its own marker");
        }
        if (writer == null || clearer == null) {
            throw new IllegalArgumentException("a case that takes room must be able to give it back");
        }
        int[] wanted = slots == null ? new int[0] : slots.clone();
        int[] written = new int[wanted.length];
        int size = 0;
        try {
            for (int slot : wanted) {
                writer.write(slot);
                written[size] = slot;
                size++;
            }
        } catch (Throwable failure) {
            // Roll back only what THIS call actually wrote, and never lose the original failure: a
            // clear that throws in turn is attached as suppressed rather than replacing it.
            for (int i = 0; i < size; i++) {
                try {
                    clearer.clear(written[i]);
                } catch (Throwable rollback) {
                    failure.addSuppressed(rollback);
                }
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw (Exception) failure;
        }
        return arm(label, view, java.util.Arrays.copyOf(written, size), marker);
    }

    /**
     * Whether this case ended with everything it owed done: released, cleaned up, nothing thrown.
     *
     * <p>What acceptance must be ANDed with. A case that reported its receipt but could not give
     * the room back has not ended cleanly, and printing PASS for it hands the next case an
     * inventory state nobody declared.
     */
    public boolean endedCleanly() {
        return released && cleanupRan && cleanupFailure == null;
    }

    private final String label;
    private final Object view;
    private final int[] filled;
    private final String marker;
    private Outcome outcome = Outcome.ARMED;
    private boolean closed;
    private boolean released;
    private String ack = "NONE";
    private int slotsFree = -1;
    private Cleanup cleanup;
    private boolean cleanupRan;
    private Throwable cleanupFailure;

    private CraftCloseLifecycle(String label, Object view, int[] filled, String marker) {
        this.label = label;
        this.view = view;
        this.filled = filled == null ? new int[0] : filled.clone();
        this.marker = marker;
    }

    /**
     * Arms one case against ONE view, stamping what it took with {@code marker}.
     *
     * <p>{@code view} is the identity of the inventory view that was open when the arm ran, not its
     * type: the actor has more than one window over a run and two of them can be the same kind. The
     * marker is this case's own and not a material, because a genuine stack of the same material
     * can be sitting in a slot this case filled and a recovery matching on type would delete it.
     */
    public static CraftCloseLifecycle arm(String label, Object view, int[] filledSlots,
                                          String marker) {
        if (view == null) {
            throw new IllegalArgumentException("a case cannot be armed without a view");
        }
        if (marker == null || marker.isEmpty()) {
            throw new IllegalArgumentException("a case cannot be armed without its own marker");
        }
        return new CraftCloseLifecycle(label, view, filledSlots, marker);
    }

    /** The case label this was armed for. */
    public String label() {
        return label;
    }

    /** Whether this case is still waiting for its own close. */
    public boolean armed() {
        return outcome == Outcome.ARMED;
    }

    /** Registers the one cleanup this case owes the world. Run exactly once, by {@link #finish}. */
    public void onRelease(Cleanup cleanup) {
        if (!armed()) {
            throw new IllegalStateException("a case that already ended cannot take on a cleanup");
        }
        this.cleanup = cleanup;
    }

    /**
     * Offers an observed close to this case.
     *
     * @return whether this close was THIS case's close and was accepted as such
     */
    public boolean offerClose(Object closedView, String reason, Capacity capacity) {
        // Identity, not equality by kind: a Bukkit view IS the window that is open, and the actor
        // has more than one of them over a run. Two windows of the same type are two windows, and a
        // case that matched on the type would adjudicate somebody else's close as its own.
        if (!armed() || closedView != view) {
            return false;
        }
        int free;
        try {
            free = capacity.read();
        } catch (Throwable failure) {
            // Decided BEFORE anything is recorded about the close. A case that recorded the close
            // first would stand as a close with no acknowledgement, which reads as "the client did
            // not close it" rather than as "this run could not be measured".
            finish(Outcome.EXCEPTION);
            return false;
        }
        closed = true;
        slotsFree = free;
        // The caller's reading of the event, carried through as it was read. Nothing is invented: a
        // reason this scope has no evidence for reaches the receipt verbatim and is rejected there.
        //
        // Paper names the origin (PLAYER) and that is the strongest evidence, so it is preferred.
        // Spigot has no InventoryCloseEvent.getReason() at all, so the caller passes UNSPECIFIED.
        // There, the acknowledgement rests on a fact this class can check rather than one the
        // server reports: whether a probe-side close was in progress at this instant.
        //
        // That question is asked of ProbeInitiatedClose, which every probe-side closeInventory()
        // goes through, instead of a flag someone has to remember to set. An earlier version used
        // such a flag and nothing ever set it, which made this guard decorative — the failure mode
        // is silent, so the check must not depend on a caller's discipline.
        ack = "PLAYER".equals(reason) ? "CLIENT"
            : "UNSPECIFIED".equals(reason) && !ProbeInitiatedClose.inProgress() ? "CLIENT"
            : String.valueOf(reason);
        return true;
    }

    /** Whether this case's own armed view was observed closing. */
    public boolean closed() {
        return closed;
    }

    /** The client acknowledgement read off the accepted close. */
    public String ack() {
        return ack;
    }

    /** Free slots as measured at the accepted close, before vanilla emptied the grid. */
    public int slotsFree() {
        return slotsFree;
    }

    /** How this case ended, or {@link Outcome#ARMED} while it has not. */
    public Outcome outcome() {
        return outcome;
    }

    /** Whether the listener this case armed has been given up. */
    public boolean released() {
        return released;
    }

    /** Whether the cleanup this case owed ran to completion. */
    public boolean cleanupRan() {
        return cleanupRan;
    }

    /** Whether the cleanup was attempted and threw. A case like that did NOT end cleanly. */
    public boolean cleanupFailed() {
        return cleanupFailure != null;
    }

    /** What the cleanup threw, kept for the diagnostic rather than swallowed. */
    public Throwable cleanupFailure() {
        return cleanupFailure;
    }

    /**
     * Ends this case: runs the cleanup it owed, once, and gives the case up.
     *
     * <p>Idempotent — a case that already ended keeps the outcome it ended under, so a late close or
     * a second release cannot rewrite what was reported.
     *
     * <p>A cleanup that throws is not a clean ending: the failure is kept for the caller's
     * diagnostic and the outcome is recorded as {@link Outcome#EXCEPTION}, because a case whose room
     * is still taken has left the world in a state the next case will be read against. The release
     * itself happens in a {@code finally}, so a throwing cleanup cannot also leak the subscription.
     */
    public void finish(Outcome outcome) {
        if (outcome == null || outcome == Outcome.ARMED) {
            throw new IllegalArgumentException("a case must end under a terminal outcome");
        }
        if (!armed()) {
            return;
        }
        this.outcome = outcome;
        Cleanup owed = cleanup;
        // Cleared before it runs, so a throwing cleanup is still attempted exactly once.
        cleanup = null;
        try {
            if (owed != null) {
                owed.run();
            }
            cleanupRan = true;
        } catch (Throwable failure) {
            cleanupFailure = failure;
            this.outcome = Outcome.EXCEPTION;
        } finally {
            released = true;
        }
    }

    /**
     * The slots a recovery may clear, decided on THIS case's marker rather than on a material.
     *
     * <p>Both halves matter. A slot this case never filled is somebody else's, and a slot whose
     * filler has been replaced — by the returned original, or by a genuine stack of the same
     * material — no longer carries this marker. Clearing either one would delete an item this
     * harness never put there.
     *
     * @param markerAt the marker carried by whatever is in each slot now, or {@code null} for a
     *                 slot holding something this case did not stamp
     */
    public int[] reclaimable(java.util.function.IntFunction<String> markerAt) {
        int[] found = new int[filled.length];
        int size = 0;
        for (int slot : filled) {
            if (marker.equals(markerAt.apply(slot))) {
                found[size++] = slot;
            }
        }
        return java.util.Arrays.copyOf(found, size);
    }
}
