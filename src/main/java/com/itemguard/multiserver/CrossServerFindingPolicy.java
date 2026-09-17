package com.itemguard.multiserver;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The cross-server rule (design §4 of {@code docs/design/2026-09-16-premium-mysql-contract.md}).
 *
 * <p>It exists because the single-server rule cannot be reused here, and reusing it was never an
 * option: a duplicate is confirmed from two exact observations inside one completed
 * {@code scan_epoch}, and epochs are minted per process, so two servers never share one. The
 * second rule set is therefore separate on purpose — adding the cross-server case must not be
 * able to weaken the epoch rule that has runtime evidence behind it, and a change to this class
 * cannot touch that one.
 *
 * <p>What it answers: has this identity been seen on more than one server inside the window? Not
 * "is it duplicated" — see {@link CrossServerStatus}.
 */
public final class CrossServerFindingPolicy {

    /**
     * @param now          the reference time, so the rule is testable without a clock
     * @param windowMillis how far back a sighting still counts
     * @throws IllegalArgumentException when the window is not positive: a zero or negative window
     *         would report nothing while looking configured, which is the class of setting this
     *         project keeps finding (a value that cannot do what its name says)
     */
    public CrossServerAssessment assess(UUID itemUuid, long now, long windowMillis,
                                        Collection<CrossServerSighting> sightings) {
        Objects.requireNonNull(itemUuid, "itemUuid");
        Objects.requireNonNull(sightings, "sightings");
        if (windowMillis <= 0) {
            throw new IllegalArgumentException(
                "A cross-server window of " + windowMillis + " ms reports nothing; it must be "
                    + "positive so that a configured window and a working one are the same thing."
            );
        }
        long earliest = now >= windowMillis ? now - windowMillis : 0;
        TreeSet<String> servers = new TreeSet<>();
        for (CrossServerSighting sighting : sightings) {
            if (sighting == null || !itemUuid.equals(sighting.itemUuid()) || sighting.expired()) {
                continue;
            }
            if (sighting.observedAt() > now || sighting.observedAt() < earliest) {
                continue;
            }
            servers.add(sighting.serverId());
        }
        if (servers.isEmpty()) {
            return new CrossServerAssessment(CrossServerStatus.NONE, List.of(), 0);
        }
        if (servers.size() < 2) {
            // One server is not a finding, but naming it is still useful: the caller can see which
            // server the identity was last seen on. The count always matches the list, because an
            // assessment whose number disagrees with its own names is a bug waiting to be printed.
            return new CrossServerAssessment(CrossServerStatus.NONE, List.copyOf(servers), 1);
        }
        return new CrossServerAssessment(CrossServerStatus.SEEN_ON_MULTIPLE_SERVERS,
            List.copyOf(servers), servers.size());
    }
}
