package com.itemguard.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The session guard's refusal paths, with the server's answers stubbed.
 *
 * <p>Why stubs and not a server: {@code innodb_flush_log_at_trx_commit} is a <em>global</em>
 * variable in MySQL 8.4 (measured — {@code SET SESSION} is rejected with "should be set with SET
 * GLOBAL"), and changing the global would require {@code SYSTEM_VARIABLES_ADMIN} and would make
 * this fixture non-disposable. So the real server proves the accepting path (every test that
 * initializes a schema goes through it) and the stubs prove the refusing paths, including the
 * queries the guard actually issues: an unlisted query is an error, not a silent pass.
 */
class MySqlSchemaSessionGuardTest {

    private static Connection answering(String version, long flush, String sqlMode)
        throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("VERSION()")) return text(version);
            if (sql.contains("innodb_flush_log_at_trx_commit")) return number(flush);
            if (sql.contains("sql_mode")) return text(sqlMode);
            throw new SQLException("unexpected query in the guard: " + sql);
        });
        return connection;
    }

    private static ResultSet text(String value) throws SQLException {
        ResultSet rows = mock(ResultSet.class);
        when(rows.next()).thenReturn(true);
        when(rows.getString(1)).thenReturn(value);
        return rows;
    }

    private static ResultSet number(long value) throws SQLException {
        ResultSet rows = mock(ResultSet.class);
        when(rows.next()).thenReturn(true);
        when(rows.getLong(1)).thenReturn(value);
        return rows;
    }

    private static final String STRICT =
        "ONLY_FULL_GROUP_BY,STRICT_ALL_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,"
            + "ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION";

    @Test
    @DisplayName("a durable, strict MySQL 8 session is accepted")
    void durableStrictSessionIsAccepted() throws Exception {
        assertDoesNotThrow(() -> new MySqlSchemaManager()
            .requireDurableSession(answering("8.4.6", 1, STRICT)));
        // MySQL 8.4's own default: strict for transactional tables, which is all this schema has.
        assertDoesNotThrow(() -> new MySqlSchemaManager().requireDurableSession(
            answering("8.4.6", 1, "ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION")));
    }

    @Test
    @DisplayName("a mode without NO_ENGINE_SUBSTITUTION is refused, because a table could change engine")
    void missingNoEngineSubstitutionIsRefused() throws Exception {
        SQLException failure = assertThrows(SQLException.class, () -> new MySqlSchemaManager()
            .requireDurableSession(answering("8.4.6", 1, "STRICT_TRANS_TABLES")));
        assertTrue(failure.getMessage().contains("NO_ENGINE_SUBSTITUTION"),
            "unexpected message: " + failure.getMessage());
    }

    @Test
    @DisplayName("flush mode is read from the server and refused when it is not 1")
    void flushModeIsRefusedWhenNotOne() throws Exception {
        SQLException failure = assertThrows(SQLException.class, () -> new MySqlSchemaManager()
            .requireDurableSession(answering("8.4.6", 2, STRICT)));
        assertTrue(failure.getMessage().contains("innodb_flush_log_at_trx_commit"),
            "unexpected message: " + failure.getMessage());
        assertTrue(failure.getMessage().contains("Refusing to start"),
            "the plugin must refuse, not warn: " + failure.getMessage());
    }

    @Test
    @DisplayName("a non-strict sql_mode is refused, because it truncates instead of rejecting")
    void nonStrictModeIsRefused() throws Exception {
        SQLException failure = assertThrows(SQLException.class, () -> new MySqlSchemaManager()
            .requireDurableSession(answering("8.4.6", 1, "ONLY_FULL_GROUP_BY")));
        assertTrue(failure.getMessage().contains("sql_mode"),
            "unexpected message: " + failure.getMessage());
    }

    @Test
    @DisplayName("MySQL 5.7 and unknown versions are refused by name")
    void oldAndUnknownServersAreRefused() throws Exception {
        assertTrue(assertThrows(SQLException.class, () -> new MySqlSchemaManager()
            .requireDurableSession(answering("5.7.44", 1, STRICT)))
            .getMessage().contains("8.0"));
        assertTrue(assertThrows(SQLException.class, () -> new MySqlSchemaManager()
            .requireDurableSession(answering("unknown", 1, STRICT)))
            .getMessage().contains("8.0"));
    }

    @Test
    @DisplayName("MariaDB is refused by name rather than failing later on collation")
    void mariadbIsRefusedByName() throws Exception {
        SQLException failure = assertThrows(SQLException.class, () -> new MySqlSchemaManager()
            .requireDurableSession(answering("10.11.6-MariaDB-1:10.11.6+maria~ubu2204", 1, STRICT)));
        assertTrue(failure.getMessage().contains("MariaDB"),
            "unexpected message: " + failure.getMessage());
    }

    @Test
    @DisplayName("version parsing takes the leading number and nothing else")
    void versionParsing() {
        assertEquals(8, MySqlSchemaManager.majorVersionOf("8.4.6"));
        assertEquals(8, MySqlSchemaManager.majorVersionOf("8.4.6-log"));
        assertEquals(5, MySqlSchemaManager.majorVersionOf("5.7.44"));
        assertEquals(10, MySqlSchemaManager.majorVersionOf("10.11.6-MariaDB"));
        assertEquals(0, MySqlSchemaManager.majorVersionOf("unknown"));
        assertEquals(Integer.MAX_VALUE,
            MySqlSchemaManager.majorVersionOf("99999999999999999999"));
    }
}
