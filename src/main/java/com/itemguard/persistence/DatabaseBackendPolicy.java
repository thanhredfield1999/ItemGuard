package com.itemguard.persistence;

import java.util.Locale;

public final class DatabaseBackendPolicy {

    /**
     * Resolves the configured {@code database.type} to a backend name ItemGuard understands.
     *
     * <p>An unrecognised or empty value is refused rather than resolved to a default, because the
     * only way an admin ends up here is after editing a config file: silently starting on SQLite
     * would leave them with a server that looks fine and is not doing what the file says.
     */
    public DatabaseBackend requireSupported(String configuredType) {
        if (configuredType == null) {
            throw new IllegalArgumentException("Database type is required");
        }
        String normalized = configuredType.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "SQLITE" -> DatabaseBackend.SQLITE;
            case "MYSQL" -> DatabaseBackend.MYSQL;
            case "" -> throw new IllegalArgumentException("Database type is required");
            default -> throw new IllegalArgumentException(
                "Unsupported ItemGuard database backend: " + configuredType
            );
        };
    }

    /**
     * Returns the backend when this build can actually open it, and refuses it by name otherwise.
     *
     * <p>The switch deliberately has no {@code default} branch. A backend added to
     * {@link DatabaseBackend} without a decision here is a compile error, so the failure surfaces
     * while the code is being written rather than as a startup crash on someone's live server.
     *
     * <p>MySQL is a planned Premium backend (design: {@code docs/design/2026-09-16-premium-mysql-contract.md}).
     * Until it is implemented, selecting it must stop the plugin — never fall back to SQLite,
     * which would leave a server running on a database the config file says it is not using.
     */
    public DatabaseBackend requireImplemented(DatabaseBackend backend) {
        return switch (backend) {
            case SQLITE, MYSQL -> backend;
        };
    }
}
