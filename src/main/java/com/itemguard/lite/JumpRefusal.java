package com.itemguard.lite;

/** Why a jump to an item's location was refused. Shown to the operator verbatim. */
public enum JumpRefusal {

    NONE("Allowed."),

    NO_PERMISSION("You do not have permission to jump to item locations."),

    NOT_A_CONTAINER("That entry is not a container, so there is nowhere to jump to."),

    NO_POSITION("That entry has no recorded position."),

    WORLD_NOT_LOADED("That world is not loaded right now."),

    NO_SAFE_SPOT("There is no safe place to stand next to that container."),

    ROW_REDACTED("You cannot see the details of that entry.");

    private final String message;

    JumpRefusal(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}
