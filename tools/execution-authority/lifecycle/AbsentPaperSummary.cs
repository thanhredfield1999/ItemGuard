namespace ItemGuard.Execution {
    // Pure summary only: caller must establish metadata from journal validation.
    public static class AbsentPaperSummary {
        public static string Derive(bool allEnforcementGatesValid, bool historyComplete) {
            if (!allEnforcementGatesValid) return "PAPER_INTENT_ABSENT_ENFORCEMENT_UNPROVEN";
            if (!historyComplete) return "PAPER_INTENT_ABSENT_HISTORY_INCOMPLETE";
            return "FAIL_BEFORE_PAPER";
        }
    }
}
