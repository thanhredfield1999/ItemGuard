# Dual-commit core review disposition

Initial Opus5 review `dual-commit-review.json`: PASS_FOR_OFFLINE_CANDIDATE, input `e7df5eef5d51441d9a85cf17d853c0da449992a0ead1cf7c31679fa432afd719`. This is no runtime/host/durability gate.

1+3 CONFIRMED failure diagnostics insufficient for future host. Kept pure boolean contract: false ALWAYS prohibits ACK and never proves zero writes; host closes job on any failure and recovery derives state from both exact mirrors, not this volatile flag. Source comment now explicit. No invented enum promoted to disk truth. Typed host diagnostics/errors before native sink integration remain OPEN.
2 CONFIRMED needless allocation/hash window after both flushes. GREEN refactor precomputes SHA before sink calls; only published count/hash assignment follows final flush. Not a newly reproduced FIPS/OOM defect; no claim OS failure injection.
4 CONFIRMED masked duplicate coverage: duplicate now maxRecords=2, limit stays1; passes existing sequence guard. Green-first coverage, no behavior patch.
5 CONFIRMED coverage gap: commit0 succeeds, commit1 last flush throws, count/hash preserved, Failed=true. New green-first coverage.
6 CONFIRMED contract note duplicated onto code: state serial-owner or after operation join only, not cross-thread monitoring.
7 INCONCLUSIVE flaky claim: 150ms negative wait may let a broken implementation slip through under starvation, but cannot fail correctly serial code solely from timeout; final a/b+count ensure both complete, failure timeouts3s are test bounds. Observed behavioral concurrency RED then GREEN preserved, not runtime stress proof.
8 Added independent cap equality check. Existing CommonJournalChain byte-pair integration runs separately built recovery assembly; line bounds/frozen codec suites remain part of full offline command. No shared production codec assembly introduced.

Final 60 dual checks + full offline rerun required after hash refactor. No native handle, filesystem journal, ACL/token/job, runtime namespace, receipt or Paper added. CanonicalJson.EncodeJournalLine still assumes stable caller-owned record; full event/trusted-genesis schema upstream of core still OPEN. Core is unused by runtime.
