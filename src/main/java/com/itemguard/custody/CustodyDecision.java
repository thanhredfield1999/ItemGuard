package com.itemguard.custody;

/** The result of one custody observation: what happened, and the resulting state. */
public record CustodyDecision(CustodyOutcome outcome, CustodyState state) {

    public boolean counted() {
        return outcome == CustodyOutcome.TRANSFER;
    }
}
