package com.itemguard.dupe;

/**
 * The outcome of marking duplicate findings read.
 *
 * <p>{@code supported} is not a detail: the acknowledgement columns exist on the MySQL schema only,
 * because raising the SQLite ladder would make a database written by this build unopenable by the
 * published LITE jar. A caller must be able to tell "nothing needed reading" from "this backend
 * cannot record it", and only one of those is the operator's problem.
 */
public record FindingAcknowledgement(int acknowledged, boolean supported) {

    public static FindingAcknowledgement noneOnThisBackend() {
        return new FindingAcknowledgement(0, false);
    }

    public static FindingAcknowledgement of(int acknowledged) {
        return new FindingAcknowledgement(Math.max(0, acknowledged), true);
    }
}
