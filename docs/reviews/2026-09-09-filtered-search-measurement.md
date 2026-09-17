# C1 filtered search and mixed workload measurement — 2026-09-09

## Verdict

Measurement/oracle/write preservation VERIFIED on synthetic JDBC only. General contains-search scale FAILS; not a performance gate PASS, Paper SLO, or production benchmark. Product code/schema/budgets unchanged. Current JAR `c8f95756adedb4a52c09908df0d43f90b7d64d9015ce081ff3b93501d7f6a534` is exactly the C3 offline candidate (378 tests).

## Reproduction

`tools/catalog/CatalogFilteredProbe.java` creates new temporary directory + 3 fresh DBs, refuses all arguments (no existing/production database accepted). Compile with Java21 javac against target JAR into fresh temp classes directory; run `java -Xmx512m -cp <fresh-classes>;target/ItemGuard-1.0.0.jar com.itemguard.catalog.CatalogFilteredProbe`. javac/application classpath required, not Java source-launch mode. Command exit0 means measurements and all result/write assertions completed, not all queries passed.

Actual log `docs/reviews/c1-filtered-probe.log`; Python parses all 90 QUERY rows/3 MIXED rows/3 REOPEN rows into `c1-filtered-summary.json`, verifies case count9 each and persisted50 writes per DB. Dataset 100000 tracked items each DB: alternating sword/pickaxe, Lan/Minh, common Blade, Unicode KIẾM RỒNG, one RareSuffix at C100000. Independent Java oracle uses generated corpus, NFC/ROOT fold and exact code/UUID/order/hasMore comparison on successful results. Errors must be SQLITE_INTERRUPT, never substituted empty results.

## Results

Each case has 9 attempts (3 fresh databases × 3 repeats). Query elapsed includes CompletableFuture completion, not only SQL CPU.

| Case | OK / interrupted | Median ms | Max ms |
|---|---|---|---|
| Unfiltered first page | 9 / 0 | 0.717 | 6.879 |
| Common text | 9 / 0 | 0.721 | 1.463 |
| Owner Lan | 9 / 0 | 0.783 | 1.156 |
| Owner Lan + sword | 9 / 0 | 0.665 | 1.181 |
| Decomposed Vietnamese name | 9 / 0 | 1.453 | 3.411 |
| Rare name at tail | 0 / 9 | 250.354 | 253.973 |
| Missing text | 0 / 9 | 250.280 | 250.439 |
| Contains full code C100000 | 0 / 9 | 250.352 | 253.642 |
| Missing owner | 9 / 0 | 19.333 | 24.513 |
| Rare name with cursor C099990 | 9 / 0 | 0.683 | 1.120 |

Every standalone read was followed by a real serialized SQL history write. Highest following-write elapsed36.844ms. Interrupted reads did not poison the connection; subsequent unfiltered exact results and persisted counts verified.

Mixed sequence per DB: deterministic latch occupies owner solely to establish FIFO queue position; submit one read through REAL shared CatalogReadGate(System::nanoTime), queue20 real SQL writes behind it, attempt5 additional reads (all rejected without invoking query), release latch. This is bounded fixture queueing, NOT full gameplay load and NOT bypassing admission. Admitted no-match read interrupts; all20 writes complete. Submission-to-completion maxima443.707/449.313/481.426ms; p50 values349.252/359.026/384.112ms. These include staged submission/latch time, SQL read wait and serialized transaction overhead, not pure query lock time.

Every stopped/reopened database retains100000 tracked rows and50 committed history writes (30 post-query +20 mixed); exact tail cursor read also passes. No data-loss claim beyond this cooperative single-owner local synthetic scenario.

## Root cause and next decision

`CatalogRepository.find` orders/seeks by code but applies general instr(ig_catalog_fold(...),...) to item_name/code/material row by row. Rare/no-match input needs broad scan + Java UDF work; LIMIT37 only caps results, not scans. Both 2000 progress callbacks and250ms sampled policy remain intact. Narrow explicit cursor succeeds without proving general search coverage.

Closing all substring scale requires a reviewed search contract/storage choice, not a history index:
1. Preserve full literal substring semantics: design a durable normalized substring candidate index, keep final exact NFC/ROOT predicate, backfill/update/rebuild/error and short-query rules. More disk and write cost; schema/migration requires approval/design/TDD.
2. No new search schema: separate exact code lookup with existing code index plus explicitly partial resumable bounded scanning for broad text. Less disk work but introduces progress/incomplete results and different UX; not a full search-completeness claim.
3. Keep current contains-search behavior and expose its failure; leaves C1 open. Increasing budget or silently changing substring to prefix/token search is NOT a fix.

These are PROPOSED alternatives, not approved implementation. C2/C3 external adapter work still independently needs server folder/installed plugin JAR metadata; no version guessed. No Paper/runtime/production writes, no new fixture authorization or reused receipts.
