import com.itemguard.catalog.CatalogRepository;
import com.itemguard.persistence.SqliteConnectionOwner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/** Offline synthetic experiment only. Never accepts an existing DB. No Paper or production access. */
public final class CatalogScaleProbe {
    public static void main(String[] args) throws Exception {
        Path dir=Files.createTempDirectory("itemguard-catalog-scale-");
        System.out.println("SYNTHETIC_ONLY database="+dir.resolve("probe.db"));
        try(var owner=new SqliteConnectionOwner(dir.resolve("probe.db"))) {
            owner.call(c->{
                try(var s=c.createStatement()) {
                    s.executeUpdate("""
                        WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM seq WHERE n<1000000)
                        INSERT INTO item_history(code,item_uuid,action,timestamp)
                        SELECT CASE WHEN n%10000=0 THEN 'TARGET' ELSE 'OTHER1' END,
                          CASE WHEN n%10000=0 THEN 'target-identity' ELSE 'other-identity' END,
                          'SYNTHETIC',n FROM seq
                        """);
                }
                return null;
            });
            var repo=new CatalogRepository(owner);
            plan(owner,"BASELINE_SCHEMA");
            long start=System.nanoTime();
            try {
                var rows=repo.history("TARGET","target-identity").get(10,TimeUnit.SECONDS);
                System.out.println("BASELINE_ROWS="+rows.size());
            } catch(ExecutionException failure) {
                System.out.println("BASELINE_ERROR="+failure.getCause().getClass().getName()+": "+failure.getCause().getMessage());
                Throwable cause=failure;
                while(cause.getCause()!=null) cause=cause.getCause();
                System.out.println("BASELINE_ROOT_CAUSE="+cause.getClass().getName()+": "+cause.getMessage());
            }
            System.out.println("BASELINE_ELAPSED_MS="+(System.nanoTime()-start)/1_000_000d);
            int count=owner.call(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM item_history")) {r.next();return r.getInt(1);}});
            if(count!=1000000) throw new AssertionError("synthetic row count "+count);
            System.out.println("AFTER_QUERY_OWNER_ROWS="+count);
            // Experimental index ONLY on this freshly generated scratch DB; not a shipped migration.
            owner.call(c->{try(var s=c.createStatement()) {s.execute("CREATE INDEX experiment_history_identity_time ON item_history(code,item_uuid,timestamp DESC,id DESC)");}return null;});
            plan(owner,"SCRATCH_INDEX_EXPERIMENT");
            start=System.nanoTime();
            var rows=repo.history("TARGET","target-identity").get(10,TimeUnit.SECONDS);
            if(rows.size()!=100 || rows.getFirst().timestamp()!=1000000 || rows.getLast().timestamp()!=10000) throw new AssertionError("exact history mismatch");
            System.out.println("INDEX_EXPERIMENT_ROWS="+rows.size()+" ELAPSED_MS="+(System.nanoTime()-start)/1_000_000d);
            System.out.println("NOT_A_PRODUCTION_BENCHMARK_OR_MIGRATION");
        }
    }
    private static void plan(SqliteConnectionOwner owner,String label) {
        owner.call(c->{try(var s=c.createStatement();var r=s.executeQuery("EXPLAIN QUERY PLAN SELECT id,action,player_name,player_uuid,location,timestamp,additional_data FROM item_history WHERE code='TARGET' AND item_uuid='target-identity' ORDER BY timestamp DESC,id DESC LIMIT 100")) {
            while(r.next()) System.out.println(label+" PLAN="+r.getString(4));
        }return null;});
    }
}
