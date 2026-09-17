package com.itemguard.tracking;

public final class TagPublicationPolicy {

    public TagPublicationAction afterPersistence(
        boolean durableSuccess,
        boolean exactSourceStillPresent,
        boolean pluginEnabled
    ) {
        if (!durableSuccess) {
            return TagPublicationAction.NOOP;
        }
        if (!pluginEnabled) {
            return TagPublicationAction.RETAIN_PREPARED;
        }
        if (!exactSourceStillPresent) {
            return TagPublicationAction.ABORT;
        }
        return TagPublicationAction.PUBLISH;
    }
}
