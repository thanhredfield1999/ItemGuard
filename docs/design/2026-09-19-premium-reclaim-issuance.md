# Reclaim issuance — the hand-over, and what each failure leaves behind

Premium only. `reclaim.issuance-enabled` gates it and ships `false`.

## What was missing before this change

The claim machinery already existed: `reclaim_claims` with
`idx_reclaim_identity_lock` covering `PENDING`, `PREPARED` and `COMMITTED`, presence probes for
player inventory, ender chest, PlayerVaults and zAuctionHouse, a full ItemStack snapshot per
identity, and a preparation/preflight pipeline that reserved a claim and proved eligibility. What did
not exist was the last step: an eligible claim ended in a `DENIED` row whose detail said
`ISSUANCE_GATE_CLOSED`. The plugin could prove an item was recoverable and then refuse to hand it
over, permanently.

## The protocol

    arm      PENDING  -> PREPARED     (off the server thread)   identity is now locked
    deliver  inventory write          (server thread)           one stack, empty slot checked first
    settle   PREPARED -> COMMITTED    (off the server thread)   delivered
             PREPARED -> DENIED                                 not delivered, retryable

The order is the guarantee, and each choice answers a specific failure:

| Failure | What happens | Why that is the safe side |
|---|---|---|
| The delivery lands, the commit write fails | Player has the item; the claim stays `PREPARED`, which is inside the unique index, so every later claim on that identity is refused. The player is told the item is theirs but unrecorded, and told to contact staff with the claim id. | A delivered item whose record allowed another claim would be a duplication path. A loud, unrepeatable state needs a human, not a retry. |
| The inventory is full | `settle(delivered=false)` → `DENIED`, which is outside the index, so the player may run the command again after making room. | Losing the retry is annoying; losing the item is not acceptable. |
| The snapshot cannot be restored | Refused before the write, `DENIED` with the validation message. | Rebuilding the item from material and name is explicitly out of contract. |
| The player went offline between arming and delivery | `DENIED`, retryable. | Nothing was handed to anyone. |
| Two commands race | `transitionReclaimClaim` is a conditional update (`WHERE state = ? AND updated_at <= ?`), so the loser sees `REFUSED_STALE` instead of assuming success. | "The write did not apply" and "the write was not attempted" are different facts. |
| The operator turns the gate off mid-flight | Arming is refused; settling still records a delivery that already happened. | The flag gates issuing, not the record of a hand-over. |

## Entry points

- `/matdo sos <id>` (player, own item): prepare → capability gate → same protocol.
- `/finditem giveoldid <id>` (admin, `itemguard.giveoldid`): proves absence through the same gate
  first, then reserves and runs the protocol against the recorded owner. Issuing to a different
  player is **not** implemented (that is `addbackitem`).
- With the gate off, both paths end in `DENIED` with `ISSUANCE_DISABLED` and say which key it is —
  nothing is issued, and the refusal names the reason.

## The gate that made all of this unreachable, and what was done about it

Found by reading the probes after the hand-over was written, not by running it: **every external
probe returned `UNAVAILABLE`, always** — `PlayerVaultsX`/`PlayerVaults` and `zAuctionHouse` alike,
installed or not, with the reasoning written out in `ExternalPresenceProbeFactory`. The capability
gate denies on `UNAVAILABLE`, so on any server — including a fixture with none of those plugins
installed — `/matdo sos` could only ever end in `DENIED_UNAVAILABLE`. The feature was correct and
unreachable.

That was a deliberate fail-closed choice (`ExternalPresenceProbeFactoryTest` pinned it), and the
requirement behind it is real: *"Adapter unavailable/timeout phải deny, không coi là absent."* The
distinction it was missing is between a plugin that **is not there** and a plugin that **is there and
cannot be read**:

- **Not installed** — it holds no items, and there is nothing to prove. Under the old behaviour it
  still blocked every reclaim.
- **Installed but unreadable** within a hard bound (every audited API here) — the identity might be
  inside, so it denies. Unchanged in both modes.

`reclaim.external-absence-mode` now chooses how the first case is treated:

