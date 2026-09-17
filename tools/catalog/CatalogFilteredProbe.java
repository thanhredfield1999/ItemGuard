package com.itemguard.catalog;

import com.itemguard.persistence.SqliteConnectionOwner;
import java.nio.file.*;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.*;

/** Measurement only; creates fresh synthetic DBs, never accepts a path or changes product schema. */
public final class CatalogFilteredProbe {
    record Case(String label, CatalogQuery query) {}
    static final List<Case> CASES=List.of(
        test("first","","",CatalogCategory.ALL,""),
        test("common","Blade","",CatalogCategory.ALL,""),
        test("owner","","Lan",CatalogCategory.ALL,""),
        test("owner_sword","","Lan",CatalogCategory.SWORD,""),
        test("unicode","kiếm rồng","",CatalogCategory.ALL,""),
        test("rare_tail","RareSuffix","",CatalogCategory.ALL,""),
        test("missing_text","not-present","",CatalogCategory.ALL,""),
        test("code_contains_tail","C100000","",CatalogCategory.ALL,""),
        test("missing_owner","","Nobody",CatalogCategory.ALL,""),
        test("rare_bounded_cursor","RareSuffix","",CatalogCategory.ALL,"C099990"));
    public static void main(String[] args) throws Exception {
        if(args.length!=0) throw new IllegalArgumentException("No existing database paths accepted");
        Path dir=Files.createTempDirectory("itemguard-filtered-");
        System.out.println("SYNTHETIC_ONLY directory="+dir);
        for(int run=0;run<3;run++) run(dir.resolve("run-"+run+".db"),run);
        System.out.println("PASS_MEASUREMENT_ORACLE_AND_WRITE_PRESERVATION not general-search SLO or Paper proof");
    }
    static void run(Path db,int run) throws Exception {
        int writes=0;
        try(var owner=new SqliteConnectionOwner(db)) {
            owner.call(c->{try(var s=c.createStatement()) {
                s.execute("""
                    WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i<100000)
                    INSERT INTO tracked_items(code,item_uuid,material,item_name,owner_name,created_at,last_seen_at)
                    SELECT printf('C%06d',i),'u-'||i,
                      CASE WHEN i%2=0 THEN 'DIAMOND_SWORD' ELSE 'IRON_PICKAXE' END,
                      CASE WHEN i=100000 THEN 'RareSuffix' WHEN i%5=0 THEN 'KIẾM RỒNG' ELSE 'Blade' END,
                      CASE WHEN i%3=0 THEN 'Lan' ELSE 'Minh' END,1,1 FROM n
                    """);
            }return null;});
            var repo=new CatalogRepository(owner);
            for(var test:CASES) {
                var expected=oracle(test.query());
                for(int repeat=0;repeat<3;repeat++) {
                    long start=System.nanoTime(); String status="OK",reason=""; int count=-1;
                    try {
                        var page=repo.find(test.query()).get(10,TimeUnit.SECONDS);
                        exact(page,expected); count=page.items().size();
                    } catch(ExecutionException failure) {
                        Throwable root=failure;while(root.getCause()!=null)root=root.getCause();
                        check(root.getMessage().contains("SQLITE_INTERRUPT"),"unexpected query failure: "+root);
                        status="INTERRUPT";reason="SQLITE_INTERRUPT";
                    }
                    double queryMs=ms(start); start=System.nanoTime();
                    write(owner).get(10,TimeUnit.SECONDS);writes++;
                    System.out.printf(Locale.ROOT,"QUERY run=%d case=%s repeat=%d status=%s rows=%d expected=%d query_ms=%.3f following_write_ms=%.3f reason=%s%n",run,test.label(),repeat,status,count,Math.min(36,expected.size()),queryMs,ms(start),reason);
                }
            }
            // Reproduce one in-flight admitted scan with queued SQL writes and rejected extra viewers.
            // A latch is a deterministic queue-position seam, not a DB or clock mock.
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            var barrier=owner.callAsync(c->{entered.countDown();check(release.await(5,TimeUnit.SECONDS),"barrier timeout");return null;});
            check(entered.await(5,TimeUnit.SECONDS),"owner did not enter barrier");
            var gate=new CatalogReadGate(System::nanoTime);
            var read=gate.submit(()->repo.find(CASES.get(6).query()));
            var completions=new ArrayList<CompletableFuture<Double>>();
            int rejected=0;
            long start=System.nanoTime();
            try {
                for(int i=0;i<20;i++) {
                    long submitted=System.nanoTime();
                    completions.add(write(owner).thenApply(ignored->ms(submitted)));
                }
                for(int i=0;i<5;i++) {
                    var denied=gate.submit(()->{throw new AssertionError("busy admission invoked query");});
                    try {denied.get(1,TimeUnit.SECONDS);throw new AssertionError("busy accepted");}
                    catch(ExecutionException failure) {check(failure.getCause() instanceof IllegalStateException,"wrong rejection");rejected++;}
                }
            } finally {release.countDown();}
            String outcome="OK";
            try {exact(read.get(10,TimeUnit.SECONDS),List.of());}
            catch(ExecutionException failure) {Throwable root=failure;while(root.getCause()!=null)root=root.getCause();check(root.getMessage().contains("SQLITE_INTERRUPT"),"unexpected mixed error: "+root);outcome="INTERRUPT";}
            barrier.get(10,TimeUnit.SECONDS);
            CompletableFuture.allOf(completions.toArray(CompletableFuture[]::new)).get(10,TimeUnit.SECONDS);
            writes+=completions.size();
            var times=completions.stream().map(CompletableFuture::join).sorted().toList();
            System.out.printf(Locale.ROOT,"MIXED run=%d read=%s writes=%d denied_readers=%d total_ms=%.3f write_p50_ms=%.3f write_max_ms=%.3f%n",run,outcome,completions.size(),rejected,ms(start),times.get(times.size()/2),times.getLast());
            check(count(owner,"tracked_items")==100000,"tracked row loss");
            check(count(owner,"item_history")==writes,"history write loss");
            exact(repo.find(CASES.getFirst().query()).get(10,TimeUnit.SECONDS),oracle(CASES.getFirst().query()));
        }
        try(var owner=new SqliteConnectionOwner(db)) {
            check(count(owner,"tracked_items")==100000,"reopen tracked row loss");
            check(count(owner,"item_history")==writes,"reopen write loss");
            exact(new CatalogRepository(owner).find(CASES.getLast().query()).get(10,TimeUnit.SECONDS),oracle(CASES.getLast().query()));
        }
        System.out.println("REOPEN run="+run+" tracked=100000 writes="+writes+" exact=true");
    }
    static CompletableFuture<Void> write(SqliteConnectionOwner owner) {
        return owner.callAsync(c->{try(var s=c.createStatement()) {
            s.executeUpdate("INSERT INTO item_history(code,item_uuid,action,timestamp) VALUES('WRITE1','writer','SYNTHETIC_FILTERED',1)");
        }return null;});
    }
    static int count(SqliteConnectionOwner owner,String table) {
        return owner.call(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+table)){r.next();return r.getInt(1);}});
    }
    static List<String> oracle(CatalogQuery q) {
        var result=new ArrayList<String>();
        for(int i=1;i<=100000 && result.size()<37;i++) {
            String code=String.format(Locale.ROOT,"C%06d",i),name=i==100000?"RareSuffix":i%5==0?"KIẾM RỒNG":"Blade";
            String material=i%2==0?"DIAMOND_SWORD":"IRON_PICKAXE",owner=i%3==0?"Lan":"Minh";
            if(code.compareTo(q.afterCode())<=0)continue;
            if(!q.text().isEmpty()&&!fold(name).contains(fold(q.text()))&&!fold(code).contains(fold(q.text()))&&!fold(material).contains(fold(q.text())))continue;
            if(!q.ownerName().isEmpty()&&!owner.equalsIgnoreCase(q.ownerName()))continue;
            if(q.category()==CatalogCategory.SWORD&&!material.endsWith("_SWORD"))continue;
            check(q.category()==CatalogCategory.ALL||q.category()==CatalogCategory.SWORD,"oracle category unsupported");
            result.add(code);
        }
        return result;
    }
    static void exact(CatalogPage page,List<String> expected) {
        check(page.items().stream().map(CatalogRow::code).toList().equals(expected.subList(0,Math.min(36,expected.size()))),"wrong result/order");
        check(page.hasMore()==(expected.size()>36),"wrong hasMore");
        for(var row:page.items()) check(row.itemUuid().equals("u-"+Integer.parseInt(row.code().substring(1))),"identity mismatch");
    }
    static String fold(String s) {return Normalizer.normalize(s,Normalizer.Form.NFC).toLowerCase(Locale.ROOT);}
    static Case test(String label,String text,String owner,CatalogCategory category,String cursor) {return new Case(label,new CatalogQuery(text,owner,category,cursor));}
    static double ms(long start) {return (System.nanoTime()-start)/1_000_000d;}
    static void check(boolean ok,String message) {if(!ok)throw new AssertionError(message);}
}
