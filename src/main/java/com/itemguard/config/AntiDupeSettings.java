package com.itemguard.config;

import org.bukkit.configuration.file.FileConfiguration;

public record AntiDupeSettings(
    boolean enabled,
    String action,
    boolean notifyStaff,
    boolean notifyPlayer,
    long detectionCooldown,
    int maxHistoryPerItem,
    long gracePeriod,
    boolean sweepEnabled,
    int sweepChunksPerTick
) {
    public static AntiDupeSettings from(FileConfiguration config) {
        return new AntiDupeSettings(
            config.getBoolean("anti-dupe.enabled", false),
            config.getString("anti-dupe.action", "NOTIFY"),
            config.getBoolean("anti-dupe.notify-staff", true),
            config.getBoolean("anti-dupe.notify-player", false),
            detectionCooldown(config),
            config.getInt("anti-dupe.max-history-per-item", 1_000),
            config.getLong("anti-dupe.grace-period-ms", 3_000L),
            config.getBoolean("anti-dupe.sweep.enabled", true),
            Math.max(1, config.getInt("anti-dupe.sweep.chunks-per-tick", 8))
        );
    }

    /**
     * The anti-repeat window, floored at <em>two</em> scan cycles.
     *
     * <p>A cooldown shorter than the interval between two scans cannot suppress anything: the same
     * identity is re-detected one cycle later, always outside the window, so staff are re-alerted
     * for as long as the duplicate exists and a row is written every cycle. The shipped default was
     * 5 seconds against a 30-second cycle, which is exactly that case — the setting looked like a
     * throttle and was inert.
     *
     * <p>Two cycles, not one. The time window is only meaningful relative to the real gap between
     * audits, and that gap is not the configured cycle: a sweep that overruns, or a server below
     * 20 TPS, makes it larger, at which point `delta >= cooldown` and the window suppresses nothing
     * (H5 of the second review). The audit also refuses to re-report the same identity in two
     * consecutive epochs — see the second clause in `insertConfirmedDuplicateFindings` — so this
     * value no longer carries the whole guarantee on its own. It still sets the operator-facing
     * window, and the floor keeps a lowered value from becoming "alert every audit".
     *
     * <p>Floored rather than validated-and-refused: a server owner lowering this value wants a
     * tighter detection window than the default, not a startup failure - and the floor is precisely
     * what stops that request from degenerating into "alert on every scan", which is the setting it
     * would otherwise become. Refusing to start over a number the plugin can clamp would help
     * nobody.
     */
    static long detectionCooldown(FileConfiguration config) {
        long intervalTicks = Math.max(1L, config.getLong("performance.inventory-scan-interval", 600L));
        // Clamped before multiplying: a nonsense interval used to overflow into a negative cycle,
        // and then into a negative floor, so the setting silently became 0 instead of a window.
        long cycleMillis = intervalTicks > Long.MAX_VALUE / 100L
            ? Long.MAX_VALUE
            : intervalTicks * 50L;
        long floor = cycleMillis > Long.MAX_VALUE / 2 ? Long.MAX_VALUE : cycleMillis * 2;
        long configured = config.getLong("anti-dupe.detection-cooldown-ms", 300_000L);
        return Math.max(configured, floor);
    }
}
