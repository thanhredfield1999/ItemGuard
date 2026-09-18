"""Update the CURRENT ledger section for artifact 6baac6ec... with the real numbers from this round."""
from __future__ import annotations

import pathlib

LEDGER = pathlib.Path("CURRENT_STATE.md")
START = "## CURRENT —"
END = "## PREVIOUS —"

CURRENT = """## CURRENT — Branch `premium-mysql` — 2026-09-19 — issuance reachable, Discord wired, dead promises deleted; six gates re-run on the final artifact

This branch keeps the Premium candidate rebuildable on top of `main` release commit `6292638`.
The frozen LITE candidate on `main` is not rebuilt or changed by Premium work.

Artifact for this tree:

    target/ItemGuard-1.0.0-shaded.jar
    SHA-256 6baac6ec7d391cf922490f533158b0cb6caa8cf51a272a9ee6a21e92c5d37469   (9,913,449 bytes)
    Paper 1.21.11-131, Java 21, relocated libraries and relocated SLF4J service provider

Hash lineage this round: `d1aac80a…` (previous session) -> `d513a3cc…` (void: permission and
anti-dupe-notice changes came after it) -> `d17b8f81…` (void: the absence-mode fix came after it) ->
`6baac6ec…` (current). Every change voided the earlier artifact's runtime evidence, and every gate was
re-run for the survivor.

Offline evidence for the current tree:

    mvnw.cmd -o test                          959/959, 0 failures/errors/skipped
    python scripts/check_no_hardcoded_vietnamese.py
                                              0 violations (201 files), self-test 25/25
    python -m unittest discover -s scripts    70/70 tooling contracts
    mvnw.cmd -o -DskipTests package           BUILD SUCCESS

Runtime evidence, all six gates PASS and bound to `6baac6ec…`:

    run/premium-paper-mysql-20260919-034937.json        single Paper, startup + restart
    run/premium-two-paper-mysql-20260919-035111.json    two isolated Paper servers, one MySQL schema
    run/premium-paper-migration-20260919-035300.json    SQLite -> MySQL /ig migrate: dry-run, confirm, non-empty refusal, persistence
    run/premium-paper-failure-20260919-035519.json      reliability: fail-closed, no retry, port closed, committed state preserved
    run/premium-paper-gameplay-20260919-035804.json     real-client gameplay journey (two protocol clients)
    run/premium-backup-restore-20260919-040323.json     dump/restore into a second schema; partial and future-version restores refused
    run/mysql-schema-gate-20260919-040850.json          43/43 across 11 tagged classes on this tree

`run/superseded/` holds every receipt from earlier artifacts (moved, not deleted). The two red
schema-gate receipts (`-024126`, `-024353`) stay in `run/` on purpose: they are the runs that found a
real defect.

What this round changed, in the order it matters:

1. **The reclaim hand-over exists** (arm -> deliver -> settle; one protocol shared by `/matdo sos` and
   `/finditem giveoldid`; `reclaim.issuance-enabled` ships `false`).
2. **The gate that made it unreachable is fixed, and this is the round's real finding.** Every external
   presence probe returned `UNAVAILABLE` whether or not the plugin was installed, so the capability
   gate denied every reclaim: correct code that could never fire. `reclaim.external-absence-mode` now
   separates "not installed" (skipped in `INSTALLED_ONLY`, recorded in the claim evidence as
   `NOT_APPLICABLE`) from "installed and unreadable" (still denies in both modes). `STRICT` remains the
   shipped default, unrecognised values resolve to it, and the old two-argument probe methods keep the
   refusing behaviour.
3. **Discord is wired** to confirmed findings on its own switch, with an accurate payload instead of
   the never-called `sendDuplicateAlert(itemName, code, holderName, location)`.
4. **Dead promises deleted, with a test so they cannot return**: `itemguard.restore` and
   `itemguard.teleport` are gone from `plugin.yml`; `PermissionDeclarationContractTest` fails if any
   declared node has no Java literal behind it. That test is what found `itemguard.bypass` as a third
   dead node.
5. **`anti-dupe.action` announces its downgrade** instead of silently ignoring a destructive choice.
6. **Earlier traps stay fixed**: alerts use `itemguard.notify` on both editions; the
   `performance.auto-cleanup.enabled` switch is the switch.
7. **Owner-facing docs**: `docs/release/PREMIUM_HANDOFF.md` (install, MySQL, migration, backup/rollback,
   permissions, secrets, evidence map), a rewritten `docs/release/LITE_VS_FULL.md` truth table, and
   `docs/design/2026-09-19-premium-reclaim-issuance.md` — which also spells out the runtime gate
   issuance still owes (held-item refusal, issuance after `/clear`, permanent lock, restart, the
   full-inventory retry).

Known flake, recorded because it cost a red run: `SqliteProcessLockCrossProcessTest` (child JVM + `READY`
line) failed once under load, then passed in isolation and on the next full run.

"""


def main() -> int:
    text = LEDGER.read_text(encoding="utf-8")
    start = text.index(START)
    end = text.index(END)
    LEDGER.write_text(text[:start] + CURRENT + text[end:], encoding="utf-8")
    print("ledger CURRENT section replaced")
    print("first heading line:", CURRENT.splitlines()[0])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
