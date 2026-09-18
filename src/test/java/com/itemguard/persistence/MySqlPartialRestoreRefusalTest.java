package com.itemguard.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A database that already carries ItemGuard's schema but is missing one of its data tables is a
 * partial restore, not a fresh install.
 *
 * <p>Measured on this artifact before the refusal existed (2026-09-18, phase C of the controlled
 * backup/restore gate): {@code mydump.sql} restored without {@code item_history}, ItemGuard
 * re-created the table empty, enabled normally and said nothing. The item identity survived and its
 * whole audit trail was gone — the plugin looked healthy on a database whose history had been
 * half-restored, which is exactly the state an operator cannot detect from the plugin's own output.
 *
 * <p>These tests pin the two halves of the correct behaviour: an existing schema must be complete
 * (refuse, naming the missing tables), and a database with no ItemGuard schema at all must still be
 * created from scratch (a first install is not a partial restore).
 *
 * <p>Requires the controlled fixture: {@code python tools/mysql-runtime/mysql_fixture.py start},
 * then {@code mvnw.cmd -Pmysql test}.
 */
@Tag("mysql")
class MySqlPartialRestoreRefusalTest {

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT 1 FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?
                """)) {
            statement.setString(1, table);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    @Test
    @DisplayName("an existing schema missing a data table is refused, and the refusal names it")
    void missingDataTableIsRefused() throws Exception {
        try (Connection connection = MySqlTestSupport.freshSchema()) {
            MySqlTestSupport.execute(connection, "DROP TABLE item_history");
            connection.commit();
            assertTrue(!tableExists(connection, "item_history"), "fixture did not drop the table");

            MySqlSchemaManager manager = new MySqlSchemaManager();
            SQLException refusal = assertThrows(
                SQLException.class,
                () -> manager.initialize(connection),
                "a partial restore must be refused instead of silently re-created"
            );
            String message = refusal.getMessage();
            assertNotNull(message);
            assertTrue(
                message.contains("item_history"),
                "the refusal must name the missing table, was: " + message
            );
        }
    }

    @Test
    @DisplayName("a database with no ItemGuard schema is still initialized from scratch")
    void emptyDatabaseIsInitialized() throws Exception {
        try (Connection connection = MySqlTestSupport.connect()) {
            connection.setAutoCommit(false);
            MySqlTestSupport.dropAllTables(connection);
            MySqlSchemaManager manager = new MySqlSchemaManager();
            assertDoesNotThrow(() -> manager.initialize(connection));
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(
                     "SELECT schema_version FROM plugin_stats WHERE id = 1")) {
                assertTrue(rows.next(), "fresh initialization did not seed plugin_stats");
                assertEquals(MySqlSchemaManager.CURRENT_SCHEMA_VERSION, rows.getInt(1));
            }
            connection.commit();
        }
    }
}
