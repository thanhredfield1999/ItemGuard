package com.itemguard.reclaim;

import java.util.Objects;

/**
 * A source that is not installed, recorded rather than silently dropped.
 *
 * <p>In {@code INSTALLED_ONLY} mode the plugin does not query storage plugins that are not present —
 * there is nothing there to search — but the decision still has to say so, because "we checked four
 * sources" and "we checked two and skipped two" are different statements to the person judging a
 * reclaim. The evidence list carries one of these per skipped source.
 */
public final class SkippedExternalProbe implements ItemPresenceProbe {

    private final String source;
    private final String reason;

    public SkippedExternalProbe(String source, String reason) {
        this.source = Objects.requireNonNull(source, "source");
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    @Override
    public PresenceEvidence probe(ReclaimTarget target) {
        Objects.requireNonNull(target, "target");
        return new PresenceEvidence(source, PresenceStatus.NOT_APPLICABLE, reason);
    }
}
