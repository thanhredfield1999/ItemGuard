package com.itemguard.persistence;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MySqlHikariPoolContractTest {
    @Test
    void premiumMySqlPathUsesHikariDataSourceAndRelocatesItsLibraries() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));
        String owner = Files.readString(Path.of(
            "src/main/java/com/itemguard/persistence/MySqlConnectionOwner.java"));

        assertTrue(pom.contains("<artifactId>HikariCP</artifactId>"),
            "Premium MySQL must use a maintained pool instead of a second hand-rolled pool");
        assertTrue(pom.contains("<pattern>com.zaxxer.hikari</pattern>"),
            "Hikari must be relocated inside the plugin jar");
        assertTrue(pom.contains("<pattern>com.mysql</pattern>"),
            "MySQL Connector/J must be relocated inside the plugin jar");
        assertTrue(owner.contains("HikariDataSource"),
            "the shipping owner path must construct and own HikariDataSource");
        assertTrue(owner.contains("MysqlDataSource"),
            "the shipping path must use Connector/J's DataSource, not global DriverManager");
    }
}
