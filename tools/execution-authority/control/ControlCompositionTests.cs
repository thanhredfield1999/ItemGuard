using System;
using System.Collections;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using ItemGuard.Execution.Control;

// In-memory composition evidence only. Schema/role/identity validation, byte hashes
// from an authenticated source, deadlines and real durable I/O are NOT exercised.
class ControlCompositionTests {
    static void Check(bool value, string message) { if (!value) throw new Exception(message); }
    static byte[] Frame(byte[] body) {
        var frame = new byte[body.Length + 4];
        frame[0] = (byte)body.Length; frame[1] = (byte)(body.Length >> 8);
        frame[2] = (byte)(body.Length >> 16); frame[3] = (byte)(body.Length >> 24);
        Buffer.BlockCopy(body, 0, frame, 4, body.Length);
        return frame;
    }
    static string Hash(byte[] body) {
        using (var sha = SHA256.Create()) {
            var s = new StringBuilder();
            foreach (byte b in sha.ComputeHash(body)) s.Append(b.ToString("x2"));
            return s.ToString();
        }
    }
    static IDictionary Parse(MethodInfo parse, byte[] body) {
        try { return (IDictionary)parse.Invoke(null, new object[] { body, 65536 }); }
        catch (TargetInvocationException e) { throw e.InnerException; }
    }
    static void LargeFragmentedRequest(MethodInfo parse, int size, int piece) {
        const string prefix = "{\"payload\":\"";
        const string suffix = "\",\"requestId\":9}";
        byte[] body = Encoding.UTF8.GetBytes(prefix + new string('x', size - prefix.Length - suffix.Length) + suffix);
        var frame = Frame(body);
        var reader = new ControlFrameReader();
        var session = new ControlSession(ControlRole.SPEAKER);
        Check(session.BeginFrame(), "begin receive");
        for (int offset = 0; offset < frame.Length; offset += piece) {
            int count = Math.Min(piece, frame.Length - offset);
            Check(reader.Append(frame, offset, count), "append");
            Check(!session.CanAcknowledge, "frame bytes must never authorize ACK");
        }
        Check(reader.Complete, "complete frame");
        byte[] recovered = reader.GetBody();
        IDictionary doc = Parse(parse, recovered);
        Check(((string)doc["payload"]).Length == size - prefix.Length - suffix.Length, "full payload");
        string hash = Hash(recovered);
        Check(session.CompleteFrame((ulong)doc["requestId"], hash, ControlRequestKind.OPERATION), "typed frame");
        Check(!session.TryDispatch(false) && !session.Failed, "buffer pending release commit");
        Check(session.ReleaseCommitted() && session.TryDispatch(false), "release then dispatch");
        Check(!session.CanAcknowledge, "no ACK before durable result");
        Check(session.CommitRequest(true, true) && session.AcknowledgeWritten(9, hash), "simulated dual commit and exact ACK");
    }
    static void MalformedCanonicalBody(MethodInfo parse) {
        byte[] body = Encoding.UTF8.GetBytes("{\"requestId\":9,\"requestId\":9}");
        var frame = Frame(body);
        var reader = new ControlFrameReader();
        var session = new ControlSession(ControlRole.SPEAKER);
        session.BeginFrame();
        Check(reader.Append(frame, 0, frame.Length), "framing alone accepts bytes");
        bool rejected = false;
        try { Parse(parse, reader.GetBody()); }
        catch (FormatException) { rejected = true; session.Fail(); }
        Check(rejected && session.Failed && !session.CanAcknowledge, "decoder failure must close policy before typed dispatch");
    }
    static int Main(string[] args) {
        try {
            var type = Assembly.LoadFrom(args[0]).GetType("ItemGuard.Recovery.CanonicalDecoder", true);
            var parse = type.GetMethod("ParseDocument", new [] { typeof(byte[]), typeof(int) });
            int cases = 0;
            foreach (int size in new [] { 4096, 65536 }) foreach (int piece in new [] { 1, 7, 31, 4096 }) {
                LargeFragmentedRequest(parse, size, piece); cases++;
            }
            MalformedCanonicalBody(parse); cases++;
            Console.WriteLine("COMPOSITION cases=" + cases + " failures=0 (in-memory only)");
            return 0;
        } catch (Exception e) { Console.WriteLine("FAIL composition: " + e); return 1; }
    }
}
