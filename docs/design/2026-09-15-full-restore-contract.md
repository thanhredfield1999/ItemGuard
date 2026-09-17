# ItemGuard FULL — Restore and Recovery: design contract

**Status:** design for approval. No code has been written against this document. Nothing here
is evidence of behaviour.

**Why this document exists.** Handing an item back is the easiest way to make an
anti-duplication plugin duplicate items. Every rule below exists because some specific way of
getting it wrong would hand a server a free item printer. The rules are written so that the
failure mode is *an admin has to try again*, never *the server now has two swords*.

Thanh's requirement, in his words: critical losses and scam cases must be recoverable for the
player, admins stay flexible, and the feature must be clear and detailed.

---

## 1. The distinction everything else depends on

Two situations look identical to a frustrated player and are completely different to the
plugin:

| | The item is… | Correct remedy | Why |
|---|---|---|---|
| **Loss** | gone — burned, void, cleared, despawned | **Restore**: create it again | Nothing exists to move |
| **Scam / theft** | alive, in someone else's hands | **Transfer**: take it from the holder, give it to the victim | Creating a second one *is* duplication |

Using restore on a scam is exactly how a server ends up with two of a unique item. So FULL
ships **two separate commands** with separate permissions, and each refuses the other's case:

- `/ig restore` refuses unless the identity is confirmed destroyed.
- `/ig transfer` refuses unless the identity is confirmed **alive and located**.

An admin cannot accidentally pick the wrong one — the command they picked will tell them which
one this case actually is.

### Two commands are also two different audit trails

This is the second reason not to merge them, and it matters after something goes wrong.

A restore row means **an item was created**. A transfer row means **an item changed hands and
the world count did not move**. If the two shared one command and one event type, the history
could no longer answer the question an investigation actually asks: *did this item come back,
or did somebody make another one?*

Keeping them apart means that if ItemGuard itself is ever suspected of being the source of a
duplicate, the trail can be read in one pass: every creation is a `RESTORE` row naming an
admin and a reason, and anything else is a move. A duplicate with no `RESTORE` row behind it
did not come from this plugin — and that is provable rather than asserted.

---

## 2. Restore — for items that no longer exist

### 2.1 Preconditions (already implemented and tested in `RestoreGate`)

Checked in this fixed order; the first failure refuses and nothing is written:

1. **Permission first.** Someone without permission learns nothing about the item's state from
   the refusal, so the command cannot be used as a probe.
2. Identity is known.
3. State is `DESTROYED`.
4. Loss was confirmed by a **completed** inventory sweep. An unloaded chunk hides intact items;
   absence from an incomplete sweep is not proof of destruction.
5. The item was **not** seen in the latest completed sweep.
6. The confirmation is **not stale** — if the world has been swept again since, the admin must
   reconfirm against the newer sweep.

`RestoreGate` implements these with ten tests.

### 2.2 Repeat restores are allowed — and why the old rule was wrong

An earlier draft refused any identity that had been restored before, ever. That rule is being
**removed**, because it blocks the wrong thing.

Restore rebuilds the item under its **original identity code**. Destroyed once, restored: one
item exists. Destroyed again, restored again: still one item. The number of copies in the
world never grows, no matter how many times the cycle repeats. A player whose sword genuinely
falls in lava twice is unlucky, not an exploiter, and refusing them punishes bad luck while
stopping no duplication at all.

**What actually creates a duplicate is a false destruction proof** — an item that is hidden
rather than destroyed, judged lost, and then restored while the original still exists. The
defence against that is preconditions 3-6, not a counter.

So: genuine destruction may be restored as often as it genuinely happens. Admins stay
flexible, which is the point of the feature.

### 2.3 The repeat count is a signal, not a gate

Every restore of an identity is still counted and shown. The command reports it before acting:

```
#YTUUS3 has been restored 2 times before (last: 2026-09-14 by Thanh, "fell in lava").
```

The admin sees the pattern and decides. Three restores of the same sword for the same player
in a week is worth a question; the plugin raises it rather than answering it, because only a
human knows whether that server's nether is genuinely that dangerous.

The count also feeds the staff ledger in §4, where repeated restores to one player are exactly
the shape that stands out.

### 2.4 The honest limit

This design's protection is only as strong as the destruction proof behind it. The known hole:
an item inside a chunk that never loads during a sweep is invisible to the scan, and invisible
is not the same as destroyed. The preconditions reduce that risk — a completed sweep, not seen
in the newest sweep, confirmation not stale — but they do not eliminate it.

An admin restoring an item that a player has stashed in an unloaded area **can** create a
second copy. No amount of counting restores prevents this; only better presence evidence does.
Say so in the documentation rather than implying the command is safe by construction.

### 2.5 Issuance — the part that does not exist yet

This is the dangerous half. The order of operations is the whole design:

```
1. Evaluate the gate.                      (no writes)
2. Claim the identity in the database:     (durable write, atomic)
     mark restored_at, restored_by, reason
     - the claim is conditional: it only succeeds if restored_at is still NULL
     - two admins running the command at the same instant: exactly one claim wins
3. ONLY THEN build the item and give it.
4. Write the history row.
```

**The claim is written before the item is created, deliberately.** If the server dies between
step 2 and step 3 the player does not get their item and an admin must look into it. If we did
it the other way round, a crash between giving and recording would leave an identity that can
be restored *again* — and now there are two.

