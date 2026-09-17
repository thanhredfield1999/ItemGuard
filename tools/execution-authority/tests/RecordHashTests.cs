using System;
using System.Collections;
using System.Collections.Specialized;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;

// Slice 6 (behavioral): journal record hash pre-image rule, on BOTH assemblies.
//
// Scope note: only the parts of the record contract that the design fixes exactly are
// tested here -- pre-image = canonical object with the top-level "recordSha256" member
// deleted (no placeholder, no LF), lowercase hex-64 output, journal line = canonical
// bytes + exactly one LF. Event/payload schema, unknown-field rejection and seq/chain
// semantics are NOT implemented: their schema is not closed in the design.
class RecordHashTests {
    static MethodInfo guardianHash;      // CanonicalJson.ComputeRecordSha256(IDictionary) -> string
    static MethodInfo guardianLine;      // CanonicalJson.EncodeJournalLine(IDictionary) -> byte[]
    static MethodInfo recoveryHash;      // CanonicalDecoder.ComputeRecordSha256(IDictionary) -> string
    static MethodInfo recoveryVerify;    // CanonicalDecoder.VerifyJournalLine(byte[]) -> IDictionary
    static int failures;
    static readonly UTF8Encoding U8 = new UTF8Encoding(false, true);

    static int Main(string[] args) {
        try {
            Type g = Assembly.LoadFrom(args[0]).GetType("ItemGuard.Execution.CanonicalJson", true);
            Type r = Assembly.LoadFrom(args[1]).GetType("ItemGuard.Recovery.CanonicalDecoder", true);
            guardianHash = Req(g, "ComputeRecordSha256", typeof(IDictionary));
            guardianLine = Req(g, "EncodeJournalLine", typeof(IDictionary));
            recoveryHash = Req(r, "ComputeRecordSha256", typeof(IDictionary));
            recoveryVerify = Req(r, "VerifyJournalLine", typeof(byte[]));
        } catch (Exception e) {
            Console.Error.WriteLine("FAIL bootstrap " + e.GetType().Name + ": " + e.Message);
            return 1;
        }

        // Pre-image is the canonical object minus the recordSha256 member.
        var body = Body();
        string preImage = "{\"namespace\":\"attempt-15\",\"seq\":0}";
        string expected = Sha256Hex(U8.GetBytes(preImage));

        Check("guardian hash equals documented pre-image", Invoke(guardianHash, body), expected);
        Check("recovery hash equals documented pre-image", Invoke(recoveryHash, body), expected);

        // A body that already carries recordSha256 must hash to the same value: the member
        // is deleted, never replaced by a placeholder.
        var withHash = Body();
        withHash["recordSha256"] = expected;
        Check("existing recordSha256 deleted not placeheld", Invoke(guardianHash, withHash), expected);
        Check("recovery deletes too", Invoke(recoveryHash, withHash), expected);

        // Only the exact top-level member is deleted.
        var nested = Body();
        nested["payload"] = Map("recordSha256", "deadbeef");
        string nestedPre = "{\"namespace\":\"attempt-15\",\"payload\":{\"recordSha256\":\"deadbeef\"},\"seq\":0}";
        Check("nested member of same name is kept", Invoke(guardianHash, nested), Sha256Hex(U8.GetBytes(nestedPre)));
        Check("recovery keeps nested too", Invoke(recoveryHash, nested), Sha256Hex(U8.GetBytes(nestedPre)));

        // Output shape: lowercase hex, 64 chars.
        string h = Invoke(guardianHash, body);
        if (h == null || h.Length != 64) { failures++; Console.Error.WriteLine("FAIL hash length: " + h); }
        else foreach (char c in h) {
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                failures++; Console.Error.WriteLine("FAIL hash not lowercase hex: " + h); break;
            }
        }

