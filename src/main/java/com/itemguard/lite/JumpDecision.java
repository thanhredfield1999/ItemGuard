package com.itemguard.lite;

/** The gate's verdict on one jump attempt. */
public record JumpDecision(boolean allowed, JumpRefusal refusal) {

    static JumpDecision allow() {
        return new JumpDecision(true, JumpRefusal.NONE);
    }

    static JumpDecision refuse(JumpRefusal refusal) {
        return new JumpDecision(false, refusal);
    }
}
