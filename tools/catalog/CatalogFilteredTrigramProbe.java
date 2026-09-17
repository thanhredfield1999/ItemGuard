import java.nio.file.*;
import java.sql.*;
import java.text.Normalizer;
import java.util.*;
import org.sqlite.ProgressHandler;

/** Synthetic-only experiment; no product or existing database access. */
public final class CatalogFilteredTrigramProbe {
    record Row(int id,String code,String name,String material,String owner) {}
    record Query(String text,String owner,String material,int cursor) {}
    static String fold(String s){return Normalizer.normalize(s,Normalizer.Form.NFC).toLowerCase(Locale.ROOT);}
    static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
    public static void main(String[] args)throws Exception {
        if(args.length!=0)throw new IllegalArgumentException("No existing DB paths");
        System.setOut(new java.io.PrintStream(System.out,true,java.nio.charset.StandardCharsets.UTF_8));
        Path db=Files.createTempDirectory("itemguard-filtered-trigram-").resolve("probe.db");
        var rows=new ArrayList<Row>();
        for(int i=1;i<=100000;i++)rows.add(new Row(i,String.format(Locale.ROOT,"C%06d",i),fold(i==100000?"RareSuffix":i%5==0?"KIẾM RỒNG":"Blade"),i%2==0?"diamond_sword":"iron_pickaxe",i>=99990?"RareOwner":"Lan"));
        var cases=List.of(new Query("Blade","RareOwner","",0),new Query("Blade","Nobody","",0),new Query("C100000","","",0),new Query("SWORD","RareOwner","diamond_sword",0),new Query("kiếm rồng","","",99900),new Query("RareSuffix","RareOwner","diamond_sword",99990),new Query("zz","","",0),new Query("龍","","",0),new Query("Blade","Lan","diamond_sword",300),new Query("not-present","","",0));
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+db)) {
            try(var s=c.createStatement()){s.execute("CREATE TABLE items(id INTEGER PRIMARY KEY,code TEXT,name TEXT,material TEXT,owner TEXT)");}
            c.setAutoCommit(false);
            try(var p=c.prepareStatement("INSERT INTO items VALUES(?,?,?,?,?)")){for(var row:rows){p.setInt(1,row.id());p.setString(2,fold(row.code()));p.setString(3,row.name());p.setString(4,row.material());p.setString(5,row.owner());p.addBatch();}p.executeBatch();c.commit();}
            long before=Files.size(db),start=System.nanoTime();
            try(var s=c.createStatement()){s.execute("CREATE VIRTUAL TABLE search USING fts5(code,name,material,tokenize='trigram case_sensitive 1')");s.execute("INSERT INTO search(rowid,code,name,material) SELECT id,code,name,material FROM items");c.commit();}
            System.out.printf(Locale.ROOT,"BUILD rows=100000 table_bytes=%d indexed_bytes=%d index_ms=%.3f%n",before,Files.size(db),(System.nanoTime()-start)/1e6);
            int ok=0,interrupted=0;
            for(int repeat=0;repeat<3;repeat++)for(var q:cases){
                String text=fold(q.text());boolean indexed=text.codePointCount(0,text.length())>=3;
                var expected=rows.stream().filter(r->r.id()>q.cursor()&&(q.owner().isEmpty()||r.owner().equalsIgnoreCase(q.owner()))&&(q.material().isEmpty()||r.material().equals(q.material()))&&(fold(r.code()).contains(text)||r.name().contains(text)||r.material().contains(text))).limit(37).map(Row::id).toList();
                String sql="SELECT i.id FROM "+(indexed?"search JOIN items i ON i.id=search.rowid":"items i")+" WHERE "+(indexed?"search MATCH ? AND ":"")+"i.id>? AND (?='' OR i.owner=? COLLATE NOCASE) AND (?='' OR i.material=?) AND (instr(i.code,?)>0 OR instr(i.name,?)>0 OR instr(i.material,?)>0) ORDER BY i.id LIMIT 37";
                long begun=System.nanoTime();
                ProgressHandler.setHandler(c,1000,new ProgressHandler(){int calls;protected int progress(){return ++calls>=2000||System.nanoTime()-begun>250000000L?1:0;}});
                try(var p=c.prepareStatement(sql)){
                    int n=1;if(indexed)p.setString(n++,"\""+text.replace("\"","\"\"")+"\"");p.setInt(n++,q.cursor());p.setString(n++,q.owner());p.setString(n++,q.owner());p.setString(n++,q.material());p.setString(n++,q.material());for(int j=0;j<3;j++)p.setString(n++,text);
                    var actual=new ArrayList<Integer>();try(var r=p.executeQuery()){while(r.next())actual.add(r.getInt(1));}check(actual.equals(expected),"wrong results "+q);ok++;
                    System.out.printf(Locale.ROOT,"QUERY repeat=%d text=%s owner=%s material=%s cursor=%d indexed=%s rows=%d exact=true ms=%.3f%n",repeat,q.text(),q.owner(),q.material(),q.cursor(),indexed,actual.size(),(System.nanoTime()-begun)/1e6);
                }catch(SQLException e){check(e.getMessage().contains("SQLITE_INTERRUPT"),e.toString());interrupted++;System.out.println("INTERRUPT repeat="+repeat+" query="+q);}
                finally{ProgressHandler.clearHandler(c);}
            }
            System.out.println("TOTAL attempts="+(ok+interrupted)+" exact="+ok+" interrupted="+interrupted);
            c.commit();
            // Counterexample: final exact predicate cannot recover an item absent from candidate index.
            try(var s=c.createStatement()) {
                s.executeUpdate("UPDATE items SET name='changedunique' WHERE id=100000");
                try(var r=s.executeQuery("SELECT count(*) FROM search JOIN items i ON i.id=search.rowid WHERE search MATCH '\"changedunique\"' AND instr(i.name,'changedunique')>0")) {
                    check(r.next()&&r.getInt(1)==0,"expected stale-index miss not reproduced");
                }
                try(var r=s.executeQuery("SELECT count(*) FROM items WHERE name='changedunique'")) {
                    check(r.next()&&r.getInt(1)==1,"source update missing");
                }
                c.rollback();
            }
            System.out.println("COUNTEREXAMPLE source_only_update actual=1 indexed=0; exact residual cannot fix false negatives");
            for(boolean withIndex:List.of(false,true)) {
                long begun=System.nanoTime();
                for(int i=1;i<=100;i++) {
                    try(var p=c.prepareStatement("UPDATE items SET name=? WHERE id=?")) {p.setString(1,"changed-"+i);p.setInt(2,i);p.executeUpdate();}
                    if(withIndex)try(var p=c.prepareStatement("UPDATE search SET name=? WHERE rowid=?")) {p.setString(1,"changed-"+i);p.setInt(2,i);p.executeUpdate();}
                    c.commit();
                }
                System.out.printf(Locale.ROOT,"WRITE sample=100 individual_commits indexed=%s ms=%.3f%n",withIndex,(System.nanoTime()-begun)/1e6);
                // Restore same baseline before the next measurement; excluded from measured interval.
                try(var p=c.prepareStatement("UPDATE items SET name=? WHERE id=?");var f=c.prepareStatement("UPDATE search SET name=? WHERE rowid=?")) {
                    for(int i=1;i<=100;i++){p.setString(1,rows.get(i-1).name());p.setInt(2,i);p.addBatch();f.setString(1,rows.get(i-1).name());f.setInt(2,i);f.addBatch();}p.executeBatch();f.executeBatch();c.commit();
                }
            }
        }
        System.out.println("SYNTHETIC_ONLY completed; no migration or product writer proof DB="+db);
    }
}
