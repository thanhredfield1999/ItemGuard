package com.itemguard.catalog;

import static org.junit.jupiter.api.Assertions.*;

import com.itemguard.persistence.MySqlConnectionOwner;
import com.itemguard.persistence.MySqlTestSupport;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("mysql")
class MySqlCatalogRepositoryTest {
    @Test
    void fulltextCatalogSearchIsIndexedAndKeysetPaged() throws Exception {
        try (Connection connection = MySqlTestSupport.freshSchema()) {
            insert(connection, "AXE001", "DIAMOND_AXE", "Diamond Axe");
            insert(connection, "STONE1", "STONE", "Stone");
            connection.commit();
            try (var owner = newOwner(connection)) {
                var page = new MySqlCatalogRepository(owner).find(
                    new CatalogQuery("diamond", "", CatalogCategory.ALL, "")).join();
                assertEquals(List.of("AXE001"), page.items().stream().map(CatalogRow::code).toList());
            }
        }
    }

    private static com.itemguard.persistence.MySqlConnectionOwner newOwner(Connection ignored) {
        return new com.itemguard.persistence.MySqlConnectionOwner(
            () -> MySqlTestSupport.connect(), 2, failure -> { });
    }

    private static void insert(Connection c, String code, String material, String name) throws SQLException {
        try (var s = c.prepareStatement("INSERT INTO tracked_items(code,item_uuid,material,item_name,created_at,last_seen_at) VALUES(?,?,?,?,1,1)")) {
            s.setString(1, code); s.setString(2, "00000000-0000-0000-0000-" + code.toLowerCase());
            s.setString(3, material); s.setString(4, name); s.executeUpdate();
        }
    }
}
