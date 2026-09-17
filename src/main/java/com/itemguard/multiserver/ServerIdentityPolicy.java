package com.itemguard.multiserver;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Chooses this server's name: configured beats remembered beats newly generated.
 *
 * <p>The order is the whole policy. A configured name is a deliberate operator decision and always
 * wins; a remembered one exists so a server that was never named keeps the same name across
 * restarts; a generated one is the last resort and is flagged as such, because until the caller
 * stores it the next start will invent a different one.
 */
public final class ServerIdentityPolicy {

    /**
     * @param configuredName the {@code multi-server.server-id} value, maybe blank
     * @param rememberedName a name from a previous start, maybe null
     * @param generator      used only when there is nothing configured and nothing remembered
     */
    public ServerIdentity resolve(String configuredName, String rememberedName,
                                  Supplier<String> generator) {
        Objects.requireNonNull(generator, "generator");
        if (configuredName != null && !configuredName.isBlank()) {
            return ServerIdentity.configured(configuredName);
        }
        if (rememberedName != null && !rememberedName.isBlank()) {
            return ServerIdentity.remembered(rememberedName);
        }
        return ServerIdentity.generated(generator.get());
    }
}
