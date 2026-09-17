using System;
using System.Collections.Generic;
using ItemGuard.Execution;

class RoleCommandSchemaTests {
    static int checks;
    static void Check(bool value, string message) { checks++; if (!value) throw new Exception(message); }
    static int Main() {
        try {
            var fields = new Dictionary<string,string>(StringComparer.Ordinal) { { "script", "C:/tool root/run.py" }, { "runToken", "6d4b2bb8-4a0d-4f7d-95e4-4b9bb886815a" } };
            string[] argv = RoleCommandSchema.Expand("PIPELINE", fields);
            Check(argv.Length == 3 && argv[0] == "C:/tool root/python.exe" && argv[1] == "C:/tool root/run.py" && argv[2] == "--run-token=6d4b2bb8-4a0d-4f7d-95e4-4b9bb886815a", "allowlisted pipeline expansion is exact and does not use PATH lookup");
            fields.Add("argv", "--unsafe");
            bool rejected = false; try { RoleCommandSchema.Expand("PIPELINE", fields); } catch (FormatException) { rejected = true; }
            Check(rejected, "unknown field cannot inject argv");
            var nonV4 = new Dictionary<string,string>(StringComparer.Ordinal) {
                { "script", "C:/tool root/run.py" },
                { "runToken", "6d4b2bb8-4a0d-1f7d-95e4-4b9bb886815a" }
            };
            rejected = false; try { RoleCommandSchema.Expand("PIPELINE", nonV4); } catch (FormatException) { rejected = true; }
            Check(rejected, "non-v4 run token cannot enter the allowlisted role argv");
            var wrongVariant = new Dictionary<string,string>(StringComparer.Ordinal) {
                { "script", "C:/tool root/run.py" },
                { "runToken", "6d4b2bb8-4a0d-4f7d-75e4-4b9bb886815a" }
            };
            rejected = false; try { RoleCommandSchema.Expand("PIPELINE", wrongVariant); } catch (FormatException) { rejected = true; }
            Check(rejected, "wrong-variant run token cannot enter the allowlisted role argv");
            Console.WriteLine("ROLE_COMMAND_SCHEMA checks=" + checks + " failures=0"); return 0;
        } catch (Exception ex) { Console.WriteLine("FAIL " + ex); return 1; }
    }
}
