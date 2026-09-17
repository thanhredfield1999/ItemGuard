import java.sql.*;
import java.nio.file.*;
import java.text.Normalizer;
import java.util.Locale;

/** Isolated synthetic trigger contract experiment. Never opens an existing DB. */
public final class CatalogTriggerProbe {
    static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
    static int count(Connection c,String sql)throws Exception{try(var s=c.createStatement();var r=s.executeQuery(sql)){check(r.next(),"row");return r.getInt(1);}}
    static void register(Connection c)throws Exception{org.sqlite.Function.create(c,"fold",new org.sqlite.Function(){protected void xFunc()throws SQLException{String s=value_text(0);result(Normalizer.normalize(s==null?"":s,Normalizer.Form.NFC).toLowerCase(Locale.ROOT));}},1,org.sqlite.Function.FLAG_DETERMINISTIC);}
    static int hit(Connection c,String word)throws Exception{return count(c,"SELECT count(*) FROM search WHERE search MATCH '\""+word+"\"'");}
    public static void main(String[] args)throws Exception{
        if(args.length!=0)throw new IllegalArgumentException("No existing paths");
        Path db=Files.createTempDirectory("itemguard-trigger-").resolve("probe.db");
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+db)){
            register(c);c.setAutoCommit(false);
            try(var s=c.createStatement()){
                s.execute("CREATE TABLE items(code TEXT PRIMARY KEY, name TEXT, owner TEXT)");
                s.execute("CREATE VIRTUAL TABLE search USING fts5(code,name,tokenize='trigram case_sensitive 1')");
                s.execute("CREATE TRIGGER ins AFTER INSERT ON items BEGIN INSERT INTO search(rowid,code,name) VALUES(new.rowid,fold(new.code),fold(new.name)); END");
                s.execute("CREATE TRIGGER upd AFTER UPDATE OF code,name ON items BEGIN DELETE FROM search WHERE rowid=old.rowid; INSERT INTO search(rowid,code,name) VALUES(new.rowid,fold(new.code),fold(new.name)); END");
                s.execute("CREATE TRIGGER del AFTER DELETE ON items BEGIN DELETE FROM search WHERE rowid=old.rowid; END");c.commit();
                s.executeUpdate("INSERT INTO items VALUES('CODE1','Original','Lan')");c.commit();check(hit(c,"original")==1,"insert missing");
                s.executeUpdate("INSERT INTO items VALUES('CODE1','Changed','Minh') ON CONFLICT(code) DO UPDATE SET name=excluded.name,owner=excluded.owner");check(hit(c,"changed")==1&&hit(c,"original")==0,"upsert stale");c.rollback();check(hit(c,"original")==1&&hit(c,"changed")==0,"rollback drift");
                s.executeUpdate("UPDATE items SET name='Changed' WHERE code='CODE1'");c.commit();check(hit(c,"changed")==1,"update missing");
                s.executeUpdate("UPDATE items SET owner='Other' WHERE code='CODE1'");c.commit();check(hit(c,"changed")==1,"owner corrupted index");
                // Test a genuinely new connection with no registered UDF, not driver unregister semantics.
                try(var missing=DriverManager.getConnection("jdbc:sqlite:"+db);var ms=missing.createStatement()) {
                boolean failed=false;try{ms.executeUpdate("UPDATE items SET name='Unsynced' WHERE code='CODE1'");}catch(SQLException expected){check(expected.getMessage().contains("fold"),"unexpected error "+expected);failed=true;}
                check(failed,"missing UDF allowed write");
                }
                check(count(c,"SELECT count(*) FROM items WHERE name='Changed'")==1&&hit(c,"changed")==1&&hit(c,"unsynced")==0,"failed update drift");
                s.executeUpdate("DELETE FROM items WHERE code='CODE1'");check(hit(c,"changed")==0,"delete stale");c.rollback();check(hit(c,"changed")==1,"delete rollback drift");
                s.executeUpdate("DELETE FROM items WHERE code='CODE1'");c.commit();check(hit(c,"changed")==0,"committed delete stale");
            }
        }
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+db)){check(count(c,"SELECT count(*) FROM items")==0&&count(c,"SELECT count(*) FROM search")==0,"reopen drift");}
        System.out.println("PASS synthetic insert/upsert/update/owner-only/delete/rollback/missing-UDF/reopen; NOT product migration or crash durability");
    }
}
