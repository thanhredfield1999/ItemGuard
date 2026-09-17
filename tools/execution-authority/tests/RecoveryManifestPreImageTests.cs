using System;
using System.Collections;
using System.Collections.Specialized;
using System.Security.Cryptography;
using System.Text;
using ItemGuard.Recovery;

class RecoveryManifestPreImageTests {
    static int checks;
    static void Check(bool v, string m) { checks++; if (!v) throw new Exception(m); }
    static string Hash(byte[] b) { using (var s = SHA256.Create()) { var h=s.ComputeHash(b); var x=new StringBuilder(); foreach(byte n in h)x.Append(n.ToString("x2")); return x.ToString(); } }
    static int Main() {
        try {
            var manifest = new OrderedDictionary();
            manifest.Add("bundleManifestSha256", new string('a',64));
            manifest.Add("enforcementReceiptRoots", new string[] { "C:/root", "E:/root" });
            manifest.Add("enforcementReceipts", new string[] { "r1", "r2", "r3", "r4" });
            manifest.Add("version", (ulong)1);
            string actual = RecoveryManifestPreImage.ComputeContentSha256(manifest);
            var expectedMap = new OrderedDictionary();
            expectedMap.Add("bundleManifestSha256", new string('a',64));
            expectedMap.Add("enforcementReceiptRoots", new string[] { "C:/root", "E:/root" });
            expectedMap.Add("version", (ulong)1);
            Check(actual == Hash(CanonicalReEncoder.Encode(expectedMap)), "manifest content hash excludes only enforcementReceipts using canonical bytes");
            Check(manifest.Contains("enforcementReceipts"), "pre-image computation does not mutate shipped manifest");
            Console.WriteLine("RECOVERY_MANIFEST_PREIMAGE checks="+checks+" failures=0"); return 0;
        } catch(Exception ex) { Console.WriteLine("FAIL "+ex); return 1; }
    }
}
