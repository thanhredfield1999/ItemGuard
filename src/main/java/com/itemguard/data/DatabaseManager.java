package com.itemguard.data;

import com.itemguard.ItemGuard;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;

public class DatabaseManager {

    private final ItemGuard plugin;
    private final String dbType;
    private Connection connection;
    private final ExecutorService executor;

    private static final String KEY_NAMESPACE = "itemguard";
    private static final String KEY_ITEM_CODE = "item_code";

    private final Map<String, Long> dupeCooldownCache = new ConcurrentHashMap<>();

    public DatabaseManager(ItemGuard plugin) {
        this.plugin = plugin;
        this.dbType = plugin.getConfigs().getDatabaseType();
        this.executor = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors(),
            r -> {
                Thread t = new Thread(r, "ItemGuard-DB");
                t.setDaemon(true);
                return t;
            }
        );
        initDatabase();
    }

    private void initDatabase() {
        switch (dbType) {
            case "MYSQL":
                initMySQL();
                break;
            case "POSTGRESQL":
                initPostgreSQL();
                break;
            default:
                initSQLite();
        }
    }

    private void initSQLite() {
        try {
            plugin.getDataFolder().mkdirs();
            File dbFile = new File(plugin.getDataFolder(), plugin.getConfigs().getSqliteFileName());
            String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
            connection = DriverManager.getConnection(url);
            connection.setAutoCommit(false);
            createTables();
            plugin.getLogger().info("SQLite database initialized: " + dbFile.getName());
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to initialize SQLite: " + e.getMessage());
        }
    }

    private void initMySQL() {
        try {
            String host = plugin.getConfigs().getMySQLHost();
            int port = plugin.getConfigs().getMySQLPort();
            String db = plugin.getConfigs().getMySQLDatabase();
            String user = plugin.getConfigs().getMySQLUsername();
            String pass = plugin.getConfigs().getMySQLPassword();
            boolean ssl = plugin.getConfigs().getMySQLSSL();

            String url = String.format(
                "jdbc:mysql://%s:%d/%s?useSSL=%s&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                host, port, db, ssl);
            connection = DriverManager.getConnection(url, user, pass);
            connection.setAutoCommit(false);
            createTables();
            plugin.getLogger().info("MySQL database connected: " + host + ":" + port);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to connect MySQL: " + e.getMessage());
        }
    }

    private void initPostgreSQL() {
        try {
            String host = plugin.getConfigs().getPostgresHost();
            int port = plugin.getConfigs().getPostgresPort();
            String db = plugin.getConfigs().getPostgresDatabase();
            String user = plugin.getConfigs().getPostgresUsername();
            String pass = plugin.getConfigs().getPostgresPassword();
            boolean ssl = plugin.getConfigs().getPostgresSSL();

            String url = String.format(
                "jdbc:postgresql://%s:%d/%s?ssl=%s",
                host, port, db, ssl);
            connection = DriverManager.getConnection(url, user, pass);
            connection.setAutoCommit(false);
            createTables();
            plugin.getLogger().info("PostgreSQL database connected: " + host + ":" + port);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to connect PostgreSQL: " + e.getMessage());
        }
    }

    private void createTables() {
        String createItemsTable = """
            CREATE TABLE IF NOT EXISTS tracked_items (
                code VARCHAR(16) PRIMARY KEY,
                item_uuid VARCHAR(36) NOT NULL,
                owner_uuid VARCHAR(36),
                owner_name VARCHAR(255),
                material VARCHAR(64),
                item_name VARCHAR(255),
                created_at BIGINT NOT NULL,
                last_seen_at BIGINT NOT NULL,
                current_count INT DEFAULT 1,
                last_location TEXT,
                INDEX idx_item_uuid (item_uuid),
                INDEX idx_owner_uuid (owner_uuid),
                INDEX idx_last_seen (last_seen_at)
            )
            """;

        String createHistoryTable = """
            CREATE TABLE IF NOT EXISTS item_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                code VARCHAR(16) NOT NULL,
                item_uuid VARCHAR(36) NOT NULL,
                action VARCHAR(32) NOT NULL,
                player_name VARCHAR(255),
                player_uuid VARCHAR(36),
                location TEXT,
                world VARCHAR(64),
                x INT,
                y INT,
                z INT,
                timestamp BIGINT NOT NULL,
                additional_data TEXT,
                INDEX idx_code (code),
                INDEX idx_item_uuid (item_uuid),
                INDEX idx_player_uuid (player_uuid),
                INDEX idx_timestamp (timestamp),
                INDEX idx_action (action)
            )
            """;

        String createStatsTable = """
            CREATE TABLE IF NOT EXISTS plugin_stats (
                id INTEGER PRIMARY KEY,
                duplicates_detected INT DEFAULT 0,
                last_updated BIGINT
            )
            """;

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(createItemsTable);
            stmt.execute(createHistoryTable);
            stmt.execute(createStatsTable);
            connection.commit();

            Statement checkStats = connection.createStatement();
            ResultSet rs = checkStats.executeQuery("SELECT COUNT(*) FROM plugin_stats");
            rs.next();
            if (rs.getInt(1) == 0) {
                stmt.execute("INSERT INTO plugin_stats (id, duplicates_detected, last_updated) VALUES (1, 0, " + System.currentTimeMillis() + ")");
                connection.commit();
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to create tables: " + e.getMessage());
            throw new RuntimeException("ItemGuard failed to initialize database tables", e);
        }
    }

    private void ensureConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            plugin.getLogger().warning("Database connection is null or closed, reinitializing...");
            initDatabase();
            if (connection == null || connection.isClosed()) {
                throw new SQLException("Failed to re-establish database connection");
            }
        }
    }

    public void executeAsync(Runnable task) {
        Runnable wrapped = () -> {
            try {
                ensureConnection();
                task.run();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Database connection error: " + e.getMessage(), e);
            }
        };
        if (plugin.getConfigs().isAsyncDatabase()) {
            executor.execute(wrapped);
        } else {
            wrapped.run();
        }
    }

    public <T> Future<T> submitAsync(Callable<T> task) {
        if (plugin.getConfigs().isAsyncDatabase()) {
            return executor.submit(task);
        } else {
            T result;
            try {
                result = task.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return CompletableFuture.completedFuture(result);
        }
    }

    public void saveItem(ItemData item) {
        executeAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                """
                INSERT OR REPLACE INTO tracked_items
                (code, item_uuid, owner_uuid, owner_name, material, item_name, created_at, last_seen_at, current_count, last_location)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """
            )) {
                ps.setString(1, item.getCode());
                ps.setString(2, item.getItemUuid().toString());
                ps.setString(3, item.getOwnerUuid() != null ? item.getOwnerUuid().toString() : null);
                ps.setString(4, item.getOwnerName());
                ps.setString(5, item.getMaterial() != null ? item.getMaterial().name() : null);
                ps.setString(6, item.getItemName());
                ps.setLong(7, item.getCreatedAt());
                ps.setLong(8, item.getLastSeenAt());
                ps.setInt(9, item.getCurrentCount());
                ps.setString(10, item.getLastLocation());
                ps.executeUpdate();
                connection.commit();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to save item: " + item.getCode(), e);
            }
        });
    }

    public void updateItemLocation(String code, Location loc, String ownerName, UUID ownerUuid) {
        executeAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                """
                UPDATE tracked_items SET last_seen_at = ?, last_location = ?, owner_name = ?, owner_uuid = ?, current_count = current_count + 1
                WHERE code = ?
                """
            )) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setString(2, formatLocation(loc));
                ps.setString(3, ownerName);
                ps.setString(4, ownerUuid != null ? ownerUuid.toString() : null);
                ps.setString(5, code);
                ps.executeUpdate();
                connection.commit();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to update item: " + code, e);
            }
        });
    }

    public void updateItemCount(String code, int count) {
        executeAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE tracked_items SET current_count = ?, last_seen_at = ? WHERE code = ?"
            )) {
                ps.setInt(1, count);
                ps.setLong(2, System.currentTimeMillis());
                ps.setString(3, code);
                ps.executeUpdate();
                connection.commit();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to update item count: " + code, e);
            }
        });
    }

    public void logHistory(ItemHistory history) {
        executeAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                """
                INSERT INTO item_history (code, item_uuid, action, player_name, player_uuid, location, world, x, y, z, timestamp, additional_data)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS
            )) {
                ps.setString(1, history.getCode());
                ps.setString(2, history.getItemUuid().toString());
                ps.setString(3, history.getAction());
                ps.setString(4, history.getPlayerName());
                ps.setString(5, history.getPlayerUuid() != null ? history.getPlayerUuid().toString() : null);
                ps.setString(6, history.getLocation());
                ps.setString(7, history.getWorld());
                ps.setInt(8, history.getX());
                ps.setInt(9, history.getY());
                ps.setInt(10, history.getZ());
                ps.setLong(11, history.getTimestamp());
                ps.setString(12, history.getAdditionalData());
                ps.executeUpdate();
                connection.commit();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to log history", e);
            }
        });
    }

    public void incrementDuplicateCount() {
        executeAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE plugin_stats SET duplicates_detected = duplicates_detected + 1, last_updated = ? WHERE id = 1"
            )) {
                ps.setLong(1, System.currentTimeMillis());
                ps.executeUpdate();
                connection.commit();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to update stats", e);
            }
        });
    }

    public Optional<ItemData> getItem(String code) {
        try {
            ensureConnection();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to get item (connection): " + code, e);
            return Optional.empty();
        }
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT * FROM tracked_items WHERE code = ?"
        )) {
            ps.setString(1, code);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return Optional.of(parseItem(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to get item: " + code, e);
        }
        return Optional.empty();
    }

    public Optional<ItemData> getItemByUuid(UUID itemUuid) {
        try {
            ensureConnection();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to get item by UUID (connection): " + itemUuid, e);
            return Optional.empty();
        }
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT * FROM tracked_items WHERE item_uuid = ? ORDER BY last_seen_at DESC LIMIT 1"
        )) {
            ps.setString(1, itemUuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return Optional.of(parseItem(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to get item by UUID: " + itemUuid, e);
        }
        return Optional.empty();
    }

    public List<ItemHistory> getHistory(String code, int limit) {
        try {
            ensureConnection();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to get history (connection): " + code, e);
            return Collections.emptyList();
        }
        List<ItemHistory> histories = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT * FROM item_history WHERE code = ? ORDER BY timestamp DESC LIMIT ?"
        )) {
            ps.setString(1, code);
            ps.setInt(2, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                histories.add(parseHistory(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to get history: " + code, e);
        }
        return histories;
    }

    public List<ItemData> getItemsByPlayer(UUID playerUuid) {
        try {
            ensureConnection();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to get items by player (connection): " + playerUuid, e);
            return Collections.emptyList();
        }
        List<ItemData> items = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT * FROM tracked_items WHERE owner_uuid = ? ORDER BY last_seen_at DESC LIMIT 200"
        )) {
            ps.setString(1, playerUuid.toString());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                items.add(parseItem(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to get items by player: " + playerUuid, e);
        }
        return items;
    }

    public List<ItemData> searchItems(String query) {
        try {
            ensureConnection();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to search items (connection): " + query, e);
            return Collections.emptyList();
        }
        List<ItemData> items = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
            """
            SELECT * FROM tracked_items
            WHERE owner_name LIKE ? OR code LIKE ? OR item_name LIKE ?
            ORDER BY last_seen_at DESC LIMIT 100
            """
        )) {
            String likeQuery = "%" + query + "%";
            ps.setString(1, likeQuery);
            ps.setString(2, likeQuery);
            ps.setString(3, likeQuery);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                items.add(parseItem(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to search items: " + query, e);
        }
        return items;
    }

    public int getHistoryCount(String code) {
        try {
            ensureConnection();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to get history count (connection): " + code, e);
            return 0;
        }
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT COUNT(*) FROM item_history WHERE code = ?"
        )) {
            ps.setString(1, code);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to get history count: " + code, e);
        }
        return 0;
    }

    public PluginStats getStats() {
        PluginStats stats = new PluginStats();
        try {
            ensureConnection();
        } catch (SQLException e) {
            stats.setDatabaseStatus("ERROR: " + e.getMessage());
            plugin.getLogger().log(Level.SEVERE, "Failed to get stats (connection)", e);
            return stats;
        }
        try (Statement stmt = connection.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM tracked_items");
            if (rs.next()) stats.setTotalItems(rs.getInt(1));

            rs = stmt.executeQuery("SELECT COUNT(*) FROM item_history");
            if (rs.next()) stats.setTotalHistory(rs.getInt(1));

            rs = stmt.executeQuery("SELECT duplicates_detected FROM plugin_stats WHERE id = 1");
            if (rs.next()) stats.setDuplicatesDetected(rs.getInt(1));

            stats.setDatabaseType(dbType);
            stats.setDatabaseStatus("OK");
        } catch (SQLException e) {
            stats.setDatabaseStatus("ERROR: " + e.getMessage());
            plugin.getLogger().log(Level.SEVERE, "Failed to get stats", e);
        }
        return stats;
    }

    public int getOnlineTrackedCount() {
        int count = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            for (ItemStack item : p.getInventory().getContents()) {
                if (item != null && item.getType() != Material.AIR) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    public void deleteOldHistory(int keepDays) {
        try {
            ensureConnection();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to delete old history (connection)", e);
            return;
        }
        long cutoff = System.currentTimeMillis() - (keepDays * 86400000L);
        try (PreparedStatement ps = connection.prepareStatement(
            "DELETE FROM item_history WHERE timestamp < ?"
        )) {
            ps.setLong(1, cutoff);
            int deleted = ps.executeUpdate();
            connection.commit();
            plugin.getLogger().info("Deleted " + deleted + " old history entries (older than " + keepDays + " days)");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to delete old history", e);
        }
    }

    public boolean isDuplicate(String code) {
        try {
            ensureConnection();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to check duplicate (connection): " + code, e);
            return false;
        }
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT current_count FROM tracked_items WHERE code = ?"
        )) {
            ps.setString(1, code);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getInt("current_count") > 1;
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to check duplicate: " + code, e);
        }
        return false;
    }

    public boolean canReportDuplicate(String code) {
        String key = code + "-" + (System.currentTimeMillis() / plugin.getConfigs().getDetectionCooldown());
        if (dupeCooldownCache.containsKey(key)) {
            return false;
        }
        dupeCooldownCache.put(key, System.currentTimeMillis());
        dupeCooldownCache.entrySet().removeIf(e -> e.getValue() < System.currentTimeMillis() - 60000);
        return true;
    }

    public void flush() {
        try {
            ensureConnection();
            connection.commit();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to flush database", e);
        }
    }

    public void close() {
        try {
            flush();
            executor.shutdown();
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
            plugin.getLogger().info("Database connection closed");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to close database", e);
        }
    }

    // ---------- PARSING ----------
    private ItemData parseItem(ResultSet rs) throws SQLException {
        ItemData item = new ItemData(
            rs.getString("code"),
            UUID.fromString(rs.getString("item_uuid"))
        );
        String ownerUuid = rs.getString("owner_uuid");
        if (ownerUuid != null) item.setOwnerUuid(UUID.fromString(ownerUuid));
        item.setOwnerName(rs.getString("owner_name"));
        String mat = rs.getString("material");
        if (mat != null) {
            try { item.setMaterial(Material.valueOf(mat)); } catch (Exception ignored) {}
        }
        item.setItemName(rs.getString("item_name"));
        item.setCreatedAt(rs.getLong("created_at"));
        item.setLastSeenAt(rs.getLong("last_seen_at"));
        item.setCurrentCount(rs.getInt("current_count"));
        item.setLastLocation(rs.getString("last_location"));
        return item;
    }

    private ItemHistory parseHistory(ResultSet rs) throws SQLException {
        ItemHistory h = new ItemHistory();
        h.setId(rs.getLong("id"));
        h.setCode(rs.getString("code"));
        h.setItemUuid(UUID.fromString(rs.getString("item_uuid")));
        h.setAction(rs.getString("action"));
        h.setPlayerName(rs.getString("player_name"));
        String puuid = rs.getString("player_uuid");
        if (puuid != null) h.setPlayerUuid(UUID.fromString(puuid));
        h.setLocation(rs.getString("location"));
        h.setWorld(rs.getString("world"));
        h.setX(rs.getInt("x"));
        h.setY(rs.getInt("y"));
        h.setZ(rs.getInt("z"));
        h.setTimestamp(rs.getLong("timestamp"));
        h.setAdditionalData(rs.getString("additional_data"));
        return h;
    }

    private String formatLocation(Location loc) {
        if (loc == null) return "Unknown";
        return String.format("%s (%d, %d, %d)",
            loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    // ---------- KEY HELPERS ----------
    public static String getKeyNamespace() {
        return KEY_NAMESPACE;
    }

    public static String getKeyItemCode() {
        return KEY_ITEM_CODE;
    }
}
