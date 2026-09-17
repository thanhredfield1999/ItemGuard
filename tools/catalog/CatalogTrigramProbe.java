import java.nio.file.*;
import java.sql.*;
import java.text.Normalizer;
import java.util.*;
import org.sqlite.ProgressHandler;

/** Disposable synthetic feasibility probe; no product schema or existing DB access. */
public final class CatalogTrigramProbe {
    static String fold(String s) { return Normalizer.normalize(s,Normalizer.Form.NFC).toLowerCase(Locale.ROOT); }
    static void check(boolean ok,String why) { if(!ok) throw new AssertionError(why); }
    public static void main(String[] args) throws Exception {
        if(args.length!=0) throw new IllegalArgumentException("No existing paths accepted");
        Path db=Files.createTempDirectory("itemguard-trigram-").resolve("probe.db");
        System.setOut(new java.io.PrintStream(System.out,true,java.nio.charset.StandardCharsets.UTF_8));
        System.out.println("SYNTHETIC_ONLY "+db);
        var names=new ArrayList<String>();
        for(int i=0;i<100000;i++) names.add(fold(i==99999?"RareSuffix":i%5==0?"KIẾM RỒNG":"Blade"));
        names.set(99998,fold("A%_\"雪😀 Rare punctuation"));
        long start=System.nanoTime();
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+db)) {
            try(var s=c.createStatement()) {
                s.execute("CREATE TABLE items(id INTEGER PRIMARY KEY, name TEXT NOT NULL)");
                s.execute("CREATE VIRTUAL TABLE search USING fts5(name,tokenize='trigram case_sensitive 1')");
            }
            c.setAutoCommit(false);
            try(var p=c.prepareStatement("INSERT INTO items VALUES(?,?)");var f=c.prepareStatement("INSERT INTO search(rowid,name) VALUES(?,?)")) {
                for(int i=0;i<names.size();i++) {p.setInt(1,i+1);p.setString(2,names.get(i));p.addBatch();f.setInt(1,i+1);f.setString(2,names.get(i));f.addBatch();}
                p.executeBatch();f.executeBatch();c.commit();
            }
            System.out.printf(Locale.ROOT,"BUILD rows=%d ms=%.3f bytes=%d%n",names.size(),(System.nanoTime()-start)/1e6,Files.size(db));
            for(String input:List.of("RareSuffix","not-present","KIẾM RỒNG","kiếm rồng","%_\"","雪😀 ","Blade","are","a","雪😀")) {
                String q=fold(input);boolean indexed=q.codePointCount(0,q.length())>=3;
                var expected=new ArrayList<Integer>();for(int i=0;i<names.size()&&expected.size()<37;i++)if(names.get(i).contains(q))expected.add(i+1);
                String sql=indexed?"SELECT i.id FROM search JOIN items i ON i.id=search.rowid WHERE search MATCH ? AND instr(i.name,?)>0 ORDER BY i.id LIMIT 37":"SELECT id FROM items WHERE instr(name,?)>0 ORDER BY id LIMIT 37";
                long begun=System.nanoTime();
                ProgressHandler.setHandler(c,1000,new ProgressHandler(){int calls;protected int progress(){return ++calls>=2000||System.nanoTime()-begun>250000000L?1:0;}});
                try(var p=c.prepareStatement(sql)) {
                    p.setString(1,indexed?"\""+q.replace("\"","\"\"")+"\"":q);if(indexed)p.setString(2,q);
                    var actual=new ArrayList<Integer>();try(var r=p.executeQuery()){while(r.next())actual.add(r.getInt(1));}
                    check(actual.equals(expected),"oracle mismatch: "+input);
                    System.out.printf(Locale.ROOT,"QUERY text=%s indexed=%s rows=%d ms=%.3f exact=true%n",input,indexed,actual.size(),(System.nanoTime()-begun)/1e6);
                } catch(SQLException e) {check(e.getMessage().contains("SQLITE_INTERRUPT"),e.toString());System.out.println("QUERY text="+input+" indexed="+indexed+" INTERRUPT");}
                finally {ProgressHandler.clearHandler(c);}
            }
            c.commit();
            // Same transaction for source + derived index; rollback must restore both.
            try(var s=c.createStatement()) {
                s.executeUpdate("UPDATE items SET name='newunique' WHERE id=100000");
                s.executeUpdate("UPDATE search SET name='newunique' WHERE rowid=100000");
                check(hits(c,"newunique")==1 && hits(c,"raresuffix")==0,"uncommitted update mismatch");
                c.rollback();
                check(hits(c,"newunique")==0 && hits(c,"raresuffix")==1,"rollback index mismatch");
                s.executeUpdate("UPDATE items SET name='newunique' WHERE id=100000");
                s.executeUpdate("UPDATE search SET name='newunique' WHERE rowid=100000");
                c.commit();
                check(hits(c,"newunique")==1 && hits(c,"raresuffix")==0,"committed update mismatch");
                s.executeUpdate("DELETE FROM items WHERE id=100000");
                s.executeUpdate("DELETE FROM search WHERE rowid=100000");
                c.rollback();
                check(hits(c,"newunique")==1,"delete rollback lost index");
                s.executeUpdate("DELETE FROM items WHERE id=100000");
                s.executeUpdate("DELETE FROM search WHERE rowid=100000");
                c.commit();
                check(hits(c,"newunique")==0,"delete left stale result");
            }
            System.out.println("TRANSACTIONS update_commit update_rollback delete_commit delete_rollback exact=true");
        }
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+db);var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM search")) {check(r.next()&&r.getInt(1)==99999,"reopen rows");check(hits(c,"newunique")==0 && hits(c,"raresuffix")==0,"reopen stale hits");}
        System.out.println("MEASUREMENT_COMPLETE not production migration, write-sync, or full-search acceptance");
    }
    static int hits(Connection c,String term) throws SQLException {
        try(var p=c.prepareStatement("SELECT count(*) FROM search JOIN items i ON i.id=search.rowid WHERE search MATCH ? AND instr(i.name,?)>0")) {
            p.setString(1,"\""+term.replace("\"","\"\"")+"\"");p.setString(2,term);
            try(var r=p.executeQuery()){check(r.next(),"missing count");return r.getInt(1);}
        }
    }
}
