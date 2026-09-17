# ItemGuard Roadmap — LITE first, Full gated

> **For Hermes:** Execute this roadmap one bounded task at a time; use TDD for code changes and preserve every consumed fixture/receipt.

**Goal:** Ship a truthful, non-destructive ItemGuard LITE candidate for Paper 1.21.11 / Java 21 only after fresh artifact verification and player-facing acceptance; progress Full separately without claiming runtime authority that has not been established.

**Architecture:** LITE remains a separate Paper entry point over the shared identity/history SQLite engine, with read-only commands and mandatory `NOTIFY` duplicate action. Full retains anti-dupe/recovery work, but execution authority is a separate Windows guardian track; it cannot be silently replaced by a normal process launcher or made a release dependency for LITE without an explicit product decision.

**Tech stack:** Java 21, Maven, Paper 1.21.11 API, SQLite, C# host-only authority tools, isolated Paper fixture, BotChecker where its protocol proves the claimed behavior.

---

## Evidence baseline — 2026-09-12

- `mvnw.cmd clean test package --no-transfer-progress`: 414 tests PASS using the verified JDK 21 toolchain. Current Full shaded JAR SHA-256: `a5c83fd18ee258f4cedc2ef551b223b0f71eca725b20f7e20b15579c0ba09f80`.
- `tools/execution-authority/build/offline-test.sh`: PASS. This is offline/host-only evidence; it is not a guardian, Paper, deploy, or production proof.
- Suspended worker exact loop: FAIL before process creation at `AdjustTokenPrivileges(SeIncreaseQuotaPrivilege)` with `ERROR_NOT_ALL_ASSIGNED`. The host account has no such right. Do not grant that privilege and do not substitute another process API merely to bypass the contract.
- The preceding `clean` build removed `target/ItemGuard-LITE-1.0.0-test.jar`. Historical LITE hashes/evidence do not describe a currently present artifact. Rebuild and re-verify before any LITE runtime/visual work.
- Historical controlled LITE smoke passed only for SHA `d86135e…769a0de8`, seeded inventory/history, permissions, read-only GUI click, duplicate `NOTIFY`, and one clean restart. That fixture is consumed. It does not cover real-client visual acceptance, natural acquisition, crafting, scale/concurrency, crash, or other server versions.

## Release definition

LITE may be proposed for a limited Paper 1.21.11 test only when all items below have fresh evidence tied to one exact JAR:

1. Java 21 package verification, no failed/skipped tests, and package receipt/hash.
2. Independent source/artifact review scoped to LITE command registration, disabled destructive paths, and package substitutions.
3. New, explicitly approved isolated fixture; never reuse the consumed fixture or its receipt.
4. Controlled server startup, permissions, LITE command surface, duplicate `NOTIFY`, and stop/restart persistence proof.
5. Real Minecraft client visual acceptance by Thanh for English default and Vietnamese mode, command errors, GUI, lore/title alignment, and staff/member boundaries.
6. Correct listing and test instructions: Paper only, 1.21.11 only, Java 21, no Full+LITE co-install, no anti-dupe guarantee, no destructive/reclaim claim.

Public SpigotMC upload remains a separate owner approval. Passing test/build/smoke does not authorize it.

---

## Phase L0 — Recreate an exact LITE candidate

**Objective:** Produce a fresh LITE JAR and receipt after the clean build removed the prior candidate.

**Files likely to change:**
- Generated only: `target/ItemGuard-LITE-1.0.0-test.jar`, `target/lite-build-receipt.json`
- Read-only inputs: `scripts/package_lite.py`, `docs/LITE_TESTING.md`, LITE descriptor/config resources

1. Confirm `target/ItemGuard-LITE-1.0.0-test.jar` is absent and record the current Git worktree without resetting user changes.
2. Pin the known Java 21 binary for the process; do not rely on the host default JDK 25.
3. Run `JAVA_HOME='C:/Program Files/Java/jdk-21' python scripts/package_lite.py`.
4. Verify package receipt: test count, no failure/error/skip, descriptor/config substitutions only, full JAR-entry comparison, and SHA-256.
5. Re-read generated `plugin.yml` and config from the LITE JAR. Prove only LITE commands are registered; prove Full and LITE cannot coexist by namespace/documentation, not an invented runtime mechanism.
6. If the new hash differs from historical smoke hash, explicitly invalidate its runtime relevance. Do not reuse its fixture.

