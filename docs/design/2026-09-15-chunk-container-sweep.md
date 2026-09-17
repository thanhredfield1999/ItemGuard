# Chunk container sweep — design contract

**Status:** design for implementation. No code written yet.

Closes the largest gap in duplicate detection: today the scan only sees a container while a
player has it open (`ItemTrackingService.java:941`), so two copies sitting in two closed
chests are never compared. That is the most common way a duplicated item is stored.

---

## 1. The rule that governs everything: one epoch = one complete pass

Duplicate detection counts observations that share a `scan_epoch`, and only for epochs marked
`epoch_complete=1`. That has a consequence which is easy to get wrong:

**If the sweep is spread across several epochs, detection silently stops working.** An item in
chest A observed under epoch 5 and its copy in chest B observed under epoch 6 never share an
epoch, so `COUNT(*) >= 2` never fires. The feature would appear to run and find nothing.

So the sweep may be spread across **ticks**, but never across **epochs**:

```
epoch N opens
  tick 1:  sweep chunk batch 1   -> observations tagged epoch N
  tick 2:  sweep chunk batch 2   -> observations tagged epoch N
  ...
  tick k:  sweep finishes        -> epoch N marked complete -> detection runs
epoch N+1 opens on the next interval
```

An epoch is a complete pass over everything in scope. It takes as many ticks as it takes.

### Consequence: interval is a floor, not a promise

If a sweep takes longer than `inventory-scan-interval`, the next epoch does **not** start on
top of it. Starting a second epoch while the first is open would split one logical pass across
two epoch ids — the exact failure above. The scheduler must skip a tick while a sweep is in
flight, and that skip must be visible in the logs rather than silent.

---

## 2. What gets swept

Only containers that are **already loaded**. The sweep must never load a chunk: forcing chunk
loads on a schedule is how a monitoring plugin becomes the reason a server lags.

- In scope: chests, trapped chests, barrels, shulker boxes placed in the world, hoppers,
  dispensers, droppers, furnaces — any block whose state is a `Container`, in a loaded chunk.
- Still out of scope, and it must stay documented as such: ender chests (per-player storage,
  not a block inventory), minecart and animal inventories (entity holders), and offline
  players' inventories.

A double chest counts as **one location**, exactly as it does today — `DoubleChestSlotPolicy`
already maps a raw slot to one physical half, and the observation key is built from that.

---

## 3. The cost, and the budget

`chunk.getTileEntities()` is the expensive call, not reading the slots. A busy server can have
thousands of loaded chunks.

- The sweep processes a bounded number of **chunks** per tick (`sweep.chunks-per-tick`,
  default deliberately small), and resumes from a cursor on the next tick.
- The chunk list is captured **once** at the start of the epoch. Chunks that unload mid-sweep
  are skipped; chunks that load mid-sweep are not added. A pass covers the world as it was
  when the pass began — which is the only self-consistent choice, and matches how the player
  snapshot already behaves.
- All of it runs on the main thread. Bukkit inventories cannot be read off-thread, and
  "faster" is not worth a race on live inventory data.
- The whole feature is one config key away from off (`anti-dupe.sweep.enabled`), and the
  budget is a config key too. A server owner who sees a cost must be able to turn it down
  without waiting for a release.

---

## 4. What this does NOT change

- No new duplicate *action*. Detection still ends in `NOTIFY`; LITE cannot remove or
  confiscate, and that stays true.
- No schema change. Container observations already have a `HolderType` and physical slot key;
  the sweep produces the same rows the open-container path produces today.
- No change to the false-positive guarantees. An item seen twice in the same physical slot
  still collapses to one row via the `ON CONFLICT` clause, so a slower pass cannot invent a
  duplicate.

---

## 5. The honest limits after this ships

State these in the README; do not let the feature sound total:

- Containers in unloaded chunks are still invisible. The sweep does not load chunks, so a
  stash in a region nobody visits is not compared. This is a deliberate trade for server
  health, not an oversight.
- Ender chests, minecart and animal inventories remain unscanned.
- A copy can still be carried by an offline player.
- A sweep is a snapshot of a moving world: an item moved from one swept chest to another
  mid-pass is observed at most once per physical slot, so it does not produce a false alarm —
  but a copy could in principle be moved into a region the cursor has already passed and be
  missed for that pass. It would be caught by the next one.

---

## 6. Test seams

The sweep's ordering logic must be testable without a server:

- **Cursor/batching**: given a captured chunk list and a budget, successive calls visit every
  chunk exactly once, then report the pass complete. Losing a chunk mid-pass skips it rather
  than aborting.
- **Epoch integrity**: every observation emitted during one pass carries the same epoch id;
  the epoch is finalised only after the last batch; a scheduler tick arriving while a pass is
  in flight does not open a second epoch.
- **Disabled path**: with the feature off, no chunk is visited at all — not "visited and
  skipped", so there is no cost to servers that do not want it.

Behaviour that genuinely needs Paper — that a real placed chest yields the observations, and
that a real duplicate across two closed chests raises `ITEMGUARD_DUPLICATE_CONFIRMED` — goes
to a controlled fixture, not to a unit test claiming it.
