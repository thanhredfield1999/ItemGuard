package com.itemguard;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqliteAvailabilityTest {

    @Test
    void sqliteDriverSupportsCreateInsertAndQuery() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE tracked_items (code TEXT PRIMARY KEY)");
            statement.executeUpdate("INSERT INTO tracked_items (code) VALUES ('ABCD-1234')");

            try (ResultSet result = statement.executeQuery("SELECT code FROM tracked_items")) {
                assertEquals(true, result.next());
                assertEquals("ABCD-1234", result.getString("code"));
            }
        }
    }
}
