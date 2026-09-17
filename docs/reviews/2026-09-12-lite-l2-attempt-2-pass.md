# LITE L2 attempt 2 — PASS_CONTROLLED_SMOKE (2026-09-12)

## Result

`PASS_CONTROLLED_SMOKE` for the exact candidate, adjudicated by the independent verifier against raw logs, artifact hashes and the fixture SQLite database. This is controlled runtime evidence only; it is not release, client-visual, gameplay-complete or production evidence.

## Exact target

- Fixture: `E:/AI.WORK/30_KET_QUA_THU_NGHIEM/itemguard-lite-isolated-15ba416c3eb8` — now **CONSUMED**, no replay.
- Candidate: `target/ItemGuard-LITE-1.0.0-test.jar` SHA-256 `b94170b2681ed44e700d107b2fdb17456bed7f5021bcc95e520006c178a67be5`; the fixture copy hashes identically after the run, so the tested bytes are the shipped bytes.
- Loopback port 65300; Paper 1.21.11 / Java 21; fresh worlds, fresh plugin data, one attempt with exactly two generations separated by one clean stop.
- Predecessor fixture `itemguard-lite-isolated-3fa2f34b59de` remains consumed with `HARNESS_FAIL`; it was not reused or replayed.

## Observed on real Paper

Generation 1, from `server-1.log` and `bots-1.log`:

- `LITE_CASE permissions PASS`, `LITE_CASE identity PASS` (`LITE_CODE BWTKJK`), `LITE_CASE history PASS`.
- `LITE_CASE gui-open PASS` and `LITE_CASE gui-click-readonly PASS`. Inspect-before-click receipt recorded the real rendered item: `slot0: diamond_sword, count: 1`. After the click the cursor stayed empty, so the preview is read-only in practice, not only by declaration.
- Duplicate handling: `ITEMGUARD_DUPLICATE_CONFIRMED code=BWTKJK uuid=1b1faa4b-5961-4008-9778-4e2b4ac0eb2e epoch=1789188978143 locations=2 action=NOTIFY`, followed by `LITE_CASE duplicate-not-removed PASS`. Staff received `[DUPE ALERT!] Item BWTKJK (Code: BWTKJK) has multiple copies!`. Both physical copies survived — nothing was deleted, confiscated or moved.
- Permission boundary: the member's `/ig search #BWTKJK` returned `[ItemGuard LITE] You do not have permission.`; staff's returned the timeline.

Generation 2, after exactly one clean stop and restart:

- `LITE_CASE restart-identity PASS` and `LITE_CASE restart-history PASS` — the same code and item UUID, with readable history, survived the restart.

Both generations: zero `ERROR`, zero exception, zero `LITE_PROBE_FAIL`, and `All dimensions are saved` present once each.

## Verifier finding and correction

The first adjudication **REJECTED** the run with `member drilldown disclosed the other actor name`. Investigation of the raw transcript showed the only occurrence of `LiteStaff` in the member's message stream was the vanilla broadcast `LiteStaff joined the game` — server output, not ItemGuard output. The assertion was too broad, not the plugin.

Corrected by TDD rather than by loosening the gate: `verify.plugin_lines` keeps only ItemGuard-prefixed lines and numbered timeline rows, and `verify.discloses_actor` flags a foreign actor only inside that plugin output, ignoring the redacted form. New `tools/lite-runtime/test_output_filter.py` (7 cases) proves vanilla join/leave/chat/death broadcasts are not disclosures, that a genuinely leaked timeline row still IS one, and that the redacted row is not. Full runtime tooling suite: **14/14 PASS**. Re-adjudicating the unchanged fixture evidence then returned `PASS_CONTROLLED_SMOKE`.

## Honest limitation on the privacy fix

The redaction branch was **not exercised at runtime** in this fixture. Read-only SQLite inspection of the fixture DB shows `tracked_items` holding one row, `BWTKJK`, still owned by `LiteStaff`, and `item_history` holding a single `SPAWN` row by `LiteStaff`. The periodic scan had not reassigned `owner_uuid` to the member before the query, so the member's owner-scoped drilldown correctly returned `No recorded history found; this is not proof the item does not exist.`

What this run therefore proves about privacy is the stronger-but-narrower fact that a member could not read another player's item history at all here. The specific redaction path — a member holding a transferred identity seeing `another player (staff only)` / `location hidden` — remains proven only by unit tests (`LiteHistoryPrivacyTest`), not by runtime. The verifier now has the assertion in place, so the next controlled run that does reassign ownership will adjudicate it automatically. SQLite `integrity_check` returned `ok`.

## Cleanup

All four owned children exited `0`, none forced, all log readers closed. Independent re-check found PIDs 16552, 13016, 38808 and 34288 absent, and a fresh connect to `127.0.0.1:65300` returned `10061` (refused).

## Remaining boundaries

`NOT_RELEASE_READY`. No real-client visual or Vietnamese presentation acceptance, no crafting/natural-break, no scale/concurrency/crash/AV/host-guardian proof, no other Paper version, no production, no public upload, no commit or push. The Full product candidate is unchanged by this smoke, and P0 native execution authority remains blocked on the missing `SeIncreaseQuotaPrivilege`.

## Artifact identity after a later rebuild

A subsequent full verification (`clean test package`, 418/418 PASS) plus `scripts/package_lite.py` produced LITE `3df091107435c966024c1b47cef53604529b8f47345f038592a4472387f819a7` from Full `449dca8fa2ffa960777c4355da335e39dd200d08d98101d33b54dcc4f0d01c06`. `scripts/compare_jar_entries.py` proves that rebuild is **byte-identical to the runtime-tested artifact at all 469 JAR entries**, with 0 missing, 0 extra and 0 differing; the whole-JAR hash differs only by ZIP metadata.

The artifact actually executed on Paper remains `b94170b2681ed44e700d107b2fdb17456bed7f5021bcc95e520006c178a67be5`, preserved inside the consumed fixture. Restoring `target/` to those exact bytes was attempted and **blocked at the tool-approval layer**; it was not retried or worked around. Therefore: do not present `3df09110…` as the hash that was executed, and re-verify whichever exact file is ultimately published. `target/lite-build-receipt.json` records this equivalence explicitly.
