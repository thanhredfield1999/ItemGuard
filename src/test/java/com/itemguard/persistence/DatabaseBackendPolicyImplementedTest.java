package com.itemguard.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class DatabaseBackendPolicyImplementedTest {
    private final DatabaseBackendPolicy policy = new DatabaseBackendPolicy();

    @Test
    void sqliteIsTheBackendThisBuildOpens() {
        assertEquals(DatabaseBackend.SQLITE, policy.requireImplemented(DatabaseBackend.SQLITE));
    }

    @Test
    void mysqlIsTheBackendThisPremiumBuildOpens() {
        assertEquals(DatabaseBackend.MYSQL, policy.requireImplemented(DatabaseBackend.MYSQL));
    }

    @Test
    void everyDeclaredBackendHasAnExplicitAnswer() {
        for (DatabaseBackend backend : DatabaseBackend.values()) {
            assertNotNull(policy.requireImplemented(backend), "no answer for " + backend);
        }
    }
}
