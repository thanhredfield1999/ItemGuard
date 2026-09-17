namespace ItemGuard.Execution {
    // Pure pre-create collision policy. It does not interpret receipt validity or
    // authorize recovery: any observed namespace artifact makes this namespace
    // permanently unavailable to the current invocation.
    public enum NamespaceAdmissionDecision { CreateFirstLeaf, BlockConsumedOrPartial }

    public static class NamespaceOneShotPolicy {
        public static NamespaceAdmissionDecision Decide(bool firstJournalLeafExists,
            bool secondJournalLeafExists, bool runtimeLeafExists) {
            return firstJournalLeafExists || secondJournalLeafExists || runtimeLeafExists
                ? NamespaceAdmissionDecision.BlockConsumedOrPartial
                : NamespaceAdmissionDecision.CreateFirstLeaf;
        }
    }
}
