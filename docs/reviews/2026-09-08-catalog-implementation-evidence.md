# Catalog A — offline implementation evidence

Status: OFFLINE_CANDIDATE_ONLY / NO_LIVE / NOT RELEASE READY.
User “tiep tuc di” interpreted as continue recommended A. No explicit visual acceptance, Paper authorization, schema migration approval or production action.

## Delivered slice

- `/ig browser`: 54-slot read-only catalog, 36 results/page, keyset code ASC, 128-page navigation cap; next/back/refresh/filter reset.
- `/ig browser <player>` keeps previous player browser path. Current catalog requires `itemguard.gui` + `itemguard.search`; history additionally checks `itemguard.history` and `.others` at submit, callback and navigation.
- Name/code/material literal search (NFC + Locale.ROOT lowercase); exact recorded-owner-name filter; material-based sword/armor/tools/ranged/other categories.
- Profile and newest retained 100 history records constrained by BOTH code + item UUID, paginated 36 each; event timestamp (server timezone with offset), actor, location and detail.
- Explicit incomplete coverage/retention. Recorded owner is NOT physical holder; actor is NOT custody. Plugin provenance, HavenBags/storage adapter, custody chain remain unsupported, visible as such.
- Scalar immutable DB DTOs; preview item is a fresh material + capped literal Adventure text. No original PDC/snapshot deserialization or grant/delete path.
- All clicks/drag cancelled at LOWEST; only left/top/exact viewer dispatches deferred action. Generation + active inventory + permission guards; prompt invalidation on close/quit/command/foreign open/timeout/disable; one consumed async chat request returns via main-thread handoff.
- SELECT on existing owner executor with SQLite progress handler (sample each 1,000 VM steps, 2,000 progress calls OR sampled 250ms; NOT hard I/O deadline). Shared catalog admission: one outstanding request + 1s minimum start interval across viewers. Failures display error, never successful empty results.

## Fresh verification

- Java 21.0.4, Paper API 1.21.11, Maven: `cmd.exe /d /s /c "mvnw.cmd clean test package --no-transfer-progress"`, JAVA_HOME pinned to `C:/Program Files/Java/jdk-21`.
- `docs/reviews/catalog-full-maven-final.log`: 334 tests, failures=0, errors=0, skipped=0; BUILD SUCCESS. Of these, 30 catalog tests; counts parsed from Surefire XML, not inferred from test method names.
- Unit + real SQLite + real Bukkit event objects with dynamic proxy Player/Inventory/InventoryView/BukkitTask and fake Host render/scheduler. NOT running Paper, permissions provider, client render, actual scheduler or legacy-chat bridge.
- RED/GREEN logs: catalog-page, filters, guards, history, session, flow, navigation, actions, submit, unicode, gate, review-r1. Source-wiring checks are supplemental. Lifecycle coverage added after code was green is COVERAGE, not retrospective behavioral RED.
- `git diff --check` passed. Final manifest records source/tests hashes, test-log SHA and packaged byte equality for all 24 catalog class files, plus SQLite Function/ProgressHandler presence.
- Artifact `E:/AI.WORK/ItemGuard/target/ItemGuard-1.0.0.jar`, SHA-256 `0c86700e863e7835951e277d847b8c089d270eb5fba3a9fc775a1bdb0d07aaed`.
- Build retains deprecated PlayerQuitEvent test constructor/legacy AsyncPlayerChatEvent notice, annotation-processing notice and shaded META-INF/MANIFEST.MF overlap warning. Not a warning-free build.

## Independent review / parent dispositions

- `catalog-r1-review.json`: PASS_FOR_OFFLINE_CANDIDATE with pre-live issues. Queue flooding, Vietnamese case mismatch, submit after denied view and misleading error guidance were confirmed/fixed with regression evidence; View lifecycle coverage added via Host seam. Missing index retained as explicit blocker, no schema change made.
- `catalog-r2-review.json`: PASS_FOR_OFFLINE_CANDIDATE / no-live. `catalog-r2-manifest.json` binds reviewed production source. Final production hashes unchanged; only two tests strengthened after R2, recorded in `catalog-final-manifest.json`.
- Speculative fake tracking from ContainerListener open: REJECTED by exact call path `ItemTrackingService.java:809-824` resolving physical slot BEFORE publication, `BlockContainerPhysicalSlotResolver.java:66-77` requiring Container holder. CatalogInventory is not Container. Follow-up reviewer agrees. Test now supplies a non-null Location/World so holder guard is isolated; every 54 preview slots is rejected. Extra bounded per-open loop/cooldown allocation remains; not Paper performance proof.
- Follow-up `catalog-followup-review.json` preserves bounded offline verdict. It inspected pre-strengthened holder test; subsequent test-only non-null location addition removes noted vacuity, fresh full suite rerun. No production patch after R2.
- Remaining: global 1s throttle can interrupt fast paging/multiple admins; failed page request returns error/reset rather than fake success. Shared DB/read UDF elapsed limit is sampled. Stuck owner future deliberately does not reopen admission (no watchdog that can admit overlapping reads). No claim all operational risks solved.

## Scale blocker measured, not hidden

`tools/catalog/CatalogScaleProbe.java` creates a NEW temp SQLite database, never opens an existing DB; 1,000,000 synthetic history rows, 100 exact target records. It runs the real catalog repository from the packaged JAR.

- Current schema: EXPLAIN shows SCAN item_history + TEMP B-TREE; bounded query fails with SQLITE_INTERRUPT. Subsequent owner read confirms all 1,000,000 records intact.
- Index only in that new scratch DB: `(code,item_uuid,timestamp DESC,id DESC)` changes plan to index SEARCH; exact 100 rows match range. This is an experiment, not a shipped migration/benchmark of production.
- `catalog-synthetic-scale-final.log` contains actual output. No product schema/index change; no production database or Paper touched.

## Still open

Large-history indexing/migration design and realistic workload/latency validation; actual Paper inventory/creative/drag/chat/plugin interaction and user visual acceptance; plugin attribution, HavenBags, full storage coverage/custody and remaining roadmap P0/P1–P6. Existing consumed fixtures/receipts remain consumed. No deploy/restart/commit/push or runtime PASS generated.
