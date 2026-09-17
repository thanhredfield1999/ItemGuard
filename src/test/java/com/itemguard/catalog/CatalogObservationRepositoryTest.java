package com.itemguard.catalog;

import com.itemguard.persistence.SqliteConnectionOwner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class CatalogObservationRepositoryTest {
    @TempDir Path dir;

    @Test void returnsExactIdentityInNewestScanOrderWithExplicitTruncation() throws Exception {
        try (var owner = new SqliteConnectionOwner(dir.resolve("observations.db"))) {
            seed(owner, 121);
            var result = new CatalogRepository(owner).observations("ABC123", "identity").get();
            assertEquals(100, result.rows().size(), "retained physical observations must be available");
            assertTrue(result.hasMore());
            assertEquals(121, result.rows().getFirst().id());
            assertEquals(22, result.rows().getLast().id());
            assertFalse(result.rows().getFirst().epochComplete());
            assertTrue(result.rows().stream().anyMatch(CatalogObservation::epochComplete));
            assertEquals("holder120", result.rows().getFirst().holderId());
            assertEquals(120, result.rows().getFirst().slot());
            assertEquals(9880, result.rows().getFirst().observedAt(), "order is scan/id, not mutable wall clock");
            assertThrows(UnsupportedOperationException.class, () -> result.rows().clear());
            assertTrue(new CatalogRepository(owner).observations("missing", "identity").get().rows().isEmpty());
        }
    }

    @Test void oversizedScalarKeysAreExplicitlyShortenedBeforeReturning() throws Exception {
        try (var owner = new SqliteConnectionOwner(dir.resolve("scalar.db"))) {
            seed(owner, 1);
            owner.call(c -> { try(var s=c.prepareStatement("UPDATE item_observations SET holder_id=?,holder_type=? WHERE observation_id=1")) {
                s.setString(1,"x".repeat(20000)); s.setString(2,"UNKNOWN".repeat(100)); s.executeUpdate();
            } return null; });
            var row=new CatalogRepository(owner).observations("ABC123","identity").get().rows().getFirst();
            assertTrue(row.holderId().length()<280,"holder key preview must be bounded");
            assertTrue(row.holderId().endsWith("… [rút gọn]"));
            assertTrue(row.holderType().length()<90);
        }
    }

    @Test void pruningAndBudgetErrorsNeverBecomeFalsePresenceOrAbsence() throws Exception {
        try(var owner=new SqliteConnectionOwner(dir.resolve("budget.db"))) {
            seed(owner,2000);
            var limited=new CatalogRepository(owner,1);
            assertThrows(java.util.concurrent.ExecutionException.class,()->limited.observations("ABC123","identity").get());
            assertEquals(100,new CatalogRepository(owner).observations("ABC123","identity").get().rows().size(),"populated query recovers after interrupt");
            var repository=new com.itemguard.persistence.ItemSqliteRepository(owner);
            repository.completeObservationEpoch(2000);
            assertTrue(new CatalogRepository(owner).observations("ABC123","identity").get().rows().isEmpty());
            int remaining=owner.call(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM item_observations")) {r.next();return r.getInt(1);}});
            assertEquals(0,remaining);
            assertThrows(NullPointerException.class,()->limited.observations(null,"identity"));
        }
    }

    static void seed(SqliteConnectionOwner owner, int count) {
        seed(owner, count, false);
    }

    @Test void currentSchemaRejectsNullSlotWithoutErasingValidObservation() throws Exception {
        try(var owner=new SqliteConnectionOwner(dir.resolve("null-slot.db"))) {
            seed(owner,1,true);
            owner.call(c->{try(var s=c.createStatement()) { s.executeUpdate("UPDATE item_observations SET slot=7"); }return null;});
            assertThrows(IllegalStateException.class,()->owner.call(c->{
                try(var s=c.createStatement()) { s.executeUpdate("UPDATE item_observations SET slot=NULL"); }
                return null;
            }));
            var row=new CatalogRepository(owner).observations("ABC123","identity").get().rows().getFirst();
            assertEquals(7,row.slot());
        }
    }

    @Test void indexedReadSurvivesLargeUnrelatedPopulationAndReopen() throws Exception {
        Path db=dir.resolve("scale.db");
        try(var owner=new SqliteConnectionOwner(db)) {
            seed(owner,100,true);
            owner.call(c->{try(var s=c.createStatement()) {
                s.execute("""
                    WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i<100000)
                    INSERT INTO item_observations(item_uuid,code,scan_epoch,holder_type,holder_id,slot,observed_at)
                    SELECT 'noise-'||i,'OTHER',9000,'CONTAINER','world-block',i,999999 FROM n
                    """);
                try(var r=s.executeQuery("EXPLAIN QUERY PLAN SELECT observation_id FROM item_observations WHERE item_uuid='identity' AND code='ABC123' ORDER BY scan_epoch DESC,observation_id DESC LIMIT 101")) {
                    var plan=new StringBuilder(); while(r.next()) plan.append(r.getString(4)).append('\n');
                    assertTrue(plan.toString().contains("idx_observation_identity_epoch"),plan.toString());
                    assertFalse(plan.toString().contains("TEMP B-TREE"),plan.toString());
                }
            }return null;});
            var result=new CatalogRepository(owner).observations("ABC123","identity").get();
            assertEquals(100,result.rows().size()); assertFalse(result.hasMore());
        }
        try(var reopened=new SqliteConnectionOwner(db)) {
            var result=new CatalogRepository(reopened).observations("ABC123","identity").get();
            assertEquals(100,result.rows().getFirst().id()); assertEquals(1,result.rows().getLast().id());
            assertFalse(result.hasMore());
        }
    }

    static void seed(SqliteConnectionOwner owner, int count, boolean noExtra) {
        owner.call(c -> {
            try (var s = c.prepareStatement("INSERT INTO item_observations(item_uuid,code,scan_epoch,epoch_complete,holder_type,holder_id,slot,observed_at) VALUES(?,?,?,?,?,?,?,?)")) {
                for (int i=0;i<count+(noExtra?0:2);i++) {
                    s.setString(1, i == count ? "other" : "identity");
                    s.setString(2, i == count+1 ? "OTHER" : "ABC123");
                    s.setLong(3, i/2); s.setInt(4, i%2);
                    s.setString(5,"PLAYER"); s.setString(6,"holder"+i); s.setInt(7,i); s.setLong(8,10000-i); s.addBatch();
                }
                s.executeBatch();
            }
            return null;
        });
    }
}
