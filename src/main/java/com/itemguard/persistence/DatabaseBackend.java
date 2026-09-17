package com.itemguard.persistence;

/**
 * A database backend whose name ItemGuard understands.
 *
 * <p>Being listed here means {@code database.type} may say this value without being treated as a
 * typo. It does <strong>not</strong> mean this build can open it: that is a separate question,
 * answered by {@link DatabaseBackendPolicy#requireImplemented(DatabaseBackend)}. Keeping the two
 * apart is what stops an admin configuring a backend that silently does nothing.
 */
public enum DatabaseBackend {
    SQLITE,
    MYSQL
}
