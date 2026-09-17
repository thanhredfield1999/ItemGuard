package com.itemguard.identity;

public final class PickupIdentityPolicy {

    public PickupIdentityAction resolve(IdentityTagStatus status) {
        return switch (status) {
            case COMPLETE -> PickupIdentityAction.RECORD;
            case ABSENT -> PickupIdentityAction.TAG_SOURCE;
            case CORRUPT -> PickupIdentityAction.IGNORE_CORRUPT;
        };
    }
}