**Acceptance:** a current LITE JAR, hash, receipt, and source/package evidence exist; no server is started in this phase.

## Phase L1 — Fresh independent LITE review

**Objective:** Ensure LITE exposure remains non-destructive before a new runtime request.

**Files likely to inspect:**
- `src/main/java/com/itemguard/lite/`
- `src/main/resources/lite/`
- `scripts/package_lite.py`
- `docs/LITE_TESTING.md`
- `src/main/java/com/itemguard/commands/`
- `src/main/java/com/itemguard/reclaim/`

1. Freeze an input manifest for the fresh JAR/source paths and scope the review to LITE only.
2. Ask a fresh independent reviewer to check: registered commands, permission defaults, `NOTIFY` enforcement, no reclaim/lost-item/external storage/mutation command exposure, WorldGuard/Discord disabled, descriptor/config correctness, and claims in testing docs.
3. Reproduce every finding using source/tests; fix only reproduced defects with RED→GREEN tests.
4. Rebuild LITE from Phase L0 after every accepted change and regenerate the receipt/hash.

**Acceptance:** review disposition tied to the exact source/JAR; no runtime conclusion.

## Phase L2 — New isolated LITE controlled runtime

**Objective:** Validate the exact reviewed LITE JAR in a new disposable Paper fixture.

**Prerequisite:** explicit approval for one new fixture and its bounded scope. Do not treat this roadmap as that approval.

**Fixture constraints:**
- New path under `E:/AI.WORK/30_KET_QUA_THU_NGHIEM/`; new plugin data and SQLite database.
- Paper 1.21.11 / Java 21; no production imports, no Full JAR, no `/reload`, PlugMan, hot-loader, forced kill, retry after failed run, or consumed receipt reuse.
- Use a free verified port and graceful-stop every owned process. Read back process/port state afterward.

**Test journey:**
1. Start exact LITE JAR and prove descriptor/version/hash in startup logs.
2. Verify member vs staff permissions for `/ig check`, own history, exact-ID staff history/search, GUI and stats.
3. Seed only disposable identities; prove GUI clicks are read-only and duplicate handling emits `NOTIFY` without deletion, seizure, issuance, or item mutation.
4. Perform exactly one clean stop/restart; prove SQLite `integrity_check=ok`, unchanged history/identity, and no owned processes or port remain after final stop.
5. Run an independent raw-log/hash/SQLite verifier, not only a runner exit-code check.

**Acceptance:** one sealed, non-replayed fixture with raw logs, hash binding, SQLite read-back, cleanup proof, and an honest scope statement.

## Phase L3 — Client visual and Vietnamese acceptance

**Objective:** Obtain player-facing acceptance that automation cannot prove.

**Prerequisite:** L2 passes for the exact JAR and Thanh joins the isolated server.

**Screens to inspect manually:**
- English and Vietnamese command help/errors.
- Empty/history/stat output.
- Member vs staff denial messages.
- Main GUI, one recorded identity, event timeline, back navigation, close/reopen, and disabled/unavailable state.
- Duplicate warning wording and item/step specificity.

**Acceptance:** Thanh explicitly accepts the visual/client experience, or provides feedback. Screenshot/client acceptance is required; unit output is not a substitute. Any feedback creates a new build → review → fixture cycle, never edits the consumed fixture.

## Phase L4 — Test distribution package and listing draft

**Objective:** Prepare, but do not publish, an honest test package.

**Files likely to change:**
- `docs/LITE_TESTING.md`
- New draft under `docs/release/` for SpigotMC copy and changelog
- Optional LITE config comments/messages only when accepted visual feedback requires it

1. State supported target exactly: Paper 1.21.11, Java 21; do not claim Spigot/Folia or a version range from compile-only evidence.
2. State LITE scope: read-only history/inspection/SQLite/NOTIFY. State exclusions: destructive action, reclaim, external storage, gameplay-complete anti-dupe, Full+LITE co-install.
3. Include install, permission, backup, upgrade and rollback-safe test guidance; prohibit `/reload` and production DB reuse.
4. Attach fresh artifact hash and verification limits.
5. Request owner approval before upload; do not publish, commit, push, deploy, or restart production in this roadmap.

