package com.itemguard.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SqliteHistoryMigrationTest {
    @TempDir Path temp;

    @Test void versionSevenPreservesHistoryAndUnknownDataWithOrderedIndexOnReopen() throws Exception {
        Path db = temp.resolve("upgrade.db");
        try (var c = connect(db)) {
            legacy(c);
            new SqliteSchemaManager().initialize(c);
            assertEquals(8, version(c), "successful migration must publish schema eight");
            assertHistoryIndex(c);
            assertLegacyData(c);
        }
        try (var c = connect(db)) {
            new SqliteSchemaManager().initialize(c);
            assertEquals(8, version(c));
            assertHistoryIndex(c);
            assertLegacyData(c);
        }
    }

    @Test void incompatibleNamedIndexesAreRejectedWithoutRepairOrVersionStamp() throws Exception {
        var definitions=List.of(
            "CREATE INDEX idx_history_identity_time ON item_history(action)",
            "CREATE INDEX idx_history_identity_time ON item_history(code,item_uuid,timestamp,id)",
            "CREATE INDEX idx_history_identity_time ON item_history(code COLLATE NOCASE,item_uuid,timestamp DESC,id DESC)",
            "CREATE UNIQUE INDEX idx_history_identity_time ON item_history(code,item_uuid,timestamp DESC,id DESC)",
            "CREATE INDEX idx_history_identity_time ON item_history(code,item_uuid,timestamp DESC,id DESC) WHERE id>0",
            "CREATE INDEX idx_history_identity_time ON unknown_extension(k)"
        );
        for(int i=0;i<definitions.size();i++) {
            Path db=temp.resolve("conflict-"+i+".db");
            try(var c=connect(db)) {
                legacy(c);
                try(var s=c.createStatement()) { s.execute(definitions.get(i)); c.commit(); }
                assertThrows(SQLException.class,()->new SqliteSchemaManager().initialize(c),"incompatible index must reject");
                assertEquals(7,version(c)); assertLegacyData(c);
            }
            try(var c=connect(db)) { assertEquals(7,version(c)); assertLegacyData(c); }
        }
    }

    @Test void corruptMetadataIsRejectedWithoutCreatingTablesOrChangingStamp() throws Exception {
        var values=List.of("'broken'", "0", "-1", "7.5", "99", "4294967303");
        for(int i=0;i<values.size();i++) {
            try(var c=connect(temp.resolve("metadata-"+i+".db"))) {
                legacy(c);
                try(var s=c.createStatement()) { s.execute("UPDATE plugin_stats SET schema_version="+values.get(i)); c.commit(); }
                String before=metadata(c);
                assertThrows(SQLException.class,()->new SqliteSchemaManager().initialize(c),"invalid metadata must reject");
                assertEquals(before,metadata(c)); assertFalse(objectExists(c,"tracked_items")); assertLegacyRows(c);
            }
        }
    }

    @Test void autoCommitRejectedBeforeAnyDdl() throws Exception {
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+temp.resolve("autocommit.db"))) {
            assertThrows(SQLException.class,()->new SqliteSchemaManager().initialize(c));
            assertFalse(objectExists(c,"tracked_items"),"autocommit rejection must precede DDL");
        }
    }

    static boolean objectExists(Connection c,String name) throws SQLException {
        try(var s=c.prepareStatement("SELECT 1 FROM sqlite_master WHERE name=?")) {
            s.setString(1,name); try(var r=s.executeQuery()) { return r.next(); }
        }
    }
    static String metadata(Connection c) throws SQLException {
        try(var s=c.createStatement(); var r=s.executeQuery("SELECT typeof(schema_version)||':'||schema_version FROM plugin_stats WHERE id=1")) { assertTrue(r.next()); return r.getString(1); }
    }
    static void assertLegacyRows(Connection c) throws SQLException {
        try(var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM item_history")) { assertTrue(r.next()); assertEquals(2,r.getInt(1)); }
    }

    @Test void stampFailureRollsBackIndexAndSchemaOnDisk() throws Exception {
        Path db=temp.resolve("stamp-failure.db");
        try(var c=connect(db)) {
            legacy(c);
            try(var s=c.createStatement()) { s.execute("CREATE TRIGGER reject_stamp BEFORE UPDATE ON plugin_stats BEGIN SELECT RAISE(ABORT,'stamp blocked'); END"); c.commit(); }
            SQLException failure=assertThrows(SQLException.class,()->new SqliteSchemaManager().initialize(c));
            assertTrue(failure.getMessage().contains("stamp blocked"));
            assertFalse(objectExists(c,"idx_history_identity_time")); assertFalse(objectExists(c,"tracked_items"));
        }
        try(var c=connect(db)) {
            assertEquals(7,version(c)); assertLegacyData(c);
            assertFalse(objectExists(c,"idx_history_identity_time"));
            try(var s=c.createStatement()) { s.execute("DROP TRIGGER reject_stamp"); c.commit(); }
            new SqliteSchemaManager().initialize(c); assertEquals(8,version(c)); assertHistoryIndex(c);
        }
    }

    @Test void rollbackFailureDoesNotHideOriginalMigrationFailure() throws Exception {
        try(var c=connect(temp.resolve("rollback-failure.db"))) {
            legacy(c);
            try(var s=c.createStatement()) { s.execute("CREATE TRIGGER reject_stamp BEFORE UPDATE ON plugin_stats BEGIN SELECT RAISE(ABORT,'stamp blocked'); END"); c.commit(); }
            Connection proxy=(Connection)java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(obj,method,args)->{
                if(method.getName().equals("rollback")) throw new SQLException("rollback blocked");
                try { return method.invoke(c,args); } catch(java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
            });
            SQLException failure=assertThrows(SQLException.class,()->new SqliteSchemaManager().initialize(proxy));
            assertTrue(failure.getMessage().contains("stamp blocked"),"original migration error must survive rollback failure");
            assertEquals("rollback blocked",failure.getSuppressed()[0].getMessage());
            c.rollback();
        }
    }

    @Test void interruptedIndexRollsBackAndClearsHandlerForNextOperation() throws Exception {
        for(boolean elapsed:List.of(false,true)) {
            Path db=temp.resolve("interrupt-"+elapsed+".db");
            try(var c=connect(db)) {
                legacy(c);
                try(var s=c.createStatement()) {
                    s.execute("WITH RECURSIVE seq(n) AS (SELECT 100 UNION ALL SELECT n+1 FROM seq WHERE n<10000) INSERT INTO item_history(code,item_uuid,action,timestamp) SELECT 'NOISE','noise','SYNTHETIC',n FROM seq"); c.commit();
                }
                var calls=new java.util.concurrent.atomic.AtomicInteger();
                var manager=new SqliteSchemaManager(elapsed?100000:1,()->elapsed && calls.getAndIncrement()>0?30_000_000_001L:0L);
                SQLException failure=assertThrows(SQLException.class,()->manager.initialize(c),"index budget must interrupt before stamp");
                assertTrue(failure.getMessage().contains("history index migration budget exceeded"),"budget failure needs actionable migration diagnosis");
                assertNotNull(failure.getCause());
                assertTrue(failure.getCause().getMessage().contains("SQLITE_INTERRUPT"));
                assertEquals(7,version(c)); assertFalse(objectExists(c,"idx_history_identity_time"));
                try(var s=c.createStatement();var r=s.executeQuery("SELECT sum(timestamp) FROM item_history")) { assertTrue(r.next()); assertTrue(r.getLong(1)>0); }
                // SQLITE_INTERRUPT can auto-rollback SQLite's transaction. The owner
                // closes a failed initialization connection; recovery uses reopen.
            }
            try(var c=connect(db)) { assertEquals(7,version(c)); new SqliteSchemaManager().initialize(c); assertHistoryIndex(c); }
        }
    }

    @Test void progressHandlerIsClearedBeforeCommit() throws Exception {
        try(var c=connect(temp.resolve("clear-before-commit.db"))) {
            legacy(c);
            var ticks=new java.util.concurrent.atomic.AtomicLong();
            var checked=new java.util.concurrent.atomic.AtomicBoolean();
            Connection proxy=(Connection)java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(obj,method,args)->{
                if(method.getName().equals("commit")) {
                    ticks.set(31_000_000_000L);
                    try(var s=c.createStatement();var r=s.executeQuery("WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM seq WHERE n<10000) SELECT sum(n) FROM seq")) {
                        assertTrue(r.next()); assertEquals(50005000L,r.getLong(1)); checked.set(true);
                    }
                }
                try { return method.invoke(c,args); } catch(java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
            });
            new SqliteSchemaManager(100000,ticks::get).initialize(proxy);
            assertTrue(checked.get()); assertEquals(8,version(c));
        }
    }

    @Test void missingOrNullMetadataIsRejected() throws Exception {
        for(boolean missing:List.of(true,false)) {
            try(var c=connect(temp.resolve("missing-"+missing+".db"))) {
                try(var s=c.createStatement()) {
                    s.execute("CREATE TABLE plugin_stats(id INTEGER PRIMARY KEY,schema_version INT,duplicates_detected INT,last_updated BIGINT)");
                    if(!missing) s.execute("INSERT INTO plugin_stats VALUES(1,NULL,0,0)"); c.commit();
                }
                assertThrows(SQLException.class,()->new SqliteSchemaManager().initialize(c));
                assertFalse(objectExists(c,"tracked_items")); assertFalse(objectExists(c,"idx_history_identity_time"));
            }
        }
    }

    static Connection connect(Path path) throws SQLException {
        var c = DriverManager.getConnection("jdbc:sqlite:" + path);
        c.setAutoCommit(false);
        return c;
    }
    // Frozen pre-index DDL, independent of the initializer under test.
    static void legacy(Connection c) throws SQLException {
        try (var s = c.createStatement()) {
            s.execute("CREATE TABLE plugin_stats(id INTEGER PRIMARY KEY,schema_version INT NOT NULL,duplicates_detected INT DEFAULT 0,last_updated BIGINT)");
            s.execute("INSERT INTO plugin_stats VALUES(1,7,17,123)");
            s.execute("CREATE TABLE item_history(id INTEGER PRIMARY KEY AUTOINCREMENT,code VARCHAR(16) NOT NULL,item_uuid VARCHAR(36) NOT NULL,action VARCHAR(32) NOT NULL,player_name VARCHAR(255),player_uuid VARCHAR(36),location TEXT,world VARCHAR(64),x INT,y INT,z INT,timestamp BIGINT NOT NULL,additional_data TEXT)");
            s.execute("INSERT INTO item_history(id,code,item_uuid,action,timestamp,additional_data) VALUES(41,'ABC123','identity','DROP',100,'unknown payload'),(42,'ABC123','identity','PICKUP',100,'keep')");
            s.execute("CREATE TABLE unknown_extension(k TEXT,v BLOB)");
            s.execute("INSERT INTO unknown_extension VALUES('opaque',X'0001FEFF')");
            s.execute("CREATE INDEX unknown_history_index ON item_history(action)");
            c.commit();
        }
    }
    static int version(Connection c) throws SQLException {
        try (var s=c.createStatement(); var r=s.executeQuery("SELECT schema_version FROM plugin_stats WHERE id=1")) { assertTrue(r.next()); return r.getInt(1); }
    }
    static void assertHistoryIndex(Connection c) throws SQLException {
        List<String> keys=new ArrayList<>();
        try (var s=c.createStatement(); var r=s.executeQuery("PRAGMA index_xinfo('idx_history_identity_time')")) {
            while(r.next()) if(r.getInt("key")==1) keys.add(r.getString("name")+":"+r.getInt("desc")+":"+r.getString("coll"));
        }
        assertEquals(List.of("code:0:BINARY","item_uuid:0:BINARY","timestamp:1:BINARY","id:1:BINARY"),keys);
        try (var s=c.createStatement(); var r=s.executeQuery("EXPLAIN QUERY PLAN SELECT id,action FROM item_history WHERE code='ABC123' AND item_uuid='identity' ORDER BY timestamp DESC,id DESC LIMIT 100")) {
            String plan=""; while(r.next()) plan+=r.getString(4);
            assertTrue(plan.contains("idx_history_identity_time"),plan);
            assertFalse(plan.contains("TEMP B-TREE"),plan);
        }
    }
    static void assertLegacyData(Connection c) throws SQLException {
        try(var s=c.createStatement(); var r=s.executeQuery("SELECT id,additional_data FROM item_history ORDER BY id")) {
            assertTrue(r.next()); assertEquals(41,r.getInt(1)); assertEquals("unknown payload",r.getString(2));
            assertTrue(r.next()); assertEquals(42,r.getInt(1)); assertEquals("keep",r.getString(2)); assertFalse(r.next());
        }
        try(var s=c.createStatement(); var r=s.executeQuery("SELECT hex(v) FROM unknown_extension")) { assertTrue(r.next()); assertEquals("0001FEFF",r.getString(1)); }
        try(var s=c.createStatement(); var r=s.executeQuery("SELECT duplicates_detected,last_updated FROM plugin_stats WHERE id=1")) { assertTrue(r.next()); assertEquals(17,r.getInt(1)); assertEquals(123,r.getInt(2)); }
        try(var s=c.createStatement(); var r=s.executeQuery("SELECT 1 FROM sqlite_master WHERE name='unknown_history_index'")) { assertTrue(r.next()); }
    }
}
