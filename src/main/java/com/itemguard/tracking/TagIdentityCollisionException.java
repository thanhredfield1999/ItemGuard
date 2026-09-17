package com.itemguard.tracking;

/** A proposed code/UUID is already reserved; no physical write has occurred. */
public final class TagIdentityCollisionException extends IllegalStateException {
    public TagIdentityCollisionException() {
        super("Tag publication identity is already reserved");
    }
}
