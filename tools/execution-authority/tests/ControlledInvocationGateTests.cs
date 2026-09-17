using System;
using System.Collections;
using System.IO;
using ItemGuard.Execution;

class ControlledInvocationGateTests {
    static int checks;
    const string Bundle = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    const string Authority = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    const string Recovery = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
    const string Review = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";
    static void Check(bool value, string label) { checks++; if (!value) throw new Exception(label); }
    static Hashtable Receipt() {
        var value = new Hashtable();
        value["receiptType"] = "CONTROLLED_PAPER_ONE_SHOT";
        value["authorizedControlledNamespace"] = "attempt-15";
        value["bundleManifestSha256"] = Bundle;
        value["executionAuthorityManifestSha256"] = Authority;
        value["recoveryManifestContentSha256"] = Recovery;
        value["reviewReceiptSha256"] = Review;
        value["userAuthorizationUnixMs"] = (ulong)1;
        value["production"] = false; value["release"] = false; value["retry"] = false;
        return value;
    }
    static bool Reject(Action action) {
        try { action(); return false; } catch (FormatException) { return true; }
        catch (IOException) { return true; } catch (InvalidOperationException) { return true; }
    }
    static int Main() {
        string id = Guid.NewGuid().ToString("N");
        string first = "E:/AI.WORK/scratch-execution-authority-gate-e-" + id;
        string second = "C:/Users/thanh/AppData/Local/Temp/scratch-execution-authority-gate-c-" + id;
        string receiptRoot = "E:/AI.WORK/scratch-execution-authority-gate-receipt-" + id;
        string runtime = "E:/AI.WORK/scratch-execution-authority-gate-runtime-" + id;
        try {
            Directory.CreateDirectory(first); Directory.CreateDirectory(second);
            Directory.CreateDirectory(receiptRoot); Directory.CreateDirectory(runtime);
            NativeFileIdentity identity;
            using (var leaf = NativeJournalLeaf.Create(receiptRoot, "authorization.json")) {
                leaf.AppendAndFlush(CanonicalJson.EncodeDocument(Receipt())); identity = leaf.Identity;
            }
            File.WriteAllText(Path.Combine(receiptRoot, "invalid.json"), "{}");
            Check(Reject(() => ControlledInvocationGate.Consume(receiptRoot, "invalid.json", identity, 4096,
                first, second, runtime, "authority.jsonl", "attempt-15", 2, "attempt-15", Bundle, Authority, Recovery, Review)),
                "receipt identity or schema failure is rejected before journal mutation");
            Check(!File.Exists(Path.Combine(first, "authority.jsonl")) && !File.Exists(Path.Combine(second, "authority.jsonl")),
                "rejected receipt creates neither journal leaf");
            using (var gate = ControlledInvocationGate.Consume(receiptRoot, "authorization.json", identity, 4096,
                first, second, runtime, "authority.jsonl", "attempt-15", 2, "attempt-15", Bundle, Authority, Recovery, Review)) {
                Check(gate.Receipt.Namespace == "attempt-15" && gate.CommittedCount == 2,
                    "validated receipt is consumed only after both dual genesis records commit");
                Check(gate.RunToken.Length == 36 && gate.RunToken[14] == '4',
                    "gate exposes the journal-bound UUID-v4 run token only after consumption");
            }
            string journal = File.ReadAllText(Path.Combine(first, "authority.jsonl"));
            Check(journal == File.ReadAllText(Path.Combine(second, "authority.jsonl")),
                "authority and consumption records are byte-identical across journals");
            Check(journal.Contains("\"authorizationReceiptFileIdHex\":\"" + identity.FileIdHex + "\"")
                && journal.Contains("\"authorizationReceiptVolumeSerial\":" + identity.VolumeSerial)
                && journal.Contains("\"bundleManifestSha256\":\"" + Bundle + "\"")
                && journal.Contains("\"executionAuthorityManifestSha256\":\"" + Authority + "\"")
                && journal.Contains("\"recoveryManifestContentSha256\":\"" + Recovery + "\"")
                && journal.Contains("\"reviewReceiptSha256\":\"" + Review + "\""),
                "authority-ready permanently binds the exact receipt identity and four validated hashes");
            Check(Reject(() => ControlledInvocationGate.Consume(receiptRoot, "authorization.json", identity, 4096,
                first, second, runtime, "authority.jsonl", "attempt-15", 2, "attempt-15", Bundle, Authority, Recovery, Review)),
                "consumed namespace cannot invoke the gate a second time");
            string limitedFirst = first + "-limited";
            string limitedSecond = second + "-limited";
            string limitedRuntime = runtime + "-limited";
            Directory.CreateDirectory(limitedFirst); Directory.CreateDirectory(limitedSecond); Directory.CreateDirectory(limitedRuntime);
            Check(Reject(() => ControlledInvocationGate.Consume(receiptRoot, "authorization.json", identity, 4096,
                limitedFirst, limitedSecond, limitedRuntime, "authority.jsonl", "attempt-15", 1, "attempt-15", Bundle, Authority, Recovery, Review)),
                "failure after authority-ready cannot return a partially consumed invocation");
            Check(File.Exists(Path.Combine(limitedFirst, "authority.jsonl"))
                && File.Exists(Path.Combine(limitedSecond, "authority.jsonl")),
                "post-genesis failure retains both namespace leaves as consumed evidence");
            Directory.Delete(limitedFirst, true); Directory.Delete(limitedSecond, true); Directory.Delete(limitedRuntime, true);
            Console.WriteLine("CONTROLLED_INVOCATION_GATE checks=" + checks + " failures=0");
            return 0;
        } catch (Exception exception) { Console.WriteLine("FAIL " + exception); return 1; }
        finally {
            try { if (Directory.Exists(first)) Directory.Delete(first, true); } catch (Exception exception) { Console.Error.WriteLine("E_CLEANUP_FAIL " + exception.Message); }
            try { if (Directory.Exists(second)) Directory.Delete(second, true); } catch (Exception exception) { Console.Error.WriteLine("C_CLEANUP_FAIL " + exception.Message); }
            try { if (Directory.Exists(receiptRoot)) Directory.Delete(receiptRoot, true); } catch (Exception exception) { Console.Error.WriteLine("R_CLEANUP_FAIL " + exception.Message); }
            try { if (Directory.Exists(runtime)) Directory.Delete(runtime, true); } catch (Exception exception) { Console.Error.WriteLine("N_CLEANUP_FAIL " + exception.Message); }
        }
    }
}
