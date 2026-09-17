package com.itemguard.persistence;

import com.itemguard.catalog.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Synthetic JDBC only: creates new databases, never accepts an existing path. */
public final class CatalogC1Probe {
    public static void main(String[] args) throws Exception {
        if(args.length!=0) throw new IllegalArgumentException("No existing database paths accepted");
        Path dir=Files.createTempDirectory("itemguard-c1-");
        System.out.println("SYNTHETIC_ONLY dir="+dir);
        for(int run=0;run<3;run++) run(dir.resolve("run-"+run+".db"),run);
        System.out.println("PASS C1 JDBC migration/query/mixed workload; NOT Paper or production SLO");
    }
    static void run(Path db,int run) throws Exception {
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+db)) {
            c.setAutoCommit(false);
            try(var s=c.createStatement()) {
                s.execute("CREATE TABLE plugin_stats(id INTEGER PRIMARY KEY,schema_version INT NOT NULL,duplicates_detected INT DEFAULT 0,last_updated BIGINT)");
                s.execute("INSERT INTO plugin_stats VALUES(1,7,0,0)");
                s.execute("CREATE TABLE item_history(id INTEGER PRIMARY KEY AUTOINCREMENT,code TEXT NOT NULL,item_uuid TEXT NOT NULL,action TEXT NOT NULL,player_name TEXT,player_uuid TEXT,location TEXT,world TEXT,x INT,y INT,z INT,timestamp BIGINT NOT NULL,additional_data TEXT)");
                s.execute("WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM seq WHERE n<1000000) INSERT INTO item_history(code,item_uuid,action,timestamp) SELECT CASE WHEN n%10000=0 THEN 'TARGET' ELSE 'OTHER1' END,CASE WHEN n%10000=0 THEN 'identity' ELSE 'noise' END,'SYNTHETIC',n/20000 FROM seq");
                s.execute("INSERT INTO item_history(code,item_uuid,action,timestamp) VALUES('TARGET','wrong','IMPOSTOR',9999),('WRONG','identity','IMPOSTOR',9999)");
                c.commit();
            }
        }
        long before=Files.size(db),start=System.nanoTime();
        var ticks=new AtomicInteger();
        // Same policy and real clock; count callback reads, not a lowered test budget.
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+db)) {
            c.setAutoCommit(false);
            new SqliteSchemaManager(100000,()->{ticks.incrementAndGet();return System.nanoTime();}).initialize(c);
        }
        System.out.printf(Locale.ROOT,"run=%d migration_ms=%.3f progress_checks=%d bytes_before=%d bytes_after=%d%n",run,ms(start),ticks.get()-1,before,Files.size(db));
        try(var owner=new SqliteConnectionOwner(db)) {
            var repo=new CatalogRepository(owner);
            String plan=owner.call(c->{try(var s=c.createStatement();var r=s.executeQuery("EXPLAIN QUERY PLAN SELECT id,action,player_name,player_uuid,location,timestamp,additional_data FROM item_history WHERE code='TARGET' AND item_uuid='identity' ORDER BY timestamp DESC,id DESC LIMIT 100")) {String p="";while(r.next())p+=r.getString(4);return p;}});
            check(plan.contains("idx_history_identity_time")&&!plan.contains("TEMP B-TREE"),plan);
            List<Double> times=new ArrayList<>();
            for(int i=0;i<20;i++) { start=System.nanoTime(); exact(repo.history("TARGET","identity").get(10,TimeUnit.SECONDS)); times.add(ms(start)); }
            System.out.println("run="+run+" plan="+plan+" history20_ms="+times);
            owner.call(c->{try(var s=c.createStatement()) {
                s.execute("WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM seq WHERE n<100000) INSERT INTO tracked_items(code,item_uuid,material,item_name,owner_name,created_at,last_seen_at) SELECT printf('C%06d',n),'u-'||n,'DIAMOND_SWORD','Blade','Lan',1,1 FROM seq");
                s.execute("UPDATE tracked_items SET item_name='RareSuffix' WHERE code='C100000'");
            }return null;});
            check(repo.find(new CatalogQuery("","",CatalogCategory.ALL,"")).get(10,TimeUnit.SECONDS).items().size()==36,"first page");
            for(String query:List.of("Blade","not-present","RareSuffix")) {
                start=System.nanoTime();
                try { var rows=repo.find(new CatalogQuery(query,"",CatalogCategory.ALL,"")).get(10,TimeUnit.SECONDS); System.out.println("run="+run+" filter="+query+" rows="+rows.items().size()+" ms="+ms(start)); }
                catch(ExecutionException failure) { Throwable cause=failure;while(cause.getCause()!=null)cause=cause.getCause();System.out.println("run="+run+" filter="+query+" error="+cause.getMessage()+" ms="+ms(start)); }
            }
            // Same serial-owner transaction path; SQL insert seam, not gameplay tracking.
            start=System.nanoTime();
            List<CompletableFuture<?>> work=new ArrayList<>();
            for(int i=0;i<30;i++) {
                work.add(owner.callAsync(c->{try(var s=c.createStatement()) {s.execute("INSERT INTO item_history(code,item_uuid,action,timestamp) VALUES('WRITE1','writer','MIXED',8888)");}return null;}));
                work.add(repo.history("TARGET","identity").thenAccept(CatalogC1Probe::exact));
            }
            CompletableFuture.allOf(work.toArray(CompletableFuture[]::new)).get(20,TimeUnit.SECONDS);
            check(owner.call(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM item_history WHERE action='MIXED'")){r.next();return r.getInt(1);}})==30,"mixed writes missing");
            System.out.println("run="+run+" mixed_30_writes_30_reads_ms="+ms(start));
            start=System.nanoTime();
            int deleted=owner.call(c->{try(var s=c.prepareStatement("DELETE FROM item_history WHERE timestamp < ?")){s.setLong(1,1);return s.executeUpdate();}});
            check(deleted==19999,"retention count "+deleted);
            check(repo.history("TARGET","identity").get(10,TimeUnit.SECONDS).size()==99,"retained target count");
            System.out.println("run="+run+" retention_deleted="+deleted+" ms="+ms(start));
        }
        try(var owner=new SqliteConnectionOwner(db)) {
            check(owner.call(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM item_history")){r.next();return r.getInt(1);}})==980033,"reopen row count");
            check(new CatalogRepository(owner).history("TARGET","identity").get(10,TimeUnit.SECONDS).size()==99,"reopen query");
        }
    }
    static void exact(List<CatalogEvent> rows) {
        check(rows.size()==100,"history row count");
        for(int i=0;i<100;i++) {long id=1000000L-i*10000L;check(rows.get(i).id()==id&&rows.get(i).timestamp()==id/20000,"identity/tie order at "+i);}
    }
    static double ms(long start) {return (System.nanoTime()-start)/1_000_000d;}
    static void check(boolean ok,String detail) {if(!ok)throw new AssertionError(detail);}
}
