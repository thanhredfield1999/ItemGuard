# C1 — history index and failure-safe catalog paging

Status: scoped implementation design; user said `làm đi` after remaining roadmap recommendation C1. Source/offline only, no deployment, Paper, existing production DB, external API or destructive action. Schema change limited to the proposed additive history index; no new tables/columns/data rewrite.

## Observed root causes and contract

- Schema v7 has no index for `WHERE code=? AND item_uuid=? ORDER BY timestamp DESC,id DESC LIMIT 100`. Existing synthetic JDBC probe showed a scan/sort and SQLITE_INTERRUPT. Index experiment fixed that query, not arbitrary text searches.
- Controller mutates cursor/previous/filter before success; error screen offers reset but no retry of the failed target and can confuse cached rows with changed filters. Global read admission stays one-inflight plus 1s; do not weaken it.

## Migration (v7 -> v8, older supported versions still use existing initializer)

- Only add nonunique, nonpartial `idx_history_identity_time` on `item_history(code,item_uuid,timestamp DESC,id DESC)` using BINARY collation. No row rewrite, delete, dedupe, backfill, table replacement or drop of unknown indexes/tables.
- Initialize on the existing single DB-owner thread in its manual JDBC transaction. Reject auto-commit before any DDL, validate schema metadata before mutation. Metadata id=1 must contain SQLite integer version in [1,8]; missing/corrupt/future versions fail closed. Fresh DB without plugin_stats is supported by existing behavior; this is not an all-table forensic validator.
- Create index then inspect PRAGMA index_list/index_xinfo: exact table, columns, ordering, collations, nonunique/nonpartial. Name collision or incompatible existing definition is an error, never DROP/repair it. Repeat init must preserve the existing index and data.
- Write version 8 only after successful index creation/validation, in same transaction as all initialization DDL. On SQLException roll back, preserve original exception with rollback failure suppressed. Close/reopen verification checks durable version/data/index outcome. Interrupted CREATE INDEX must not publish v8.
- Bound CREATE INDEX through a temporary SQLite ProgressHandler: check every 1000 VM instructions; proposed cap 100000 checks or 30s elapsed must be calibrated on the 1M workload before acceptance. Clear in finally before commit and rollback/other work. These are sampled CPU/elapsed limits, NOT an I/O/lock/OS/fsync deadline. Larger-than-tested DBs may fail startup rather than exceed the cap; this is not a supported production size claim. Existing driver busy timeout and sidecar ownership remain unchanged.
- Index creation consumes disk/temp space and writer time. Startup constructor still waits for DB initialization; no main-thread query or background live migration is added. A large/disk-full/locked DB can reject startup; release requires stopped-server consistent backup, free-space planning, maintenance window, controlled migration and restore rehearsal. No live enable approval from this slice.
- Rollback to an old JAR is not automatic: v7 correctly rejects v8; use stopped backup restoration through a separately approved deployment procedure. No downgrading schema stamp over a v8 DB.

## Paging behavior

- Keep a committed catalog query/cursor-stack paired with the cached successful page. Build immutable proposed request state for next/previous/filter/reset; commit it only when that request succeeds under current generation/permission.
- While loading, invalidate old actions as today. Error UI offers `Thử lại` for the exact failed target and `Về trang trước đó` to the last successful cached page (including its filters/cursor stack); first-load failure has no fake successful page. Busy/error never becomes empty results. No automatic retry or loop.
- History errors retry the same selected identity with history permission and return to that profile; no catalog filter controls on a history-error screen. Catalog failure never uses a stale selected profile as its back target. Preserve existing generation, post-show and close/quit guards. No return-to-cached-history action after failure: history renders only after a successful identity-bound request; regression covers A-history cached then B-history failure/retry.
- Chat cancel/invalid input returns to last appropriate screen without relabeling cached rows under a failed filter. Keep maximum 128 catalog pages and 100 retained history events, not snapshots/full coverage.

## Verification

1. Behavioral RED then GREEN: frozen v7 DDL/data fixture upgrades to exactly v8 with exact index, retained unknown data and unchanged history rows; reopen idempotence. Fault after index/before version commit rolls back on disk; conflicting index/malformed metadata/future version/autocommit reject without mutation. Existing v1/v6 tests remain.
2. JDBC indexed query on 1M synthetic history events: exact identity and timestamp/id tie ordering; plan uses index without temp sort. Query budget unchanged; interrupted search must not poison next write/read. No existing DB path accepted by probe.
3. Controller tests: failed next/previous/filter keeps successful state; retry identical query; double/stale clicks do not skip; permissions/close discard callbacks; history failure remains in profile context.
4. Measure synthetic migration time, DB bytes, filtered search outcomes and mixed FIFO read/write/retention. Do not claim production SLO or that history index fixes contains-text search.
5. Full Java21 clean test package; exact artifact/hash and independent Opus5 review; source/test evidence. Paper/client/production and remaining roadmap gates still open.

## Design review disposition (Opus5, c1-design-review.json)
- PASS_FOR_BOUNDED_IMPLEMENTATION with amendments. A1: stale history risk is prevented by no cached-history return and generation/selection invariants; add cross-item regression, do not introduce new cache subsystem. A2: operation-kind error routing plus no history-error catalog filters. A3: cap acceptance requires measured VM callbacks/migration time; no arbitrary larger-size promise.
- Tests include no fake empty after initial failure/chat cancel, previous-stack failures, repeated retry, handler cleared before commit (instrument JDBC proxy), xinfo key rows only, migration rollback/reopen and shared-gate behavior. Unsupported old-table corruption beyond version/index validation remains outside this slice.

## Calibration observed (c1-scale-compiled.log)
- Three independently generated DBs: 1,000,002 history rows (1M base + two identity impostors). Migration used 12,000 progress checks of the 100,000 cap each run, ~1.31–1.37s vs 30s sampled cap. Retain proposed bounds for this offline candidate; larger DB admission/support remains unproven.
- DB bytes before/after: 41,435,136 / 68,591,616 (includes all initializer additions, not index-only attribution). 60 exact history queries returned correct identity and timestamp/id tie order; no temp sort. Missing contains-text filter on 100k tracked records still interrupts; mixed reads/writes and retention/reopen continue after that failure.
- Source-launcher cannot use the package-private calibration seam across classloaders. Compile probe with javac and launch with the JAR in the same app classloader; do not expose test constructor publicly.
- SQLITE_INTERRUPT during CREATE INDEX auto-rolls back SQLite's transaction; cleanup rollback can report no active transaction and is preserved as suppressed. Failed-initialization connection is closed by existing owner lifecycle. Recovery evidence uses close/reopen, not falsely claiming that connection is ready for another commit.
