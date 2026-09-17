package com.itemguard.restore;

/** Why a restore was refused. Each reason is shown to the operator verbatim. */
public enum RestoreRefusal {

    NONE("Allowed."),

    UNKNOWN_IDENTITY("No such tracked item, so there is nothing to restore."),

    NOT_DESTROYED("That item still exists. Restoring it would create a second copy."),

    ALREADY_RESTORED("That item has already been restored once. It cannot be restored again."),

    NOT_CONFIRMED_LOST("The loss has not been confirmed by a completed scan yet."),

    STALE_CONFIRMATION("A newer scan has run since the loss was confirmed. Confirm the loss again."),

    SEEN_IN_LATEST_EPOCH("The latest scan still found that item, so it is not lost."),

    NO_PERMISSION("You do not have permission to restore items.");

    private final String message;

    RestoreRefusal(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}
