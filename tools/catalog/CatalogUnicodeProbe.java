import java.sql.*;
import java.text.Normalizer;
import java.util.*;

/** In-memory compatibility experiment only; no persistent database input. */
public final class CatalogUnicodeProbe {
    static String fold(String s) { return Normalizer.normalize(s,Normalizer.Form.NFC).toLowerCase(Locale.ROOT); }
    static String hex(String s) { return s.codePoints().mapToObj(c->Integer.toHexString(c)).reduce((a,b)->a+"-"+b).orElse("empty"); }
    public static void main(String[] args) throws Exception {
        if(args.length!=0)throw new IllegalArgumentException("no inputs");
        String[] values={"abc\0defghi","abc\0xyz","KIẾM RỒNG","kiếm rồng","😀雪abc","İSTANBUL","ΟΣΟΣ","a\"b%c_d","prefix\uD800suffix"};
        String[] queries={"defghi","abc\0xyz","\0de","kiếm","😀雪a","i\u0307st","οσο","a\"b","suffix"};
        try(var c=DriverManager.getConnection("jdbc:sqlite::memory:");var s=c.createStatement()) {
            s.execute("CREATE TABLE items(id INTEGER PRIMARY KEY,name TEXT)");
            s.execute("CREATE VIRTUAL TABLE search USING fts5(name,tokenize='trigram case_sensitive 1')");
            try(var p=c.prepareStatement("INSERT INTO items VALUES(?,?)");var f=c.prepareStatement("INSERT INTO search(rowid,name) VALUES(?,?)")) {
                for(int i=0;i<values.length;i++){p.setInt(1,i);p.setString(2,fold(values[i]));p.executeUpdate();f.setInt(1,i);f.setString(2,fold(values[i]));f.executeUpdate();}
            }
            int mismatch=0,errors=0;
            for(String input:queries){String q=fold(input);List<Integer> expected=new ArrayList<>(),actual=new ArrayList<>();
                try(var p=c.prepareStatement("SELECT id FROM items WHERE instr(name,?)>0 ORDER BY id")){p.setString(1,q);try(var r=p.executeQuery()){while(r.next())expected.add(r.getInt(1));}}
                try(var p=c.prepareStatement("SELECT i.id FROM search JOIN items i ON i.id=search.rowid WHERE search MATCH ? AND instr(i.name,?)>0 ORDER BY i.id")){
                    p.setString(1,"\""+q.replace("\"","\"\"")+"\"");p.setString(2,q);try(var r=p.executeQuery()){while(r.next())actual.add(r.getInt(1));}
                    if(!expected.equals(actual))mismatch++;
                    System.out.println("QUERY hex="+hex(q)+" oracle="+expected+" indexed="+actual+" exact="+expected.equals(actual));
                }catch(SQLException e){errors++;System.out.println("QUERY hex="+hex(q)+" oracle="+expected+" SQL_ERROR="+e.getErrorCode());}
            }
            System.out.println("MEASURED cases="+queries.length+" mismatches="+mismatch+" errors="+errors+"; not an acceptance gate");
            int verified=0;
            for(String input:queries) {
                String q=fold(input);
                boolean indexed=q.indexOf('\0')<0 && q.codePointCount(0,q.length())>=3;
                List<Integer> expected=new ArrayList<>(),actual=new ArrayList<>();
                try(var p=c.prepareStatement("SELECT id FROM items WHERE instr(name,?)>0 ORDER BY id")){p.setString(1,q);try(var r=p.executeQuery()){while(r.next())expected.add(r.getInt(1));}}
                String sql=indexed?"SELECT i.id FROM search JOIN items i ON i.id=search.rowid WHERE search MATCH ? AND instr(i.name,?)>0 ORDER BY i.id":"SELECT id FROM items WHERE instr(name,?)>0 ORDER BY id";
                try(var p=c.prepareStatement(sql)){p.setString(1,indexed?"\""+q.replace("\"","\"\"")+"\"":q);if(indexed)p.setString(2,q);try(var r=p.executeQuery()){while(r.next())actual.add(r.getInt(1));}}
                if(!expected.equals(actual))throw new AssertionError("guarded mismatch "+hex(q));
                verified++;
            }
            System.out.println("GUARDED exact="+verified+"; NUL/short fallback uses instr, no scale claim");
        }
    }
}