        // Journal line = canonical bytes + exactly one LF, with recordSha256 present.
        var record = Body();
        record["recordSha256"] = expected;
        byte[] line;
        try { line = (byte[])guardianLine.Invoke(null, new object[] { record }); }
        catch (TargetInvocationException e) {
            failures++; Console.Error.WriteLine("FAIL journal line: " + e.InnerException.Message);
            return Finish();
        }
        string lineText = U8.GetString(line);
        string wantLine = "{\"namespace\":\"attempt-15\",\"recordSha256\":\"" + expected + "\",\"seq\":0}\n";
        if (lineText != wantLine) {
            failures++;
            Console.Error.WriteLine("FAIL journal line bytes:\n  want " + wantLine + "  got  " + lineText);
        }

        // Recovery accepts the guardian's line and rejects tampering.
        AcceptLine("recovery verifies guardian line", line);
        RejectLine("missing LF", U8.GetBytes(wantLine.TrimEnd('\n')));
        RejectLine("double LF", U8.GetBytes(wantLine + "\n"));
        RejectLine("CRLF", U8.GetBytes(wantLine.TrimEnd('\n') + "\r\n"));
        RejectLine("wrong hash", U8.GetBytes(wantLine.Replace(expected, new string('0', 64))));
        RejectLine("uppercase hash", U8.GetBytes(wantLine.Replace(expected, expected.ToUpperInvariant())));
        RejectLine("short hash", U8.GetBytes("{\"namespace\":\"attempt-15\",\"recordSha256\":\"abcd\",\"seq\":0}\n"));
        RejectLine("missing recordSha256", U8.GetBytes("{\"namespace\":\"attempt-15\",\"seq\":0}\n"));
        RejectLine("mutated body same hash field",
            U8.GetBytes(wantLine.Replace("\"seq\":0", "\"seq\":1")));
        RejectLine("non-canonical line", U8.GetBytes("{\"seq\":0,\"namespace\":\"attempt-15\",\"recordSha256\":\"" + expected + "\"}\n"));
        RejectLine("empty", new byte[0]);

        return Finish();
    }

    static int Finish() {
        if (failures > 0) { Console.Error.WriteLine("FAILURES=" + failures); return 1; }
        Console.WriteLine("PASS RecordHashTests: 7 pre-image vectors + shape + 11 journal-line cases");
        return 0;
    }

    static MethodInfo Req(Type t, string name, Type arg) {
        MethodInfo m = t.GetMethod(name, new Type[] { arg });
        if (m == null) throw new MissingMethodException(t.FullName + "." + name);
        return m;
    }

    static string Invoke(MethodInfo m, IDictionary arg) {
        try { return (string)m.Invoke(null, new object[] { arg }); }
        catch (TargetInvocationException e) { return "THREW:" + e.InnerException.GetType().Name + ":" + e.InnerException.Message; }
    }

    static void Check(string name, string got, string want) {
        if (got != want) { failures++; Console.Error.WriteLine("FAIL " + name + ":\n  want " + want + "\n  got  " + got); }
    }

    static void AcceptLine(string name, byte[] line) {
        try { recoveryVerify.Invoke(null, new object[] { line }); }
        catch (TargetInvocationException e) {
            failures++; Console.Error.WriteLine("FAIL accept " + name + ": " + e.InnerException.GetType().Name + " " + e.InnerException.Message);
        }
    }

    static void RejectLine(string name, byte[] line) {
        try {
            recoveryVerify.Invoke(null, new object[] { line });
            failures++; Console.Error.WriteLine("FAIL reject " + name + ": accepted");
        } catch (TargetInvocationException e) {
            if (!(e.InnerException is FormatException)) {
                failures++; Console.Error.WriteLine("FAIL reject " + name + ": wrong exception " + e.InnerException.GetType().Name);
            }
        }
    }

    static IDictionary Body() { return Map("seq", (ulong)0, "namespace", "attempt-15"); }

    static OrderedDictionary Map(params object[] kv) {
        var d = new OrderedDictionary();
        for (int i = 0; i < kv.Length; i += 2) d.Add((string)kv[i], kv[i + 1]);
        return d;
    }

    static string Sha256Hex(byte[] data) {
        using (var sha = SHA256.Create()) {
            byte[] h = sha.ComputeHash(data);
            var sb = new StringBuilder(64);
            foreach (byte x in h) sb.Append(x.ToString("x2"));
            return sb.ToString();
        }
    }
}
