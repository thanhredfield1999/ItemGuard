package com.itemguard.config;

import com.itemguard.dupe.AntiDupeActionPolicy;
import com.itemguard.dupe.DuplicateAction;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Turns the silently-ignored anti-dupe action into a sentence an owner sees at startup.
 *
 * <p>{@code anti-dupe.action} offers {@code REMOVE_NEWER}, {@code REMOVE_OLDER} and {@code REMOVE_ALL},
 * and {@link AntiDupeActionPolicy} downgrades every one of them to {@code NOTIFY} because a destructive
 * response still has no audit-backed path. Until this class existed, an owner could set
 * {@code action: REMOVE_ALL} and see nothing anywhere — no warning, no log line, no behaviour — which
 * is the same failure the source audit kept finding: a setting that implies a choice the plugin does
 * not honour.
 *
 * <p>The text names the effective action and the way to silence the notice, because a warning that
 * cannot be acted on is just noise.
 */
public final class DestructiveAntiDupeNotice {

    private final AntiDupeActionPolicy policy;

    public DestructiveAntiDupeNotice() {
        this(AntiDupeActionPolicy::new);
    }

    DestructiveAntiDupeNotice(Supplier<AntiDupeActionPolicy> policySupplier) {
        this.policy = policySupplier.get();
    }

    /**
     * @param configuredAction the raw {@code anti-dupe.action} value, possibly null or junk
     * @return the warning to log, or empty when the configured action is exactly what will happen
     */
    public Optional<String> warningFor(String configuredAction) {
        DuplicateAction effective = policy.resolve(configuredAction, false);
        String requested = configuredAction == null ? "" : configuredAction.trim();
        if (effective.name().equalsIgnoreCase(requested)) {
            return Optional.empty();
        }
        return Optional.of(
            "anti-dupe.action=" + issued(requested) + " is refused by policy: a destructive response "
                + "has no audited path yet, so the effective action is " + effective + ". "
                + "Set anti-dupe.action: NOTIFY to silence this notice."
        );
    }

    private String issued(String requested) {
        return requested.isEmpty() ? "(blank)" : requested;
    }
}
