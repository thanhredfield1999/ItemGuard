package com.itemguard.reclaim;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

public final class ExternalPresenceProbeFactory {

    private final Function<String, Optional<String>> enabledPluginVersion;

    public ExternalPresenceProbeFactory(
        Function<String, Optional<String>> enabledPluginVersion
    ) {
        this.enabledPluginVersion = Objects.requireNonNull(
            enabledPluginVersion,
            "enabledPluginVersion"
        );
    }

    public ItemPresenceProbe playerVaultsProbe() {
        return playerVaultsProbe(ExternalAbsenceMode.STRICT);
    }

    /**
     * @param mode how to treat a plugin that is not installed; an installed plugin that cannot be read
     *             within a hard bound is {@code UNAVAILABLE} (denying) in both modes
     */
    public ItemPresenceProbe playerVaultsProbe(ExternalAbsenceMode mode) {
        Optional<DetectedPlugin> detected = detect("PlayerVaultsX")
            .or(() -> detect("PlayerVaults"));
        if (detected.isEmpty()) {
            return absent(
                mode,
                "PLAYER_VAULTS",
                "PlayerVaultsX/PlayerVaults is not installed, so it holds nothing to search"
            );
        }
        DetectedPlugin plugin = detected.orElseThrow();
        if ("PlayerVaults".equals(plugin.name())) {
            return unavailable(
                "PLAYER_VAULTS",
                "Detected " + plugin.name() + " " + plugin.version()
                    + "; official 4.4.x public API performs synchronous file I/O, may create"
                    + " a missing holder file, and has no hard scan bound, so bounded read-only"
                    + " absence cannot be proven"
            );
        }
        return unavailable(
            "PLAYER_VAULTS",
            "Detected " + plugin.name() + " " + plugin.version()
                + "; this separate fork has no public API to enumerate every player vault"
                + " and read its contents, so bounded read-only absence cannot be proven"
        );
    }

    public ItemPresenceProbe zAuctionHouseProbe() {
        return zAuctionHouseProbe(ExternalAbsenceMode.STRICT);
    }

    /** @see #playerVaultsProbe(ExternalAbsenceMode) */
    public ItemPresenceProbe zAuctionHouseProbe(ExternalAbsenceMode mode) {
        Optional<DetectedPlugin> detected = detect("zAuctionHouse");
        if (detected.isEmpty()) {
            return absent(
                mode,
                "ZAUCTIONHOUSE",
                "zAuctionHouse is not installed, so it holds nothing to search"
            );
        }
        DetectedPlugin plugin = detected.orElseThrow();
        if (plugin.version().startsWith("4.")) {
            return unavailable(
                "ZAUCTIONHOUSE",
                "Detected " + plugin.name() + " " + plugin.version()
                    + "; public API seller queries are synchronous full-bucket scans and no"
                    + " bounded read-only identity query covers listed, purchased, and expired"
                    + " items, so absence cannot be proven"
            );
        }
        if (plugin.version().startsWith("3.")) {
            return unavailable(
                "ZAUCTIONHOUSE",
                "Detected " + plugin.name() + " " + plugin.version()
                    + "; public API storage queries are synchronous full-list scans with no"
                    + " hard bound or cross-storage read consistency contract, so absence"
                    + " cannot be proven"
            );
        }
        return unavailable(
            "ZAUCTIONHOUSE",
            "Detected " + plugin.name() + " " + plugin.version()
                + "; no documented bounded read-only identity query covers listed, purchased,"
                + " and expired items, so absence cannot be proven"
        );
    }

    private Optional<DetectedPlugin> detect(String pluginName) {
        Optional<String> version = Objects.requireNonNull(
            enabledPluginVersion.apply(pluginName),
            "Plugin version lookup returned null"
        );
        return version
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .map(value -> new DetectedPlugin(pluginName, value));
    }

    private ItemPresenceProbe unavailable(String source, String reason) {
        return new UnavailableExternalPresenceProbe(source, reason);
    }

    /**
     * A plugin that is not installed: refused in STRICT mode, recorded as not-applicable in
     * INSTALLED_ONLY mode. Both paths produce evidence, so the claim detail always says which
     * sources were skipped.
     */
    private ItemPresenceProbe absent(ExternalAbsenceMode mode, String source, String reason) {
        if (mode == ExternalAbsenceMode.INSTALLED_ONLY) {
            return new SkippedExternalProbe(source, reason + " (INSTALLED_ONLY)");
        }
        return new UnavailableExternalPresenceProbe(source, reason + "; absence cannot be proven");
    }

    private record DetectedPlugin(String name, String version) {
    }
}