Losing an item is recoverable. Duplicating one is not. The order encodes that.

### 2.6 Delivery

- **Target must be online.** No offline queue in 1.0. A queue is a second durable state machine
  and every one of its failure modes is a duplication risk; it is not worth it for a command an
  admin runs by hand.
- **Inventory must have room. If it is full, refuse and change nothing** — do not drop the item
  on the ground. Dropping is how the item gets lost a second time, in public, next to other
  players.
- Refusal at this stage happens **before** the claim, so the admin can retry after the player
  makes space.

### 2.7 What the item is

Rebuilt from the stored snapshot: type, name, lore, enchantments, durability, and the
**original identity code**. It is the same item returning, not a new one — the timeline
continues rather than starting over, and the restore appears in it as a staff action.

If no snapshot exists, refuse. Guessing at an item's contents means an admin's memory decides
what a player receives.

---

## 3. Transfer — for scam and theft

### 3.1 Preconditions

1. Permission.
2. Identity is known.
3. The item is **confirmed alive** in the latest completed sweep, with a known holder. If it
   cannot be located, refuse — and say so plainly. "I could not find it" is a useful answer;
   inventing a replacement is not.
4. Both the current holder and the recipient are **online**. Taking an item from an offline
   player means writing into their saved data; out of scope for 1.0.
5. Recipient has room.

### 3.2 Execution

```
1. Verify the exact item is still in the named holder's inventory  (same tick)
2. Remove it from the holder
3. Give it to the recipient
4. Record: taken from X, given to Y, by admin Z, reason
```

Steps 2 and 3 run in the same tick with no intervening scheduling. The count of that item in
the world never changes — this is a move, not an issue, so the ordering worry from restore
does not apply here. If step 2 fails, nothing happens.

**Confiscation to nobody is not supported in 1.0.** Deleting a player's item is a bigger power
than moving one and deserves its own design.

---

## 4. The audit trail

Every restore and transfer writes a durable row: who ran it, which identity, which players,
the reason text the admin typed, and the timestamp. Reason is **mandatory** — an admin who
cannot say why in one sentence should not be issuing items.

`/ig history #ID` shows these as ordinary events, so a second admin reviewing the item later
sees the intervention rather than an unexplained appearance.

### 4.1 The staff ledger: watching the watchers

An anti-duplication plugin that hands a trusted group an item-creation command has moved the
duplication risk rather than removed it. The owner must be able to answer *"is one of my staff
abusing this?"* without reading the whole history by hand.

So every staff action against an item is recorded **twice over**: once on the item's timeline,
and once in a staff ledger keyed by the acting admin. The second view is the one that exposes
a pattern:

```
/ig audit <admin> [days]    - everything this admin did: restores, transfers, reasons
/ig audit                   - totals per admin over the window, highest first
```

What the ledger records for each action:

- acting admin (name and UUID — names change, UUIDs do not)
- action: `RESTORE` or `TRANSFER`
- identity code, and for a transfer both the player taken from and the player given to
- the reason text, verbatim
- timestamp and server epoch

**Recipient is recorded separately from actor on purpose.** The clearest sign of abuse is not
volume, it is direction: an admin who restores items to their own account, or repeatedly to
the same one or two players, stands out immediately in a per-admin view and is nearly
invisible scattered across per-item timelines.

### 4.2 The ledger is append-only

No command edits or deletes a ledger row. LITE's automatic history cleanup is disabled and
FULL must not prune this table either — an audit trail an admin can clear is not an audit
trail. If an admin has database access they can of course edit the file directly, and no
plugin can prevent that; this defends against abuse through the plugin's own commands, which
is the realistic case.

### 4.3 What this proves when something goes wrong

If a duplicate is ever found on a server running FULL, the ledger answers, in one query,
whether ItemGuard created it:

- there is a `RESTORE` row for that identity → an admin created it, and the row names them,
  when, and their stated reason
- there is no such row → the second copy did not come from ItemGuard's restore path

That is a provable statement, not a reassurance — which is exactly what the owner needs when
the accusation is aimed at the plugin itself.

---

## 5. What this design refuses to do

- No automatic restore. No rule-based refunds. A human decides, every time.
- No cap on how many times one identity may be restored. Genuine destruction is restorable as
  often as it genuinely happens; the guard is the destruction proof, not a counter.
- No player-facing "give me my item back" command. `/matdo sos` may **request** review; it
  never issues anything.
- No restore of an item that still exists. That is what transfer is for.
- No offline delivery, no drop-on-floor fallback, no confiscation-to-void — in 1.0.

---

## 6. Open questions for Thanh

1. **Command names.** `/ig restore <code> <player> <reason>` and
   `/ig transfer <code> <player> <reason>` — English, consistent with the rest of FULL. Agreed?
2. **Permissions.** Separate nodes (`itemguard.restore`, `itemguard.transfer`), both `op` by
   default, neither reachable from a wildcard grant by accident. Agreed?
3. **Confirmation step.** Should a destructive command require typing the code twice, or is
   one command enough given that everything is logged and reversible by a second transfer?
4. ~~The already-restored rule.~~ **Decided 2026-09-15: repeat restores are allowed.** Restore
   reuses the original identity, so repeating it never increases the number of copies in the
   world; the real duplication risk is a false destruction proof, which §2.2-2.4 address
   directly. The repeat count is surfaced to the admin and recorded in the ledger as a signal,
   not enforced as a limit.
