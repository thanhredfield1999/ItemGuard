# Power-cut durability — ItemGuard LITE `c5cdfb6f`

Date: 2026-09-16. Harness: `tools/lite-runtime/power_cut.py`.

Asked by Thanh: *"hệ thống này có chắc là lưu id tốt không?"* The existing fixture only ever
restarted the server **cleanly**, which proves an orderly shutdown works and says nothing about
losing power.

## What was run

```
boot -> create tracked identity -> start 200 history writes
     -> taskkill /F the JVM mid-write      (no chance to flush anything)
     -> boot again on the SAME world and SAME database
     -> look for the identity
```

`taskkill /F`, not `stop`: anything Paper can catch would let it save, which is the behaviour
under test.

## Result: the database survived intact, the item did not

Identity `5ZVL3K`, killed PID 52132 / 36520 across two runs.

**ItemGuard's own storage came through the kill perfectly.** Reading the SQLite file directly
after the unclean kill:

| Table | Rows | Contains `5ZVL3K` |
|---|---|---|
| `tracked_items` | 1 | yes |
| `item_history` | 1 | yes |
| `item_snapshots` | 1 | yes |
| `tag_publications` | 1 | yes |

No corruption, no leftover journal (`journal_left: []`), file intact at 122,880 bytes. The
plugin's record of the item is complete and readable after a kill that landed mid-write.

**The item itself was not in the player's inventory on the second boot** — `LITE_READBACK NONE`.

The fixture originally gated on the item coming back, which was wrong: that would require
ItemGuard to undo a vanilla world-save loss. The gate is now its own records, re-verified on
candidate `c5cdfb6f` → `PASS_POWER_CUT` with all four tables holding the identity.

## Why, and why it is not an ItemGuard defect

A player's inventory lives in `playerdata/<uuid>.dat`, written by **vanilla Minecraft** on
logout or autosave, not by ItemGuard. Killing the JVM before an autosave rolls the player back
to their last saved state — an item handed out seconds earlier was never on disk. Every item
in that inventory is lost the same way, tracked or not; the same kill deletes a diamond block
with no plugin installed at all.

The distinction that matters for the product claim:

- **Vanilla loses the item.** Not something a plugin can prevent; the inventory write is not
  ItemGuard's to make.
- **ItemGuard did not lose the identity.** Every row survived. If that item is restored from a
  backup, or if a copy of it exists elsewhere, its ID and history are still there to compare
  against.

That is the honest scope: ItemGuard's durability guarantee covers *its own records*, and those
records held under an unclean kill. It never covered the server's world save, and the listing
does not claim it does.

## What this does not prove

- Only a Windows `taskkill /F` was tested. Real power loss can also corrupt at the filesystem
  layer, below anything SQLite controls.
- One kill, at one moment in the write. A different instant could land differently.
- `PRAGMA synchronous` is never set explicitly — this run passed on SQLite's defaults. Worth
  pinning rather than inheriting, since a future driver default change would be silent.

## Harness defects found while building this

Both produced confident-looking passes:

1. **An assertion that could not fail.** The probe prints `NONE expected=<CODE>` on failure,
   and the check was `code in readback` — which matches the echoed `expected=` value in the
   failing case too. The first run reported `PASS_POWER_CUT` with `readback: NONE`. Fixed to
   compare the first field only.
2. **Looking only at the main hand.** After a crash the player rejoins and may not be holding
   the item, so a missing ID in the hand would have been reported as a lost identity. The probe
   now scans the whole inventory and reports the slot.

Separately, this work uncovered that `test_contracts.py` had been **72/100 failing** since the
`itemDamageEvidence` fix: `verify.py` was changed to read `evidenceWhileHurt=` while the tests
still generated `healthAfter=`. The tool that guards the tools had been broken and unnoticed.
Fixed; 100/100 now.
