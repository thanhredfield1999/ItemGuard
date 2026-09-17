package com.itemguard.reclaim;

@FunctionalInterface
public interface ItemPresenceProbe {
    PresenceEvidence probe(ReclaimTarget target);
}
