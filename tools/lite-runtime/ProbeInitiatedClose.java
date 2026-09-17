package smoke;

/**
 * Whether a probe-initiated inventory close is being delivered right now.
 *
 * <p>On Paper the server names who closed a window
 * ({@code InventoryCloseEvent.Reason.PLAYER} vs {@code PLUGIN}), so a close the probe caused
 * can never be mistaken for the client's. Spigot has no such API, and the crafting-close
 * cases need that distinction: their whole point is that a REAL client close exercises the
 * packet path the plugin has to survive, which a probe-side {@code closeInventory()} skips.
 *
 * <p>The first attempt used a per-case boolean that callers were supposed to set before
 * closing. Nothing ever called it, so on Spigot every close — from any source — was accepted
 * as the client's. The guard read as protection while enforcing nothing, which is the worst
 * kind of test defect: silent, and indistinguishable from evidence.
 *
 * <p>So the flag is gone. Probe code closes inventories through
 * {@code ProbeInitiatedClose.close(viewer)}, which brackets the call with {@link #enter()} and
 * {@link #exit(boolean)}; the lifecycle asks {@link #inProgress()} at the moment the event
 * arrives. A close that did not come through that door was not started by the probe.
 *
 * <p>This class carries no Bukkit types so the lifecycle seam stays compilable — and
 * therefore testable — without a server on the classpath.
 */
public final class ProbeInitiatedClose {

    /**
     * Thread-confined by construction: Bukkit delivers InventoryCloseEvent synchronously on the
     * main thread inside the same call that requested the close, so the flag is set, observed
     * and cleared without ever leaving that thread.
     */
    private static boolean inProgress;

    private ProbeInitiatedClose() {
    }

    /** Whether a probe-initiated close is being delivered right now. */
    public static boolean inProgress() {
        return inProgress;
    }

    /** Marks the start of a probe-side close. Returns the previous state, for {@link #exit}. */
    public static boolean enter() {
        boolean previous = inProgress;
        inProgress = true;
        return previous;
    }

    /**
     * Restores the state captured by {@link #enter()}.
     *
     * <p>Callers must do this in a finally block: a listener that throws must not leave the
     * flag stuck on, or every later client close would read as probe-initiated and fail the
     * crafting cases for the wrong reason.
     */
    public static void exit(boolean previous) {
        inProgress = previous;
    }
}
