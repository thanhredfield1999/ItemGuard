# SUPERSEDED — see ROADMAP.md

This document was written before the container sweep and the version matrix existed, and it
still carried a restore rule Thanh reversed on 2026-09-15 (one restore per identity, forever).
Acting on it would re-introduce a decision that was deliberately changed.

Current roadmap: `docs/release/ROADMAP.md`

---

## Historical content below

# ItemGuard FULL — Product Roadmap

**Status:** planning only. Nothing here is approved for implementation, and nothing here is
evidence of behaviour. LITE 1.0.0 ships first; work below starts only after LITE is published
and has real users.

**Boundary that defines this document:** LITE observes and warns. It never deletes, moves,
grants or hands items back. Everything in FULL is a *write* the owner deliberately pays for.
That line is what people are buying, so it must stay visible in both listings.

Current LITE candidate at the time of writing:
`a952d16146082ffacfbcbf6032fdba0634c86e8099cbf6802685c2f76db71727` (733 Java tests; Paper
1.21.11 fixtures `itemguard-lite-isolated-57c97c1ee2be` loss and
`itemguard-lite-isolated-dd70b72bb201` full).

---

## F1 — `/ig untrack`: release an item's identity (shop / VIP / gacha templates)

**The problem, stated from a real session (2026-09-14).** Thanh asked what happens when an
admin sets up a shop, VIP kit or gacha prize. If the template item is made by copying an item
that ItemGuard already tracks, every copy handed out carries the *same* identity. The scanner
then sees one identity in many places and reports duplicates — for every buyer. Nothing is
wrong with the plugin; the template was contaminated before it was ever sold.

**What the source says (read, not assumed).** Identity is written on a player *click*:
`ItemListener.onInventoryClick` → `ItemTrackingService.requestPlayerSlotTag` /
`requestBlockContainerSlotTag`. The periodic scan **observes** and does not mint identities.
So a cleaned template sitting in a shop chest is not re-tagged by scanning alone; it is
re-tagged if someone clicks it.

**Unverified.** That reading has NOT been confirmed on a live server. Before F1 is designed in
detail, run one bounded manual session: place a tracked item in a chest, let a full scan pass,
confirm the ID did not change; then click it and confirm what happens. Until that receipt
exists, treat this paragraph as a hypothesis.

**Proposed command**

```
/ig untrack     - strip the identity from the item in hand
```

- Admin permission only, its own node — never reachable from a wildcard grant by accident.
- Removes the PDC identity and the ID lore line. Does not delete the item.
- Writes a history row naming the staff member, the code released and the time. Releasing an
  identity is a staff action on record, not a silent erase.
- The released code is retired, not recycled: a future item must never reuse it, or old
  history would appear to describe a new object.

**Open questions for design time**
- What happens to the history already recorded under that code? Keep it and mark the identity
  retired, most likely — deleting it would destroy evidence an admin may still need.
- Should the command refuse an item that is currently flagged as duplicated? Probably yes:
  untracking is not a way to make an investigation disappear.
- Bulk form for kit setup (a whole inventory, or a container) — convenient, but every bulk
  write needs its own confirmation step and its own audit row.

**Documentation duty regardless of implementation.** The shop/gacha trap above must be
described in the LITE listing and README as a setup warning, because it affects LITE users
today even though the fix ships in FULL. Recommended wording: build shop templates from a
fresh `/give`, never from an item taken out of a player's inventory.

---

## F2 — Restore / reclaim

Give a staff member a way to hand back an item the history proves was lost. This is the
single most requested capability and the hardest to get right, because a restore that fires
twice is a duplication bug shipped by the anti-duplication plugin.

Non-negotiable preconditions (already worked out in earlier design sessions):
- The item must be confirmed destroyed by a completed scan, not merely absent.
- One restore per identity, permanently — even if the restored copy is lost again.
- A newer scan that saw the item alive cancels the restore.
- Permission checked before anything else, so a player without rights cannot probe item state
  through error messages.

Depends on F1's audit-row work: both are staff writes and should share one ledger format.

---

## F3 — Teleport to the recorded location

Take staff to where an item was last seen. Small feature, real safety surface: it loads
chunks on demand and moves a player. Needs its own permission and its own rate limit.

---

## F4 — Compatibility beyond Paper 1.21.11

LITE claims exactly one platform because that is the only one with runtime evidence. FULL
should widen deliberately: pick target versions, run the fixture suite on each, and publish
the matrix. Compiling is not support.

---

## F5 — Vietnamese

Message files exist and `general.language: vi` works, but the crafting refusal is fixed
English wording and no Vietnamese visual acceptance has been done. Finish the translation and
have Thanh accept it on a real client before advertising it.

---

## Ordering

1. Publish LITE. Collect real reports for a while.
2. F1 — smallest, unblocks shop/gacha servers, and the setup warning is owed to LITE users now.
3. F2 — the reason people pay.
4. F3, F4, F5 as demand shows.

No item on this list may be implemented by weakening a LITE guarantee that is already
advertised.