| Mode | Not installed | Installed but unreadable |
|---|---|---|
| `STRICT` (default, shipped) | `UNAVAILABLE` → deny | `UNAVAILABLE` → deny |
| `INSTALLED_ONLY` | `NOT_APPLICABLE` → skipped, recorded in the evidence | `UNAVAILABLE` → deny |

`NOT_APPLICABLE` is a new `PresenceStatus` that the gate never treats as blocking, and it is written
into the claim detail for every skipped source, so "we checked two of four sources and skipped these
two, because they are not installed" is visible to whoever judges the claim. An unrecognised or blank
setting resolves to `STRICT` (`ExternalAbsenceMode.parse`), and the three-argument probe methods still
default to `STRICT`, so no existing caller silently became permitted to issue.

## The runtime gate, and the two defects it caught

This gate now exists and passes: `tools/premium-runtime/premium_reclaim_smoke.py` (one real Mineflayer
client, its own fixture staged on top of the gameplay fixture with `reclaim.issuance-enabled: true` and
`reclaim.external-absence-mode: INSTALLED_ONLY`, because without those two keys the fixture would only
ever measure the refusal path). The steps it performs are the list this section used to carry as
"owed": give + equip + `/ig check`, `/matdo check`, a refusal while the item is held, a vanilla
`/clear`, the issuance with a client-side assertion that the stack is in the inventory, a second
`/matdo sos` that must be refused by the claim lock, a clean restart, and the same refusal afterwards.
MySQL postconditions: exactly one `COMMITTED` claim naming the actor, at least one `DENIED` claim from
the while-held refusal, one `RECLAIM_ISSUED` history row, one snapshot row, and the same claim set
after the restart. Every fixture child exited 0 without a forced stop and both ports were released.

**It earned its keep on the first run by finding two real defects that every offline test had missed:**

1. **The claim never committed.** `arm` returned the decision with the record it was given — state
   `PENDING` — and the flow then settled with that same record. `settle` refuses anything that is not
   `PREPARED`, so every issuance armed the claim, delivered the item, wrote nothing, and left the
   identity locked in `PREPARED` **for ever**: no later claim could be reserved, and the audit trail
   showed an armed claim with no outcome. The unit tests missed it because they handed `settle` a
   `PREPARED` record directly and never exercised the flow. Fixed by carrying the moved claim
   (`ReclaimClaim.movedTo`), with tests for the armed, lost-race and refused-arming paths.
2. **A delivered item was reported as success.** The player-facing message was chosen by the
   `delivered` flag rather than by whether the claim was committed, so a hand-over that was never
   recorded told the player everything was fine — the exact outcome the design says a human must clean
   up, hidden by the success text. The message now follows the record (`settled.issued()`), and the
   delivered-but-unrecorded case has its own alarming text.

Both fixes are in the artifact this gate ran against; the receipt is bound in `run/` like the others.

## Boundaries — not implemented, and not claimed

- **Cooldown and per-player quota.** The requirement lists them; today the only limit is the unique
  index (one active-or-committed claim per identity, forever) and the capability gate. A player can
  therefore ask repeatedly for identities they own and have lost; each ask that is not eligible ends
  `DENIED` with the blocker recorded, and an eligible one succeeds once.
- **`addbackitem` / `removebackitem` / `showbackitem`.** A give-back queue for items without a claim
  does not exist.
- **`givenewid`.** Cloning a snapshot under a new identity is not implemented; issuing a new identity
  means creating and tagging one, and that path must be designed rather than improvised.
- **Quarantine / destructive actions.** Unchanged: nothing seizes, deletes or teleports items.
- **Player-facing reclaim GUI.** `/matdo check` is chat; the eligibility/cooldown/quota screen the
  requirements describe does not exist yet.
- **Runtime evidence.** The protocol is covered by unit tests, a source contract on the ordering, and
  the SQLite/MySQL schema tests. It has **not** been exercised on a running Paper server: enabling the
  gate and running a controlled Paper journey (give → snapshot → prove absence → issue → restart →
  read back, plus an inventory-full retry) is the gate that must pass before this ships enabled.
- **External adapters.** PlayerVaults/zAuctionHouse probes deny when the plugin is missing or
  disabled; a controlled runtime test with those plugins installed has not been run.
