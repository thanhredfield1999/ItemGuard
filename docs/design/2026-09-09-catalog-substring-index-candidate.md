# Substring index candidate — design, NOT shipped

Scope: preserve existing NFC→Locale.ROOT literal substring semantics across name/code/material, owner filter and material category, code ascending keyset pagination, query/admission budgets. No production migration or server operations. Existing trigram spike proves only name-only synthetic feasibility, not full contract.

Proposed derived FTS5 trigram case_sensitive1 index with three separate normalized columns. Never concatenate fields (cross-field false matches). Quote MATCH input literally, then apply exact instr residual and owner/category/code cursor BEFORE LIMIT37. Query length measured after folding in Unicode codepoints; shorter than3 needs separately designed bounded path, not FTS MATCH false-empty. Common terms plus sparse owner/category may still scan/sort too much. Keep errors as errors, never partial rows as complete results.

Critical unresolved design points before product implementation:
- Stable rowid versus code identity mapping, replacement/upsert/delete behavior.
- Every tracked_items mutation must atomically update derived columns/index in SAME SQLite transaction. Trigger/UDF lifecycle and availability must be guaranteed before any writer path. Manual dual writes in a probe do not prove product coverage.
- Backfill must be bounded/resumable or have reviewed startup migration cap; stamp only when index valid. Interrupted creation, disk full, malformed preexisting table/index, future versions and restore rollback must preserve original DB.
- Derived-index stale/corrupt/missing state must deny indexed search or explicit bounded fallback, never assert complete absence. FTS integrity-check alone does not prove external content synchronization.
- Unicode normalization/folding version changes and NUL/unpaired-surrogate/string domain must not introduce false negatives. Short query completeness and long-value indexing work remain unresolved.
- Cost evidence needs same-corpus baseline/table+index disk delta, insert/update/delete cost, mixed traffic, restrictive filters and short rare/no-match samples. Existing total DB bytes are NOT index overhead.

Evidence: c1-trigram-probe.log and c1-trigram-transactions.log. New run100000 names, all10 exact result/order cases passed; manual same-transaction source+index update commit/rollback and delete commit/rollback assertions passed, reopen kept99999 index rows and no deleted-term hits. No crash/fsync, trigger sync, migration or production SLO proof. Java21 uses actual SQLite shipped in current ItemGuard JAR. No src/main/pom/schema change.

Next gate: independent read-only Opus critique of this design and probe, then broaden executable comparison before choosing migration architecture. Do not count review as implementation acceptance or close C1.

## Compatibility findings after initial critique failed transport

`CatalogUnicodeProbe.java`, logs `c1-unicode-probe.log` and `c1-unicode-guarded.log`: nine queries against actual packaged SQLite in memory. Two literal queries containing U+0000 produce FTS SQL errors despite matching source rows via instr. Seven other cases match, including composed/decomposed Vietnamese, supplementary Unicode, ROOT folding, quotes and a suffix after a malformed Java surrogate (only post-JDBC SQL semantics tested). This does not establish all-Unicode completeness.

Candidate routing must keep queries containing U+0000, like short queries, off MATCH and use the existing bounded exact path. Guarded experiment asserts all nine results equal SQL instr oracle. No source-domain rejection or silent empty response permitted; fallback performance is unresolved. Source values with NUL are not proven universally safe merely because the sampled suffix passed.

Trigger feasibility and filtered search evidence: `2026-09-09-trigger-maintenance-evidence.md` and `2026-09-09-filtered-trigram-evidence.md` under docs/reviews. Dedicated connection-lifetime normalization is required; current per-query registration must not be repurposed. Migration/backfill/readiness/repair remain unimplemented; earlier Opus review failed transport and has no accepted verdict.
