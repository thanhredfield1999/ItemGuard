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

## The runtime gate that is still owed, spelled out

Issuance has unit, contract and schema coverage and the six gates for the current artifact all pass —
but none of them *issues* anything. The gate below is what turns "implemented" into "watched working",
and it is the acceptance criterion named in `docs/release/LITE_VS_FULL.md`:

Fixture config: `reclaim.issuance-enabled: true`, `reclaim.external-absence-mode: INSTALLED_ONLY`
(otherwise every presence probe denies, which is the point of §"The gate that made all of this
unreachable"). One real protocol client, `PremiumStaff`, in a controlled Paper + MySQL fixture.

1. `/clear` then `/give` a sword, equip it, `/ig check` → a code comes back, so identity and snapshot
   exist. Assert `item_snapshots` has a row for the code.
2. `/matdo check` → the item is listed as eligible (proves the player-facing list path).
3. `/matdo sos <code>` **while the item is held** → refuse with the `PLAYER_INVENTORY` blocker and a
   `DENIED` claim. This is the negative case: presence must beat the request.
4. `/clear <player>` (vanilla) → the item is destroyed; the snapshot is the only copy left.
5. `/matdo sos <code>` → the issued message arrives and the stack is **in the client's inventory**
   (client-side assertion, not just a server log line). Assert `reclaim_claims.state = COMMITTED`
   with `issued to PremiumStaff` in the detail, and one `item_history` row with action
   `RECLAIM_ISSUED`.
6. `/matdo sos <code>` again → refused by the claim lock, and the inventory still holds exactly one
   sword. This is the anti-duplication assertion, and it is the one that matters most.
7. Restart Paper, `/matdo check` → the identity is still committed; a second issuance is impossible.
8. Inventory-full retry: with a full inventory, issuance must end `DENIED` (retryable) and the item
   must still be claimable after making room — the failure path that protects the player's item.

Everything the gate cannot cover, stated up front: it uses `INSTALLED_ONLY`, so it does not prove
behaviour with PlayerVaults or zAuctionHouse actually installed (their APIs still deny, and a fixture
with those plugins has not been built); it does not exercise `/finditem giveoldid` (the same flow, a
different entry point — worth adding); and it does not cover a crash *between* delivery and commit.

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
