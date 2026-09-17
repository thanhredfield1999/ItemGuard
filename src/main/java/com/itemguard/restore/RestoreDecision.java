package com.itemguard.restore;

/** The gate's verdict on one restore attempt. */
public record RestoreDecision(boolean allowed, RestoreRefusal refusal) {

    static RestoreDecision allow() {
        return new RestoreDecision(true, RestoreRefusal.NONE);
    }

    static RestoreDecision refuse(RestoreRefusal refusal) {
        return new RestoreDecision(false, refusal);
    }
}
