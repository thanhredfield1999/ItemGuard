package com.itemguard.tracking;

public final class CraftOutputPolicy {

    /**
     * C1 (review 2026-09-17): the fail-closed default, kept as the no-argument form so the policy
     * on its own still says what it always said — an eligible craft whose result has no identity is
     * cancelled rather than allowed through untracked.
     */
    public Action decide(boolean hasIdentity, boolean identityReady, boolean eligible) {
        return decide(hasIdentity, identityReady, eligible, true);
    }

    /**
     * @param cancelUntrackedCraftOutput the server's choice for the only branch where cancelling
     *     blocks ordinary play. Every other branch stays fail-closed whatever this says: a tagged
     *     result is still refused (it would copy an identity), and a tagged-but-unverified result is
     *     still refused (its identity cannot be proven). This switch exists because blocking every
     *     crafted pickaxe, sword and armour piece is a large thing to do to a server, the plugin
     *     page never said it would do it, and an admin who disagrees had no way to say so.
     */
    public Action decide(
        boolean hasIdentity,
        boolean identityReady,
        boolean eligible,
        boolean cancelUntrackedCraftOutput
    ) {
        if (hasIdentity) {
            return identityReady
                ? Action.CANCEL_TAGGED_READY_COPY
                : Action.CANCEL_TAGGED_NOT_READY;
        }
        if (!eligible) {
            return Action.ALLOW_UNTRACKED;
        }
        return cancelUntrackedCraftOutput
            ? Action.CANCEL_UNTAGGED_ELIGIBLE
            : Action.ALLOW_UNTAGGED_ELIGIBLE;
    }

    /**
     * The message key for a refusal, decided next to the actions it names.
     *
     * H3 of the third review: the mapping used to live in `CraftListener` as a binary condition, and
     * it was inverted - the copy refusal fell through to the message that told an admin to change
     * `tracking.cancel-untracked-craft-output`, a key `decide` never reaches once the result carries
     * an identity. A pure function here means the mapping is unit-tested rather than eyeballed, and
     * the switch names every constant, so an action added later cannot inherit someone else's
     * wording: it will not compile until it is given one.
     */
    public static String messageKeyFor(Action action) {
        return switch (action) {
            case CANCEL_UNTAGGED_ELIGIBLE -> "craft-refused";
            case CANCEL_TAGGED_NOT_READY -> "craft-refused-tag-pending";
            case CANCEL_TAGGED_READY_COPY -> "craft-refused-tagged";
            // Both are allowed through and never reach a message; mapped to the key whose text makes
            // no claim about being blocked, in case a future caller asks before filtering.
            case ALLOW_UNTRACKED, ALLOW_UNTAGGED_ELIGIBLE -> "craft-refused";
        };
    }

    public enum Action {
        ALLOW_UNTRACKED,
        ALLOW_UNTAGGED_ELIGIBLE,
        CANCEL_UNTAGGED_ELIGIBLE,
        CANCEL_TAGGED_NOT_READY,
        CANCEL_TAGGED_READY_COPY
    }
}
