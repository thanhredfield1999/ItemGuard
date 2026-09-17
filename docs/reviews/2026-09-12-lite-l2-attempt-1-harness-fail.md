# LITE L2 attempt 1 — HARNESS_FAIL, fixture consumed (2026-09-12)

## Outcome

`HARNESS_FAIL / PRODUCT_VERDICT_NOT_ISSUED`. The attempt stopped before the duplicate, privacy and restart cases. No product defect was demonstrated and none is claimed.

## Exact target

- Fixture: `E:/AI.WORK/30_KET_QUA_THU_NGHIEM/itemguard-lite-isolated-3fa2f34b59de` — **CONSUMED**, `attempt.json` and `outcome.json` written. No replay.
- Candidate: `target/ItemGuard-LITE-1.0.0-test.jar` SHA-256 `b94170b2681ed44e700d107b2fdb17456bed7f5021bcc95e520006c178a67be5`
- Loopback port 64704; Paper/Java from the existing immutable cache; fresh worlds and plugin data.
- Pre-run gates PASS: contract tests 7/7, staged 122 files with recorded hashes, candidate hash match, port free.

## What the run did prove (runtime, this fixture)

Server reached `Done (`, LITE enabled as the LITE edition, and the probe booted. Observed on real Paper:

- `LITE_CASE permissions PASS`, `LITE_CASE identity PASS` (`LITE_CODE RK6KHJ`), `LITE_CASE history PASS`.
- `/ig check` by staff returned `Item ID: #RK6KHJ`.
- `/ig search #RK6KHJ` by the member was denied: `You do not have permission.`
- `/ig search #RK6KHJ` by staff returned the timeline with actor and location, plus the "oldest line" caveat.
- `/ig gui` opened the window titled `LITE - Recorded item history`.

No `ERROR`, exception, or `LITE_PROBE_FAIL` appeared in the server log.

## Failure cause — two harness defects, both in test tooling

1. `tools/lite-runtime/bots.cjs` inspect-before-click required `window.slots[0].name === 'paper'`. The LITE overview deliberately renders the real tracked material per slot (a diamond sword here), so the guard rejected a correct window and raised `Expected history preview`. Fixed: assert a `LITE -` preview window and a non-empty slot 0, emit an `inspected` receipt with the observed material, and never hardcode a material.
2. `tools/lite-runtime/LiteProbe.java` compared the holder's concrete class name to `com.itemguard.lite.LiteCommand$Preview`. `Preview` is an abstract base; the real holder is `OverviewPreview`/`TimelinePreview`, so `gui-open` could never pass. Fixed by walking the superclass chain. This defect would have failed the next step even if defect 1 had not fired.

Both are harness bugs. Neither indicates a LITE product defect.

## Cleanup

Both owned children exited `0`, not forced, log readers closed. `port_released: true`; a fresh connect to `127.0.0.1:64704` returned `10061` (refused). No leftover owned Java process for this fixture.

## Tooling corrections and re-gates

- Controller candidate pin updated from the superseded `d86135e7…769a0de8` to `b94170b2…78a67be5`.
- New privacy step: after the duplicate, the member runs `/ig history #CODE` and the real output is captured.
- `tools/lite-runtime/verify.py` now adjudicates that output: the member's lines must not contain the other actor's name, must withhold foreign actor detail, and staff lines must retain it.
- `tools/lite-runtime/test_contracts.py` gained `test_member_disclosure_of_other_actor_rejects`, which proves the verifier REJECTS a leaked transcript instead of passing it. Contract tests 7/7 PASS.
- Post-fix gates: `javac` compile of the probe PASS, `node --check bots.cjs` PASS, contract tests 7/7 PASS.

## Boundary

A second controlled fixture requires fresh explicit approval; the consumed fixture must not be reused or replayed. Still `NOT_RELEASE_READY`: no client visual or Vietnamese acceptance, no crafting/natural-break, scale, concurrency, crash or guardian claims, no other Paper version, no public upload, commit, push, deploy or production change.
