namespace ItemGuard.Execution {
    // Inputs are validated facts, not claims from an untrusted journal.
    public static class HistoryCompleteness {
        public static bool IsComplete(bool mirrorsIdentical, bool commonGenesisValid,
            bool noInvalidOrPartialSuffix, bool exactlyOneFinalTerminal,
            bool terminalSequenceBound, bool noAppendAfterTerminal, bool spawnIntakeSettled) {
            return mirrorsIdentical && commonGenesisValid && noInvalidOrPartialSuffix
                && exactlyOneFinalTerminal && terminalSequenceBound
                && noAppendAfterTerminal && spawnIntakeSettled;
        }
    }
}
