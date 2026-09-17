# C1 — history index and failure-safe paging evidence

Status: VERIFIED_OFFLINE_CANDIDATE; implementation review conditional PASS plus bounded disposition followup PASS_FOR_OFFLINE_CANDIDATE; parent source/JAR checks satisfied. NO_LIVE / NOT RELEASE READY. User requested execution after remaining-roadmap C1 recommendation. Only source, tests, docs, generated build files and synthetic local DBs changed. No Paper, production migration/deployment/restart, commit or push. Blocked `.hermes/WORKING_STATE.md` untouched.

## Latest exact artifact after review fixes (supersedes earlier measurements below)

- F1 budget diagnostics and F3 original-error preservation implemented RED→GREEN; `c1-review-disposition.md` evaluates all six findings without accepting unsupported safe-size/free-disk extrapolations. Fresh/v1/v6 index assertions added, plus rare selective filter at end of 100k records. F4/F5/F6 handling documented, no invented timeout release or proxy-owner redesign.
- `c1-full-maven-final.log`: fresh Java21 clean test package, 351/351 PASS (38 catalog, 9 migration). `target/ItemGuard-1.0.0.jar` SHA-256 `179606b04b25868ed0d199ad1722dd6db7215d6908772c284f42b9a129cbe84a`; 216 packaged classes byte-matched. Current source/tests/probe pinned in updated `c1-final-manifest.json`. Initial review files not changed by followup remain hash-identical; changed files match exact followup manifest.
- `c1-scale-final.log`: three fresh DBs, migration 1398.300–1508.307 ms; 12k progress checks each; 60 exact 100-event history queries min 0.3223 ms, median 0.4382 ms, nearest-rank p95 1.4137 ms, max 6.1008 ms. Bytes/counts/plans unchanged from prior probe. Timings are synthetic samples, not production SLO.
- `RareSuffix` (one hit at last code) AND `not-present` still interrupt under unchanged budget, ~222–254 ms; matching `Blade` returns 36. Each run then passes 30 writes/30 reads (~398–516 ms), 19,999-row retention and reopen exact counts. General search scale remains open.
- `c1-diagnosis-red.log` / `c1-diagnosis-green.log`; `c1-cleanup-red.log` / `c1-cleanup-green.log`. Cleanup fault tests use injected cleanup actions, not native disk/JNI failure. Real JDBC probe checks ordinary interrupt/recovery after query failure.
- Static regex scan of modified production files found no secret assignments/process execution/dynamic SQL concatenation at that pass. Broader security/runtime review is not claimed. All SQL identifier formatting added here uses compile-time schema constants, not user input.

## Changes

- `SqliteSchemaManager`: schema 8, additive `idx_history_identity_time(code,item_uuid,timestamp DESC,id DESC)`; nonunique/nonpartial/BINARY exact validation, no DROP/repair. Version stamp in same transaction after index creation and validation. Reject auto-commit and corrupt/future metadata. Preserve original exception if rollback fails. Index build budget: 100k sampled callbacks / 30s elapsed, callback every 1000 VM operations; clear handler before commit/rollback.
- `CatalogController`: immutable proposed query/cursor stack, commit cached page/filter state only after query success; retry exact failed target, return to last successful page. Initial failure/chat cancel remains error, never empty success. History errors retry with history permission, no catalog-filter controls or stale profile back target; stale item-A history action cannot show under item B.
- `SqliteHistoryMigrationTest` new; `CatalogControllerTest` extended. `SqliteConnectionOwnerTest` future-schema sentinel changed from literal 8 to CURRENT_SCHEMA_VERSION+1 because 8 is now supported; failure/lock-release assertions retained.
- `tools/catalog/CatalogC1Probe.java`: separate synthetic workload probe; accepts no arguments/existing DB path. Package-private calibration seam compiled into same classloader, not made public.

## Behavioral RED -> GREEN

Logs under `docs/reviews/`:
- `c1-migration-red.log` -> `c1-migration-green.log`: expected v8, actual v7; frozen pre-index schema/data preserved across migration and reopen.
- `c1-index-conflict-red.log` -> `c1-index-conflict-green.log`: incompatible named index previously silently accepted. Six variants: wrong columns/order/collation/uniqueness/partial/table; reject without repair or stamp.
- `c1-metadata-red.log` -> `c1-metadata-green.log`: bad metadata accepted, autocommit rejected too late after durable DDL; fixed before mutation.
- `c1-rollback-red.log` -> `c1-rollback-green.log`: rollback exception hid original stamp failure; now suppressed.
- `c1-budget-red.log` -> `c1-budget-reopen-green.log`: CREATE INDEX ignored budget; real JDBC interrupted for count and elapsed limits; v7/index absence durable, reopen migration successful. Initial `c1-budget-green.log` failed due to harness attempting commit after SQLite automatically rolled back an interrupted transaction. Removed that unsupported harness assertion; did not loosen production behavior. Existing owner closes failed-init connection, recovery uses reopen.
- `c1-paging-red.log` -> `c1-paging-green.log`: no exact-target retry; cursor stack committed only after success.
- `c1-history-context-red.log` -> `c1-focused-green.log`: history error mixed catalog filters; now operation-kind-specific. Other coverage includes previous-page/filter failure, real admission gate repeated denials, initial failure/chat cancel, stale callback/action, item A/B histories, permission loss.
- Post-index stamp trigger failure, missing/null metadata and handler-cleared-before-commit proxy probes are additional coverage, not separately claimed behavioral RED.

