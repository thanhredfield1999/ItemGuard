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
