using System;
using System.IO;
using ItemGuard.Execution;

class NativeJournalLeafTests {
    static int checks;
    static void Check(bool condition, string label) {
        checks++;
        if (!condition) throw new Exception(label);
    }
    static void Expect<T>(Action action, string label) where T : Exception {
        bool rejected = false;
        try { action(); } catch (T) { rejected = true; }
        Check(rejected, label);
    }
    static bool SameBytes(byte[] first, byte[] second) {
        if (first == null || second == null || first.Length != second.Length) return false;
        for (int i = 0; i < first.Length; i++) if (first[i] != second[i]) return false;
        return true;
    }
    static byte[] ReadWhileLeafHeld(string path) {
        using (var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite)) {
            var bytes = new byte[stream.Length];
            int offset = 0;
            while (offset < bytes.Length) {
                int count = stream.Read(bytes, offset, bytes.Length - offset);
                if (count == 0) break;
                offset += count;
            }
            if (offset != bytes.Length) throw new IOException("short read");
            return bytes;
        }
    }
    static int Main() {
        string root = "E:/AI.WORK/scratch-execution-authority-native-journal-" + Guid.NewGuid().ToString("N");
        string reparseRoot = root + "-junction";
        string leaf = Path.Combine(root, "authority.jsonl");
        string outside = Path.Combine(Path.GetDirectoryName(root), "authority-escape.jsonl");
        byte[] record = System.Text.Encoding.UTF8.GetBytes("{\"seq\":0}\n");
        try {
            Directory.CreateDirectory(root);
            using (var journal = NativeJournalLeaf.Create(root, "authority.jsonl")) {
                Check(File.Exists(leaf), "relative native create must create the exact leaf");
                Check(journal.AppendAndFlush(record) == record.Length, "append and flush must report all bytes");
                Check(SameBytes(ReadWhileLeafHeld(leaf), record), "durable leaf bytes must equal the requested record");
                Expect<IOException>(() => NativeJournalLeaf.Create(root, "authority.jsonl"), "existing leaf must be rejected without overwrite");
                Check(SameBytes(ReadWhileLeafHeld(leaf), record), "collision must not alter original bytes");
                Expect<ArgumentException>(() => NativeJournalLeaf.Create(root, "../authority-escape.jsonl"), "leaf path traversal must be rejected before native create");
                Check(!File.Exists(outside), "rejected traversal must not create outside the parent");
            }
            var junction = new System.Diagnostics.ProcessStartInfo(Environment.GetEnvironmentVariable("ComSpec"),
                "/d /s /c mklink /J \"" + reparseRoot + "\" \"" + root + "\"");
            junction.UseShellExecute = false; junction.CreateNoWindow = true;
            using (var process = System.Diagnostics.Process.Start(junction)) {
                process.WaitForExit();
                Check(process.ExitCode == 0 && Directory.Exists(reparseRoot), "test setup creates a disposable junction");
            }
            Expect<IOException>(() => {
                using (var ignored = NativeJournalLeaf.Create(reparseRoot, "reparse.jsonl")) { }
            }, "reparse parent must be rejected before leaf create");
            Check(!File.Exists(Path.Combine(root, "reparse.jsonl")), "reparse rejection must not create inside target");
            Console.WriteLine("NATIVE_JOURNAL_LEAF checks=" + checks + " failures=0");
            return 0;
        } catch (Exception ex) {
            Console.WriteLine("FAIL " + ex);
            return 1;
        } finally {
            try {
                if (Directory.Exists(reparseRoot)) Directory.Delete(reparseRoot, false);
                if (Directory.Exists(root)) Directory.Delete(root, true);
                if (File.Exists(outside)) File.Delete(outside);
            } catch (Exception ex) {
                Console.Error.WriteLine("CLEANUP_FAIL " + ex.Message);
            }
        }
    }
}
