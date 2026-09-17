package com.itemguard.tracking;

public final class GroundItemMergePolicy {

    public Action decide(boolean sourceHasIdentity, boolean targetHasIdentity) {
        return sourceHasIdentity || targetHasIdentity ? Action.CANCEL : Action.ALLOW;
    }

    public enum Action {
        ALLOW,
        CANCEL
    }
}
