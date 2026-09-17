package com.itemguard.multiserver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerIdentityTest {

    private final ServerIdentityPolicy policy = new ServerIdentityPolicy();

    @Test
    @DisplayName("a configured name always wins, and is not flagged as new")
    void configuredWins() {
        ServerIdentity identity = policy.resolve("survival-1", "remembered-2", () -> "generated-3");
        assertEquals("survival-1", identity.name());
        assertFalse(identity.newlyGenerated());
        assertEquals("survival-1", identity.name(), "trimmed, and the trim is what is used");
        assertEquals("survival-1", policy.resolve("  survival-1  ", null, () -> "x").name());
    }

    @Test
    @DisplayName("a blank configuration falls back to the remembered name")
    void rememberedIsUsedWhenNothingIsConfigured() {
        for (String blank : new String[] {null, "", "   "}) {
            ServerIdentity identity = policy.resolve(blank, "survival-9", () -> "generated-3");
            assertEquals("survival-9", identity.name(), "input: " + blank);
            assertFalse(identity.newlyGenerated());
        }
    }

    @Test
    @DisplayName("nothing configured and nothing remembered generates, and says so")
    void generatedIsFlaggedAsNew() {
        AtomicInteger calls = new AtomicInteger();
        ServerIdentity identity = policy.resolve(null, null, () -> {
            calls.incrementAndGet();
            return "server-7f3a";
        });
        assertEquals("server-7f3a", identity.name());
        assertTrue(identity.newlyGenerated(),
            "the caller must know it has to store this, or the next start invents another");
        assertEquals(1, calls.get());
        assertFalse(policy.resolve("survival-1", null, () -> "server-7f3a").newlyGenerated(),
            "a configured name must not be flagged, or the caller would store it forever");
    }

    @Test
    @DisplayName("a name that cannot be read in a finding is refused, whoever produced it")
    void unusableNamesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> ServerIdentity.configured("survival 1"));
        assertThrows(IllegalArgumentException.class, () -> ServerIdentity.configured("surv'ival"));
        assertThrows(IllegalArgumentException.class, () -> ServerIdentity.configured("survival;1"));
        assertThrows(IllegalArgumentException.class, () -> ServerIdentity.configured("a".repeat(65)));
        assertThrows(IllegalArgumentException.class, () -> ServerIdentity.generated("not a name"),
            "a generator producing junk must not pass silently into the schema");
        assertThrows(NullPointerException.class, () -> ServerIdentity.configured(null));
    }

    @Test
    @DisplayName("letters, digits, dot, underscore and hyphen are all usable")
    void usableNamesAreAccepted() {
        for (String name : new String[] {"survival-1", "SURVIVAL_1", "srv.1", "a", "s1"}) {
            assertEquals(name, ServerIdentity.configured(name).name());
        }
        assertEquals(64, ServerIdentity.configured("a".repeat(ServerIdentity.MAX_LENGTH)).name()
            .length());
    }
}
