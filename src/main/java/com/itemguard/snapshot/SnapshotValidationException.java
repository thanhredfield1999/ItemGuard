package com.itemguard.snapshot;

public final class SnapshotValidationException extends RuntimeException {

    public SnapshotValidationException(String message) {
        super(message);
    }

    public SnapshotValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
