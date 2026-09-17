import java.sql.*;
import org.sqlite.Function;
import org.sqlite.SQLiteConnection;
/** In-memory diagnostic against packaged driver, not product code. */
public final class CatalogFunctionLifecycleProbe {
 static void register(Connection c,int arity)throws Exception{Function.create(c,"fold",new Function(){protected void xFunc()throws SQLException{result("active");}},arity,0);}
 static boolean active(Connection c)throws Exception{try(var s=c.createStatement();var r=s.executeQuery("SELECT fold('x')")){return r.next()&&"active".equals(r.getString(1));}catch(SQLException e){if(!e.getMessage().contains("no such function"))throw e;return false;}}
 public static void main(String[] args)throws Exception{
  if(args.length!=0)throw new IllegalArgumentException();
  for(int arity:new int[]{1,-1})try(var c=DriverManager.getConnection("jdbc:sqlite::memory:")){
   register(c,arity);if(!active(c))throw new AssertionError("registration");
   int rc=c.unwrap(SQLiteConnection.class).getDatabase().destroy_function("fold");
   System.out.println("arity="+arity+" native_destroy_rc="+rc+" remains_active="+active(c));
  }
 }
}
