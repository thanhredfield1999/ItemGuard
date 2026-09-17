# Filtered trigram experiment — synthetic JDBC only

Executed Java21 source probe `tools/catalog/CatalogFilteredTrigramProbe.java` against actual SQLite packaged in target/ItemGuard-1.0.0.jar, no product source/schema change. Fresh temporary DB per invocation; arguments rejected. Logs `c1-filtered-trigram.log`, `c1-filtered-trigram-final.log`, both process exit0.

30/30 exact-result attempts in final run, no interrupts: 10 cases repeated3 on one100000-row DB. Separate normalized name/code/material FTS columns, exact instr residual, owner/material/cursor filters before LIMIT37. In-memory generated oracle compares every returned ID in order. Includes rare owner at tail, nonexistent owner, full code at tail, sword material, decomposed Vietnamese, cursor, 1/2-codepoint absent fallback. Fixed-width generated code order equals integer id order in this fixture only; product code ordering/mapping not established.

Initial run table-only4141056 bytes vs indexed19685376 bytes; total includes duplicated normalized content and FTS internals, not projected production cost. Final run100 individually committed source-only updates898.352ms vs source+index updates1035.701ms. Sequential single sample, restoration between modes, no statistically valid overhead ratio/production claim. Disk cache, index layout and ordering differ; not A/B performance acceptance.

Executable counterexample confirmed: source-only rename gives actual1 matching row but indexed0. Final exact residual cannot recover omitted FTS candidates. Counterexample transaction rolled back. This blocks simply bolting an index onto reads without atomic coverage of ALL writer paths. Existing manual dual-write/rollback probe proves SQLite capability, not product synchronization.

Material equality in probe is not full CatalogCategory predicates; integer cursor is not arbitrary code collation; no full corpus Unicode/NUL, all pagination pages, actual owner executor/admission/mixed gameplay writes, long scalar cost, index drift recovery/backfill/migration/crash proof. Short normalized scans succeeded here, not universal SLO. Candidate remains design/experiment only; C1 still open, no Paper/deploy/production mutation.

Next implementation prerequisite: audit all tracked_items mutations and choose atomic derived-index maintenance + bounded backfill/readiness state. Independent reviewer was dispatched separately; no review PASS asserted here.
