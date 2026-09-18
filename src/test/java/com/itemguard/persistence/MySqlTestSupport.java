package com.itemguard.persistence;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared plumbing for the tests that run against the controlled MySQL fixture: one place that
 * knows how a test gets a clean schema, so two test classes cannot drift into setting it up
 * differently and quietly proving different things.
 *
 * <p>Not a test class itself (the name does not match surefire's includes on purpose).
 */
public final class MySqlTestSupport {

    static final String IDENTITY_CODE = "AB12CD";
    static final String IDENTITY_UUID = "00000000-0000-0000-0000-000000000001";

    private static final String DEFAULT_URL =
        "jdbc:mysql://127.0.0.1:33316/itemguard_premium"
            + "?allowPublicKeyRetrieval=true&sslMode=PREFERRED&connectionTimeZone=UTC";

    private MySqlTestSupport() {
    }

    static String setting(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    public static Connection connect() throws SQLException {
        return DriverManager.getConnection(
            setting("IG_MYSQL_TEST_URL", DEFAULT_URL),
            setting("IG_MYSQL_TEST_USER", "itemguard"),
            setting("IG_MYSQL_TEST_PASSWORD", "itemguard_test_password")
        );
    }

    /** Connects, drops every table of a previous run, and initializes the schema. */
    public static Connection freshSchema() throws SQLException {
        Connection connection = connect();
        connection.setAutoCommit(false);
        dropAllTables(connection);
        new MySqlSchemaManager().initialize(connection);
        return connection;
    }

    /**
     * A repository on the fixture's own pool, for tests that exercise SQL rather than DDL.
     *
     * <p>The owner is the test's; closing it (try-with-resources) closes the pool, so a caller that
     * builds the repository this way must close the owner, not just the repository.
     */
    public static MySqlConnectionOwner owner() {
        return new MySqlConnectionOwner(
            MySqlConnectionOwner.hikariDataSource(
                setting("IG_MYSQL_TEST_URL", DEFAULT_URL),
                setting("IG_MYSQL_TEST_USER", "itemguard"),
                setting("IG_MYSQL_TEST_PASSWORD", "itemguard_test_password"),
                3,
                1,
                5_000
            ),
            3,
            ignored -> { }
        );
    }

    public static void dropTargetTablesForMigration() {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            dropAllTables(connection);
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not reset MySQL migration target", failure);
        }
    }

    static void dropAllTables(Connection connection) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET FOREIGN_KEY_CHECKS=0");
            try (ResultSet rows = statement.executeQuery("""
                    SELECT TABLE_NAME FROM information_schema.TABLES
                    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'
                    """)) {
                while (rows.next()) tables.add(rows.getString(1));
            }
            for (String table : tables) statement.execute("DROP TABLE IF EXISTS `" + table + "`");
            statement.execute("SET FOREIGN_KEY_CHECKS=1");
        }
        connection.commit();
    }

    static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    static void insertTrackedItem(Connection connection, String code, String uuid)
        throws SQLException {
        execute(connection, """
            INSERT INTO tracked_items (code, item_uuid, created_at, last_seen_at)
            VALUES ('%s', '%s', 1, 1)
            """.formatted(code, uuid));
        connection.commit();
    }

    static int count(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    static int detectionCount(Connection connection, String code) throws SQLException {
        try (var statement = connection.prepareStatement(
            "SELECT detection_count FROM tracked_items WHERE code = ?")) {
            statement.setString(1, code);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : -1;
            }
        }
    }

    static void setDetectionCount(Connection connection, String code, int value)
        throws SQLException {
        try (var statement = connection.prepareStatement(
            "UPDATE tracked_items SET detection_count = ? WHERE code = ?")) {
            statement.setInt(1, value);
            statement.setString(2, code);
            statement.executeUpdate();
        }
    }
}
