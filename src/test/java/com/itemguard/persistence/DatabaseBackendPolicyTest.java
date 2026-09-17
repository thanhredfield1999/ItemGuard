package com.itemguard.persistence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DatabaseBackendPolicyTest {

    private final DatabaseBackendPolicy policy = new DatabaseBackendPolicy();

    @Test
    void sqliteIsTheDefaultBackend() {
        assertEquals(DatabaseBackend.SQLITE, policy.requireSupported("SQLITE"));
        assertEquals(DatabaseBackend.SQLITE, policy.requireSupported(" sqlite "));
    }

    /**
     * MySQL is a known backend from 2026-09-16 (Premium work). Recognising the name is a
     * separate question from being able to use it, and this test only answers the first — see
     * {@link DatabaseBackendPolicyImplementedTest} for the second.
     */
    @Test
    void mysqlIsRecognisedAsAConfiguredBackend() {
        assertEquals(DatabaseBackend.MYSQL, policy.requireSupported("MYSQL"));
        assertEquals(DatabaseBackend.MYSQL, policy.requireSupported(" mysql "));
    }

    /**
     * The rejection message is what an admin sees at startup after editing the wrong key, so the
     * list of names that fail has to stay honest: unknown and empty values are still refused
     * rather than quietly resolved to a working backend.
     */
    @Test
    void unknownBackendsAreRejectedInsteadOfFallingBack() {
        assertThrows(IllegalArgumentException.class, () -> policy.requireSupported("POSTGRESQL"));
        assertThrows(IllegalArgumentException.class, () -> policy.requireSupported("mongo"));
        assertThrows(IllegalArgumentException.class, () -> policy.requireSupported(""));
        assertThrows(IllegalArgumentException.class, () -> policy.requireSupported("  "));
        assertThrows(IllegalArgumentException.class, () -> policy.requireSupported(null));
    }
}
