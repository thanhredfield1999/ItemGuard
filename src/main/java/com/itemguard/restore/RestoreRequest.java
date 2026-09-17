package com.itemguard.restore;

/**
 * Everything the gate needs to judge one restore attempt.
 *
 * @param code                    the public item code being restored
 * @param state                   what the records say about the identity
 * @param alreadyRestored         whether this identity has ever been restored before
 * @param lossConfirmedAtEpoch    the completed scan epoch that found it absent, or 0 if never
 * @param latestCompletedEpoch    the most recent completed scan epoch
 * @param presentInLatestEpoch    whether the latest completed scan still found the identity
 * @param hasPermission           whether the operator may restore items
 */
public record RestoreRequest(
    String code,
    IdentityState state,
    boolean alreadyRestored,
    long lossConfirmedAtEpoch,
    long latestCompletedEpoch,
    boolean presentInLatestEpoch,
    boolean hasPermission
) {}
