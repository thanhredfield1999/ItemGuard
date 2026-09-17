package com.itemguard.custody;

/**
 * The window during which one pair of players can only raise the hand-over count once.
 *
 * <p>Deliberately separate from the duplicate-detection cooldown, which answers a different
 * question (how often the same identity may be reported) and is operator-tunable: an admin may
 * shorten it, and it is floored to two scan cycles - a number that moves with the config and with
 * the server's tick rate, so it cannot be leaned on as a duration. A custody window is about
 * discouraging farming, so it is measured in minutes and is not configurable per server.
 */
public final class CustodyWindow {

    /** Fifteen minutes: long enough that repeated swapping between one pair stops being profitable. */
    public static final long DEFAULT_MILLIS = 15 * 60_000L;

    public static final String CONFIG_PATH = "tracking.custody-window-ms";

    private CustodyWindow() {
    }

    /** Resolves a configured value, falling back to the default and clamping negatives to zero. */
    public static long resolve(Long configured) {
        if (configured == null) {
            return DEFAULT_MILLIS;
        }
        return Math.max(0L, configured);
    }
}
