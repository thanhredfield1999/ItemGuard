using System;
using ItemGuard.Execution;
class PaperPrefixTests {
    static string Snapshot(PaperLifecycle s) { return s.Process+"/"+s.Code+"/"+s.Output+"/"+s.Exit; }
    static int Main() {
        string[][] branches = {
            new [] { "SPAWN_INTENT", "PROCESS_CREATED_SUSPENDED", "PROCESS_ADMISSION_READY", "EXECUTION_RELEASE_INTENT", "EXECUTION_RELEASE_OBSERVED", "EXECUTION_ACTIVITY_OBSERVED", "OUTPUT_SEALED", "EXIT_OBSERVED" },
            new [] { "SPAWN_INTENT", "PROCESS_CREATED_SUSPENDED", "PROCESS_ADMISSION_READY", "EXECUTION_RELEASE_INTENT", "EXECUTION_RELEASE_OBSERVED", "OUTPUT_SEALED", "EXIT_OBSERVED" },
            new [] { "SPAWN_INTENT", "PROCESS_CREATED_SUSPENDED", "SUSPENDED_TERMINATION_INTENT", "SUSPENDED_TERMINATION_OBSERVED", "OUTPUT_SEALED", "EXIT_OBSERVED" }
        };
        string[] events = { "SPAWN_INTENT", "PROCESS_CREATED_SUSPENDED", "PROCESS_ADMISSION_READY", "EXECUTION_RELEASE_INTENT", "EXECUTION_RELEASE_OBSERVED", "EXECUTION_ACTIVITY_OBSERVED", "SUSPENDED_TERMINATION_INTENT", "SUSPENDED_TERMINATION_OBSERVED", "OUTPUT_SEALED", "EXIT_OBSERVED", "UNKNOWN", null };
        int checkedCases=0;
        foreach (var branch in branches) for (int prefix=0; prefix<=branch.Length; prefix++) foreach (var ev in events) {
            var s=new PaperLifecycle(); for(int i=0;i<prefix;i++) if(!s.Apply(branch[i])) throw new Exception("valid prefix rejected");
            string before=Snapshot(s);
            bool allowed=prefix<branch.Length && ev==branch[prefix];
            // At created-suspended either admission or pre-release abort is allowed.
            if(prefix==2 && (ev=="PROCESS_ADMISSION_READY" || ev=="SUSPENDED_TERMINATION_INTENT")) allowed=true;
            // Validated zero-activity seal is allowed; only activity establishes code.
            if(prefix==5 && branch[3]=="EXECUTION_RELEASE_INTENT"
                && (ev=="EXECUTION_ACTIVITY_OBSERVED" || ev=="OUTPUT_SEALED")) allowed=true;
            if(s.Apply(ev)!=allowed) throw new Exception("wrong acceptance at "+prefix+" event "+ev);
            if(!allowed) {
                if(!s.InvalidSuffix || Snapshot(s)!=before) throw new Exception("invalid event changed prefix");
                foreach(var later in events) if(s.Apply(later) || Snapshot(s)!=before) throw new Exception("suffix recovered after failure");
            }
            checkedCases++;
        }
        Console.WriteLine("PASS prefix/event matrix cases="+checkedCases); return 0;
    }
}
