# LITE L0/L1 — rebuild, independent review BLOCK, corrections (2026-09-12)

## Exact artifact after corrections

- LITE JAR `target/ItemGuard-LITE-1.0.0-test.jar` SHA-256 `b94170b2681ed44e700d107b2fdb17456bed7f5021bcc95e520006c178a67be5`
- Source Full JAR SHA-256 `cce0e672557a21f869af8a887654f83ab55c35c11c434e3c26adad2d2fd74cce`
- `JAVA_HOME='C:/Program Files/Java/jdk-21' ./mvnw.cmd clean test package`: **418 tests PASS**, 0 failures/errors/skips
- `python scripts/verify_lite_artifact.py`: `ARTIFACT_CONSISTENT_OFFLINE_ONLY`
- `python -B scripts/test_verify_lite_artifact.py`: 8/8 PASS

The previous build in this session was SHA `88da1c3f00d99a54ae64a50cb62d82c272957e96d6cc18d51e1daba3655d0c55`; it is superseded and must not be tested or published. The historical smoke hash `d86135e7…769a0de8` is older still and carries no evidence for this artifact.

## Independent review

Direct Claude Opus CLI, read-only, plan mode, frozen inputs in `2026-09-12-lite-input-manifest.json`. Prompt: `2026-09-12-lite-review-prompt.md`. Raw output: `%LOCALAPPDATA%/Temp/itemguard-lite-review-20260912.txt`.

Verdict on the pre-fix artifact: **BLOCK**, 3 blockers. The reviewer produced no runtime, client or release evidence and wrote nothing into this repository.

## Disposition

### Blocker 1 — member could read another player's name and coordinates: CONFIRMED, FIXED

Reproduced from source, not accepted on assertion. `ItemSqliteRepository.getHistoryByPlayerAsync` joins `tracked_items item ON item.code = history.code WHERE item.owner_uuid = ?` (lines 789–796) and returns `history.*`, i.e. every retained event for that identity. `owner_uuid` is rewritten to the acting/current holder by the `UPDATE tracked_items … SET owner_uuid = ?` paths (lines ~166, ~191). So a member who receives a tracked item inherits the window containing a previous holder's rows, and `LiteHistoryView.eventLine`/`eventIconLore` printed `getPlayerName()` and `getLocation()` verbatim.

RED first: `src/test/java/com/itemguard/lite/LiteHistoryPrivacyTest.java`, 3 cases. Observed RED was exactly the disclosure assertion (`memberDrilldownHidesAnotherPlayersNameAndCoordinates` failed at the name/location check), with the other two already holding.

Fix (smallest, no ownership-semantics change): `LiteHistoryView.disclosesActor(row, viewer, staffView)` withholds only the foreign actor's name and location; the event, action and timestamp remain visible. `LiteCommand.printTimeline`/`openTimeline` pass the viewer UUID and `itemguard.history.others`. Staff output is unchanged — asserted by `staffKeepsFullActorDetailForInvestigation`.

### Blocker 2 — wrong artifact SHA in the testing doc: CONFIRMED, FIXED

`docs/LITE_TESTING.md` advertised a stale hash while binding test counts to "this exact artifact". Now carries the current LITE and source hashes, the 418-test count, and an explicit note that earlier runtime results belong to superseded hashes.

### Blocker 3 — verifier printed the permission surface without asserting it: CONFIRMED, FIXED

The old script stored `facts['permissions']` and used a denylist of three Full command names, so a descriptor shipping `itemguard.history.others: default: true` would still pass. Replaced with an allowlist: `EXPECTED_COMMANDS` plus `EXPECTED_PERMISSIONS` asserted through `check_surface`, a real `permission_defaults` parser, and an alias check. Four new RED→GREEN cases in `scripts/test_verify_lite_artifact.py` cover extra command, exact expected set, loosened `history.others`, and a missing declaration.

### Non-blocking notes

- `CleanupTask` gate: ACCEPTED AND FIXED. It was off only because the shipped config sets `interval-hours: 0`, while `ConfigManager` defaulted to 24 for an absent key. Now `plugin.isLiteEdition() ? 0 : …`, with a test asserting LITE stays 0 and Full still honours 24.
- Undisclosed PDC writes, inventory/container scanning and blanket craft cancellation: ACCEPTED as documentation defects; `docs/LITE_TESTING.md` now states them plainly.
- Server-wide single `pending` query gate, inert `itemguard.search` without `history.others`, unbounded GUI page offset versus the repository clamp of 155, `package_lite.py` substring checks, and no binding of the Full JAR to the frozen source tree: acknowledged, NOT fixed in this slice. They are usability/robustness issues, not disclosure or destruction paths, and each needs its own RED case.

## Boundaries

`NOT_RELEASE_READY`. No server was started, no fixture staged, no client visual or Vietnamese acceptance performed, no public upload, no commit, push, deploy or production change. The review is static analysis; the disclosure fix is proven by unit tests, not by in-game reproduction. P0 native execution authority remains blocked on the missing `SeIncreaseQuotaPrivilege` and is untouched here.