**Acceptance:** a reviewable draft and exact test JAR. Public upload remains pending owner approval.

---

## Full track — separate from LITE release

### Phase F0 — Keep P0 authority fail-closed

**Objective:** Preserve current Windows authority boundary without permission escalation.

- Keep the `SeIncreaseQuotaPrivilege` launch failure as a real blocker in `CURRENT_STATE.md`.
- Do not add privilege grants, UAC elevation instructions, service installation, scheduled-task workaround, `CreateProcessWithTokenW`, or ordinary subprocess fallback merely to turn the test green.
- Continue only offline pure work whose value does not depend on launching a worker: recovery summary parser/verifier contracts, sealed protocol framing, durable journal replay classification, and DACL/identity contract design, each as a separate TDD slice.
- When a slice needs real child launch, stop and request an explicit architecture/product decision: either a separately provisioned guardian account/environment with documented least privilege, or defer P0. This decision must precede code that changes the security model.

### Phase F1 — Natural-break and crafting truthfulness

**Objective:** Close or deliberately exclude shared-engine gameplay claims using new controlled evidence.

- Natural break and crafting are not LITE release prerequisites unless the listing claims them. They are Full/shared-engine risk gates.
- Before a new Paper attempt, design a fresh sealed protocol with exact JAR, fixture, client-load proof, ItemListener/request/publication evidence, stopped DB/NBT evidence, and cleanup/restore verification.
- Never reuse attempts 12/14 or their bundle/receipt. Do not infer a product defect from prior inconclusive packet seams.
- Use a real client if the bot cannot prove the Paper client-loaded lifecycle; do not call a bot-only result visual/gameplay acceptance.

### Phase F2 — Catalog and scale boundaries

**Objective:** Avoid advertising full catalog/performance behavior beyond evidence.

- Keep existing exact-ID history index and paging behavior bounded.
- Do not lift read budgets or promise arbitrary text-search SLOs because synthetic `contains` queries still hit `SQLITE_INTERRUPT`.
- If product needs general text search, first write a data-volume/SLO design and migration/rollback/space plan; then TDD index semantics, collision/normalization, cancellation and recovery before a live migration discussion.

### Phase F3 — Custody/external integration

**Objective:** Maintain fail-closed absence and reclaim behavior.

- Keep PlayerVaults/zAuctionHouse/HavenBags external absence `UNAVAILABLE` until an upstream version exposes a bounded, side-effect-free, read-consistent identity query.
- Do not create an adapter that full-scans, opens inventory, triggers file creation, or infers absence from an exception/cache.
- Any issuance/reclaim design must be a separately approved transaction project after all required presence sources can prove absence; no LITE dependency.

---

## Execution order

1. L0 fresh LITE package and receipt.
2. L1 independent LITE review and source/test corrections if required.
3. Request explicit authorization for L2 only after L1 is tied to one JAR.
4. L2 new controlled runtime, then L3 client visual acceptance.
5. L4 listing/package draft; request approval before public upload.
6. Progress F0 offline-only in parallel only when it cannot alter LITE source or falsely extend LITE claims. F1–F3 follow product priority after LITE test readiness.

## Verification commands

- Full Java baseline: `JAVA_HOME='C:/Program Files/Java/jdk-21' ./mvnw.cmd clean test package --no-transfer-progress`
- LITE package: `JAVA_HOME='C:/Program Files/Java/jdk-21' python scripts/package_lite.py`
- Offline authority suite: `bash tools/execution-authority/build/offline-test.sh`
- Privilege-dependent worker contract, only as a diagnostic and not a retry loop: `bash tools/execution-authority/build/native-suspended-worker-test.sh`
- Artifact hash: `sha256sum target/ItemGuard-LITE-1.0.0-test.jar`

## Risks and decisions still open

- A real Windows guardian launch cannot proceed under the current account because the required privilege is absent. This is an architectural/provisioning decision, not a code defect to bypass.
- A previous clean build removed the historical LITE JAR; every next runtime/visual claim must bind to a freshly rebuilt candidate.
- LITE currently targets Paper, not vanilla Spigot. SpigotMC is a distribution website, not a server compatibility claim.
- LITE has no public-release approval. It must never be marketed as full anti-dupe protection or as supporting all gameplay paths.
- No production action, commit, push, deployment, restart, or public upload is included in this roadmap.
