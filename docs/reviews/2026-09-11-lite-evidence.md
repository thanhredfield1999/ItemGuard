# 2026-09-11 ItemGuard LITE offline evidence

## Status

OFFLINE_CANDIDATE_ONLY / NOT_RELEASE_READY. User approved continuing LITE for SpigotMC distribution on Paper and investigating older/newer versions. No production changes, server startup, fixture reuse, commit or push. Existing P0/native/runtime gates remain open. Independent delegated review/implementation did not execute because the child model configuration failed; no reviewer PASS is claimed.

## Changes in this slice

- `SqliteConnectionOwner`: SQLite INTERRUPT can end the native transaction while JDBC still reports auto-commit false. A subsequent JDBC rollback can fail. Reusing that connection left later commit/rollback broken. On rollback failure the owner now closes the old connection and recreates/configures one under the existing serial executor and retained process lock, without replaying the failed operation. If closing/reopening fails, queued/new work fails closed; shutdown still owns cleanup.
- `SqliteInterruptionRecoveryTest`: real SQLite interrupted-write recovery, next transaction rollback atomicity, reopen persistence; injected uncertain close rejects queued/new work and retains database ownership. Existing `CatalogBackfillTest` now passes without weakening its interruption/retry assertion.
- `ItemGuard`: protected registration seam and explicit default Full edition indicator; existing Full routes remain intact.
- `lite/ItemGuardLite`, `lite/LiteCommand`: separate restricted command entry point, bounded recent history/read-only preview, one outstanding query, permission rechecks on callback, preview click/drag cancellation, plugin-disable closure.
- `ConfigManager`: LITE English fallback, forced NOTIFY duplicate action, disabled optional WorldGuard/Discord. Full defaults preserved.
- `resources/lite/*`, `scripts/package_lite.py`: dedicated descriptor/config; clean full build then shaded artifact transformation and byte verification. No public-release or runtime automation.

## Verification

- Baseline full test/package failed in SQLite interrupted catalog backfill. Log: `lite-baseline-build.log`.
- Focused SQLite RED: `lite-db-red.log`, failed the next-transaction recovery assertion after actual native interruption. Focused GREEN: `lite-db-green.log`; full post-fix build: `lite-db-full.log`.
- LITE initial command RED: `lite-command-red.log` (missing new feature symbols; not a runtime assertion RED). Additional permission/admission and preview tests are coverage, not retrospectively claimed TDD RED.
- Explicit duplicate config RED: `lite-notify-default-red.log`; forced NOTIFY policy RED: `lite-notify-policy-red.log`.
- Final `JAVA_HOME='C:/Program Files/Java/jdk-21' python scripts/package_lite.py`: Maven **clean verify**, 397 tests, 0 failures, 0 errors, 0 skipped. Log `lite-package-build.log`. Packaging checks passed after fixing ZipInfo reuse: zip writing mutates offsets, so copy ZipInfo before writing the new archive.
- Final LITE JAR SHA-256: `dd4a32becc52cfbe3b1ec44cde83250d97a4c6d893f7ccfd8e77ed84d4654ece`.
- Source shaded Full JAR SHA-256: `46c7402e64ef257fe41184ecb218b3ddff9a0f1f402fd8e41bc91d1e55be2f48`.
- Metadata retains ItemGuard identity, selects `com.itemguard.lite.ItemGuardLite`, declares Paper API 1.21.11 and only the itemguard command plus ig alias. All shared classes remain packaged, including dormant Full commands; this is an entry-point/product-surface split, not physical code exclusion.

## Version audit

Final frozen source audit root:
`E:/AI.WORK/30_KET_QUA_THU_NGHIEM/itemguard-compatibility/compile-ba50cd57abce`.

Both compile lanes exit 0:

- Paper API `1.21.4-R0.1-SNAPSHOT`, JDK 21.
- Paper API `26.2.build.123-stable`, JDK 25, compiler release 21.

No tests/server runs in these lanes. The helper's static `scope` field says 'pre-LITE'; that text is stale, not the provenance. `source-manifest.json` includes the final LITE sources/resources. Preserve the raw receipts; use input hashes for interpretation. Canonical api-version remains 1.21.11, so this is not an enabled 1.21.4 backport. Reflection and item serialization are not verified by compilation.

## Remaining gates

- Independent read-only fallback preflight: Claude Code 2.1.233 reports `Claude API account`, not a confirmed Max subscription path. Per the user's no-unapproved-paid-API preference, no model request or auth/config change was made. Review remains blocked pending a known approved reviewer route.
- Final independent parent read-back: all 177 frozen audit inputs match canonical bytes; all 226 application classes in the LITE JAR match fresh target/classes; artifact SHA and NOTIFY config match the receipt. These checks are packaging/compile evidence, not independent model review.

Independent exact-tree review, approved fresh controlled runtime with required authority/fixture admission, Paper lifecycle, commands/permissions/preview mechanics on the real server, item tracking/duplicate-warning journey, stop/restart and client visual acceptance. Broader existing stress/stackable/crafting/native authority gaps are not closed by this candidate. No SpigotMC upload should be inferred.
