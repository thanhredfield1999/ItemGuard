package com.itemguard.persistence;

/**
 * Thrown when a write asked to hold the lock on an identity that no longer exists.
 *
 * <p>This is not a database failure: it means the row the caller intended to modify is gone, and
 * the write must not proceed on the assumption that it is there. A caller that legitimately wants
 * to create an identity does that through the publication path, whose first write is protected by
 * the unique keys on {@code tracked_items.code} and {@code tracked_items.item_uuid} — not by this
 * lock, which only ever protects a row that already exists.
 */
public final class NoSuchIdentityException extends Exception {

    private final String code;

    public NoSuchIdentityException(String code) {
        super("No tracked item with code " + code + ": refusing to proceed while holding no lock");
        this.code = code;
    }

    public String code() {
        return code;
    }
}
