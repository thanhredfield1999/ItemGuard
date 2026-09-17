package com.itemguard.tracking;

public final class HopperTransferPolicy {

    public Action decide(boolean hasIdentity, boolean identityReady, boolean eligible) {
        return decide(hasIdentity, identityReady, eligible, true, true);
    }

    public Action decide(
        boolean hasIdentity,
        boolean identityReady,
        boolean eligible,
        boolean sourceSupported,
        boolean destinationSupported
    ) {
        if ((hasIdentity || eligible) && (!sourceSupported || !destinationSupported)) {
            return Action.CANCEL;
        }
        if (hasIdentity) {
            return identityReady ? Action.ALLOW : Action.CANCEL;
        }
        return eligible ? Action.CANCEL_AND_SCAN_SOURCE : Action.ALLOW;
    }

    public enum Action {
        ALLOW,
        CANCEL,
        CANCEL_AND_SCAN_SOURCE
    }
}
