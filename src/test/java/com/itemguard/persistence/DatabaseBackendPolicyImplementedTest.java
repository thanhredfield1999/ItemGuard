package com.itemguard.persistence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Separates "the config value is understood" from "this build can open it".
 *
 * <p>Without this gate, an admin who sets {@code database.type: MYSQL} would reach
 * {@code DriverManager} and be told by the JDBC layer that no suitable driver exists — or worse,
 * a future refactor could fall back to SQLite and leave a server that appears to run while
 * ignoring the file. Neither is acceptable, so the plugin refuses the value by name and says what
 * to do instead.
 */
class DatabaseBackendPolicyImplementedTest {

    private final DatabaseBackendPolicy policy = new DatabaseBackendPolicy();

    @Test
    void sqliteIsTheBackendThisBuildOpens() {
        assertEquals(DatabaseBackend.SQLITE, policy.requireImplemented(DatabaseBackend.SQLITE));
    }

    @Test
    void mysqlIsRefusedByThisBuildWithAnActionableReason() {
        UnsupportedOperationException failure = assertThrows(
            UnsupportedOperationException.class,
            () -> policy.requireImplemented(DatabaseBackend.MYSQL)
        );
        String message = failure.getMessage();
        assertNotNull(message, "a refusal an admin reads must carry a reason");
        String upper = message.toUpperCase(java.util.Locale.ROOT);
        assertTrue(
            upper.contains("MYSQL"),
            "the reason must name the backend that was refused: " + message
        );
        assertTrue(
            upper.contains("SQLITE"),
            "the reason must tell the admin what to set instead: " + message
        );
    }

    /**
     * Every backend the enum declares must get an explicit answer. Adding a constant without
     * deciding whether the build can open it is the change that would otherwise ship silently as
     * a runtime failure, so it has to fail here instead.
     */
    @Test
    void everyDeclaredBackendIsEitherImplementedOrExplicitlyRefused() {
        for (DatabaseBackend backend : DatabaseBackend.values()) {
            try {
                assertNotNull(policy.requireImplemented(backend), "no answer for " + backend);
            } catch (UnsupportedOperationException refusal) {
                assertNotNull(refusal.getMessage(), "silent refusal for " + backend);
            }
        }
    }
}
