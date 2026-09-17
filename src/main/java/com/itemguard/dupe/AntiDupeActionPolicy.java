package com.itemguard.dupe;

import java.util.Locale;

public final class AntiDupeActionPolicy {

    public DuplicateAction resolve(String configuredAction, boolean destructiveReleaseGateEnabled) {
        DuplicateAction requested = parse(configuredAction);
        if (requested.isDestructive() && !destructiveReleaseGateEnabled) {
            return DuplicateAction.NOTIFY;
        }
        return requested;
    }

    private DuplicateAction parse(String configuredAction) {
        if (configuredAction == null) {
            return DuplicateAction.NOTIFY;
        }
        try {
            return DuplicateAction.valueOf(configuredAction.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return DuplicateAction.NOTIFY;
        }
    }
}
