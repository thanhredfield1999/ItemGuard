namespace ItemGuard.Execution {
    // Consumes only externally validated common-prefix events for one process identity.
    public sealed class PaperLifecycle {
        public string Process { get; private set; }
        public string Code { get; private set; }
        public string Output { get; private set; }
        public string Exit { get; private set; }
        public bool InvalidSuffix { get; private set; }
        public PaperLifecycle() { Process = "ABSENT"; Code = Output = Exit = "NOT_APPLICABLE"; }
        public bool Apply(string kind) {
            if (InvalidSuffix) return false;
            if (kind == "SPAWN_INTENT" && Process == "ABSENT") { Process = "SPAWN_INTENT_ONLY"; return true; }
            if (kind == "PROCESS_CREATED_SUSPENDED" && Process == "SPAWN_INTENT_ONLY") {
                Process = "CREATED_SUSPENDED"; Code = Output = Exit = "NOT_ESTABLISHED"; return true;
            }
            if (kind == "PROCESS_ADMISSION_READY" && Process == "CREATED_SUSPENDED") { Process = "ADMISSION_READY"; return true; }
            if (kind == "EXECUTION_RELEASE_INTENT" && Process == "ADMISSION_READY") { Process = "RELEASE_INTENT_UNCERTAIN"; return true; }
            if (kind == "EXECUTION_RELEASE_OBSERVED" && Process == "RELEASE_INTENT_UNCERTAIN") { Process = "RELEASED"; return true; }
            if (kind == "SUSPENDED_TERMINATION_INTENT" && Process == "CREATED_SUSPENDED") { Process = "SUSPENDED_TERMINATION_INTENT_UNCERTAIN"; return true; }
            if (kind == "SUSPENDED_TERMINATION_OBSERVED" && Process == "SUSPENDED_TERMINATION_INTENT_UNCERTAIN") { Process = "TERMINATED_SUSPENDED"; Code = "NON_EXECUTION_ESTABLISHED"; return true; }
            if (kind == "EXECUTION_ACTIVITY_OBSERVED" && Process == "RELEASED" && Code == "NOT_ESTABLISHED" && Output == "NOT_ESTABLISHED") { Code = "ESTABLISHED"; return true; }
            // Caller validates the seal's factual counters. A released process with no
            // activity evidence may seal, but its code execution remains uncertain.
            if (kind == "OUTPUT_SEALED" && Output == "NOT_ESTABLISHED" && (Process == "TERMINATED_SUSPENDED" || Process == "RELEASED")) { Output = "ESTABLISHED"; return true; }
            if (kind == "EXIT_OBSERVED" && Output == "ESTABLISHED" && Exit == "NOT_ESTABLISHED") { Process = "EXITED"; Exit = "ESTABLISHED"; return true; }
            InvalidSuffix = true; return false;
        }
    }
}
