package com.itemguard.restore;

/**
 * What a sweep and the history say about one identity.
 *
 * @param absentFromSweep       the sweep did not find the identity anywhere
 * @param sweepCompleted        the sweep finished a full pass rather than being cut short
 * @param lastAction            the last recorded action for the identity
 * @param sweepCoveredEverything every holder the sweep needed was loaded and readable
 */
public record DestructionEvidence(
    boolean absentFromSweep,
    boolean sweepCompleted,
    String lastAction,
    boolean sweepCoveredEverything
) {}