## Fresh full verification

Command: `python C:/Users/thanh/AppData/Local/Temp/itemguard-catalog-maven.py clean test package` (sets Java21 and invokes `mvnw.cmd ... --no-transfer-progress`).

- First full log `c1-full-maven.log`: 349 tests, 1 failure — old future-schema sentinel 8 was now a valid version. Corrected test to CURRENT+1.
- Full rerun `c1-full-maven-r2.log`: 349 tests, failures/errors/skipped 0; BUILD SUCCESS. 36 catalog tests; 9 new migration tests. Warnings: existing deprecations/PlayerQuitEvent, annotation processing, absent SLF4J provider and shading overlap warnings; no clean-warning claim.
- Packaged JAR `target/ItemGuard-1.0.0.jar`, SHA-256 `a11185e92844f2fe2c12cdcbad9f784e0fa0084dc67f01bed804d5b235529062`.
- XML totals parsed; all 215 `com/itemguard` class entries in JAR byte-match target/classes. `c1-final-manifest.json` pins source/tests/probe/manifest inputs. Implementation review pack files unchanged at verification. `git diff --check` PASS; `codegraph sync E:/AI.WORK/ItemGuard` already up to date.

## Real JDBC synthetic workload

Compile and launch:

    javac -encoding UTF-8 -cp target/ItemGuard-1.0.0.jar -d target/c1-probe tools/catalog/CatalogC1Probe.java
    java -cp "target/c1-probe;target/ItemGuard-1.0.0.jar" com.itemguard.persistence.CatalogC1Probe

Use Java21 binaries. Source launcher failed classloader/package-private access (`c1-scale.log`), then compiled same-loader probe passed (`c1-scale-compiled.log`).

- Three fresh DBs, each 1,000,002 history rows: 1M base rows + two identity impostors. Migration 1307.587–1370.645 ms; 12,000 callback checks per run under 100,000 cap. Byte size 41,435,136 -> 68,591,616 (all initializer additions, not index-only disk attribution).
- EXPLAIN uses `SEARCH item_history USING INDEX idx_history_identity_time (code=? AND item_uuid=?)`, no temp sort. 60 reads verify all 100 expected event IDs/timestamp ties and exclude impostors. Timings min 0.3921 ms, median 0.53205 ms, nearest-rank p95 1.084 ms, max 5.2888 ms. Sample only, not representative production performance or p95 SLO.
- 100k tracked items: matching `Blade` returns 36; `not-present` contains-text search still SQLITE_INTERRUPT (~245–251 ms). Not fake empty. General filtered-search scale remains OPEN; history index is not a text-search index.
- After failed search each DB runs 30 FIFO writes + 30 exact history reads: all writes durable, results correct (~413–486 ms total). This is direct SQL insert through real serial-owner path, not gameplay tracking/publication or server density benchmark. Probe bypasses UI admission deliberately to exercise serial queue; controller real-gate tests separately verify denials/retry.
- Retention uses same SQL predicate as repository (`DELETE FROM item_history WHERE timestamp < ?`), deletes exactly 19,999 rows; 99 matching target events retained. Close/reopen verifies 980,033 remaining rows and exact target count. No change to retention implementation/index; its broader scan cost remains a limitation.

## Review / remaining gates

- Design `c1-design-review.json`: Opus5 PASS_FOR_BOUNDED_IMPLEMENTATION with amendments; parent disposition and measured budget calibration in `docs/design/2026-09-09-catalog-c1-index-pagination.md`.
- Implementation `c1-implementation-review.json`: conditional PASS_FOR_OFFLINE_CANDIDATE; disposition `c1-review-disposition.md`, exact bounded followup `c1-followup-review.json`: PASS_FOR_OFFLINE_CANDIDATE. Followup accepts F1–F6 dispositions and patch/coverage only, not broad re-review. Parent hash/packaged-class verification supplies the artifact condition missing from review snapshot. Earlier 349-test artifact remains historical; latest section above wins.
- Followup clarification F6: SerialDatabaseExecutor.close can shutdownNow after its own termination timeout and discard queued tasks. This differs from the owner closeConnection timeout branch discussed in disposition; no proof of all shutdown-race behavior is claimed. Remains pre-existing failed-close limitation, not grounds to prematurely release one-inflight gate. v1/v6 tests create missing history table; only frozen v7 test proves upgrading populated old history.
- C1 indexed-history and retry slice does not complete general search-scale, Paper/client visual, plugin provenance/HavenBags/custody or P0–P6.
- Sampled VM/time cap is not an I/O/OS/lock deadline. No disk-full or power-loss test. Large startup migration can deny startup; no proven support envelope for larger DBs. Maintenance/free-space/consistent stopped backup and restore rehearsal required before separately approved live deployment. v7 JAR rejects v8 DB; rollback requires stopped backup restoration, never version-stamp downgrade.
