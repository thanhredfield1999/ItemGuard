package com.itemguard.reclaim;

import java.util.Locale;

/**
 * How the capability gate treats external storage plugins that are not installed.
 *
 * <p>The requirement behind this setting: absence must be proven before an item is handed back, and an
 * adapter that is unavailable at runtime must deny rather than count as absent. Both modes honour that
 * for a plugin that <em>is</em> installed but whose contents cannot be read within a hard bound —
 * PlayerVaults, PlayerVaultsX and every zAuctionHouse line audited so far all stay {@code UNAVAILABLE}
 * and therefore deny.
 *
 * <p>The modes differ on a plugin that is not installed at all. There, {@link #STRICT} denies (nothing
 * was proven about that storage, because there is nothing to prove), which is why every reclaim is
 * refused on a server that does not run those plugins. {@link #INSTALLED_ONLY} records the source as
 * {@link PresenceStatus#NOT_APPLICABLE} in the evidence and continues, which is the only way the
 * feature can work on such a server. An operator chooses between "refuse everything until those
 * plugins can be read" and "refuse unless what I actually run can be read", and the decision is
 * written into every claim's detail either way.
 */
public enum ExternalAbsenceMode {
    STRICT,
    INSTALLED_ONLY;

    /** Anything unrecognised — including a blank key — resolves to STRICT, the refusing side. */
    public static ExternalAbsenceMode parse(String configured) {
        if (configured == null) {
            return STRICT;
        }
        try {
            return valueOf(configured.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unrecognised) {
            return STRICT;
        }
    }
}
