package com.itemguard.tracking;

public final class EntityPublicationLifecyclePolicy {

    public EntityPublicationCaptureAction captureAction(
        boolean inSpawnCallback,
        boolean entityValid,
        int attempt,
        int maximumAttempts
    ) {
        if (inSpawnCallback) {
            return EntityPublicationCaptureAction.DEFER;
        }
        if (entityValid) {
            return EntityPublicationCaptureAction.REQUEST;
        }
        return attempt < maximumAttempts
            ? EntityPublicationCaptureAction.DEFER
            : EntityPublicationCaptureAction.IGNORE;
    }

    public boolean canWrite(boolean entityValid, boolean digestMatches) {
        return entityValid && digestMatches;
    }
}
