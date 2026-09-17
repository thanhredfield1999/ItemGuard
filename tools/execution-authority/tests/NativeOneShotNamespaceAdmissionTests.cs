using System;
using System.IO;
using ItemGuard.Execution;

class NativeOneShotNamespaceAdmissionTests {
    static int checks;
    static void Check(bool value, string label) { checks++; if (!value) throw new Exception(label); }
    static int Main() {
        string id = Guid.NewGuid().ToString("N");
        string first = "E:/AI.WORK/scratch-execution-authority-one-shot-e-" + id;
        string second = "C:/Users/thanh/AppData/Local/Temp/scratch-execution-authority-one-shot-c-" + id;
        string runtime = "E:/AI.WORK/scratch-execution-authority-one-shot-runtime-" + id;
        try {
            Directory.CreateDirectory(first); Directory.CreateDirectory(second); Directory.CreateDirectory(runtime);
            using (var admission = NativeOneShotNamespaceAdmission.Create(first, second, runtime,
                "authority.jsonl", "attempt-15", 2)) {
                Check(admission.TryCommitAuthorityReady(), "fresh namespace commits authority-ready only after both leaves exist");
                Check(admission.TryCommitAuthorizationConsumed(), "fresh namespace consumes authorization as the second durable record");
            }
            bool blocked = false;
            try { NativeOneShotNamespaceAdmission.Create(first, second, runtime, "authority.jsonl", "attempt-15", 2).Dispose(); }
            catch (IOException) { blocked = true; }
            Check(blocked, "any retained journal leaf permanently blocks a second invocation without cleanup");
            string fileFirst = first + "-runtime-file";
            string fileSecond = second + "-runtime-file";
            string fileRuntime = runtime + "-file";
            Directory.CreateDirectory(fileFirst); Directory.CreateDirectory(fileSecond); Directory.CreateDirectory(fileRuntime);
            File.WriteAllText(Path.Combine(fileRuntime, "attempt-15"), "collision");
            blocked = false;
            try { NativeOneShotNamespaceAdmission.Create(fileFirst, fileSecond, fileRuntime, "authority.jsonl", "attempt-15", 2).Dispose(); }
            catch (IOException) { blocked = true; }
            Check(blocked && !File.Exists(Path.Combine(fileFirst, "authority.jsonl"))
                && !File.Exists(Path.Combine(fileSecond, "authority.jsonl")),
                "runtime file collision blocks before any journal mutation");
            Directory.Delete(fileFirst, true); Directory.Delete(fileSecond, true); Directory.Delete(fileRuntime, true);
            string directoryFirst = first + "-journal-directory";
            string directorySecond = second + "-journal-directory";
            string directoryRuntime = runtime + "-journal-directory";
            Directory.CreateDirectory(directoryFirst); Directory.CreateDirectory(directorySecond); Directory.CreateDirectory(directoryRuntime);
            Directory.CreateDirectory(Path.Combine(directoryFirst, "authority.jsonl"));
            blocked = false;
            try { NativeOneShotNamespaceAdmission.Create(directoryFirst, directorySecond, directoryRuntime, "authority.jsonl", "attempt-15", 2).Dispose(); }
            catch (IOException) { blocked = true; }
            Check(blocked && !File.Exists(Path.Combine(directorySecond, "authority.jsonl")),
                "journal directory collision blocks before a mirror leaf can be created");
            Directory.Delete(directoryFirst, true); Directory.Delete(directorySecond, true); Directory.Delete(directoryRuntime, true);
            string invalidFirst = first + "-invalid-name";
            string invalidSecond = second + "-invalid-name";
            string invalidRuntime = runtime + "-invalid-name";
            Directory.CreateDirectory(invalidFirst); Directory.CreateDirectory(invalidSecond); Directory.CreateDirectory(invalidRuntime);
            foreach (string invalidJournalLeaf in new[] { "..", "nested/authority.jsonl", "nested\\authority.jsonl", Path.GetFullPath("authority-outside.jsonl") }) {
                blocked = false;
                try { NativeOneShotNamespaceAdmission.Create(invalidFirst, invalidSecond, invalidRuntime, invalidJournalLeaf, "attempt-15", 2).Dispose(); }
                catch (ArgumentException) { blocked = true; }
                Check(blocked && !File.Exists(Path.Combine(invalidFirst, "authority.jsonl"))
                    && !File.Exists(Path.Combine(invalidSecond, "authority.jsonl")),
                    "invalid journal leaf is rejected before any journal mutation: " + invalidJournalLeaf);
            }
            Directory.Delete(invalidFirst, true); Directory.Delete(invalidSecond, true); Directory.Delete(invalidRuntime, true);
            Console.WriteLine("NATIVE_ONE_SHOT_NAMESPACE checks=" + checks + " failures=0");
            return 0;
        } catch (Exception exception) { Console.WriteLine("FAIL " + exception); return 1; }
        finally {
            try { if (Directory.Exists(first)) Directory.Delete(first, true); if (Directory.Exists(second)) Directory.Delete(second, true); if (Directory.Exists(runtime)) Directory.Delete(runtime, true); }
            catch (Exception exception) { Console.Error.WriteLine("CLEANUP_FAIL " + exception.Message); }
        }
    }
}
