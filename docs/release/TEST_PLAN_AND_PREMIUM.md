# ItemGuard test plan and Premium roadmap

Written 2026-09-16 on candidate `12ac2fcf`. Supersedes the testing sections of earlier notes.

Two separate questions, deliberately kept apart:

1. **What still needs testing before LITE is a finished product** — work that closes gaps in
   what is already shipped.
2. **What Premium becomes** — new capability, built only after LITE has told us what people
   actually want.

Mixing them is how the current FULL edition ended up advertising four features with no working
code behind them.

---

# Part 1 — Testing LITE to completion

## Already proven, and re-bound on 2026-09-17

These rows used to be bound to `12ac2fcf…`, to `796/796` tests and to a `13/13` matrix. Two of
those numbers are historical and must not be re-quoted: the `13/13` **cannot be reproduced** (four
of its versions have no protocol data in the pinned client library, and the run that claimed them
went through a jar-binding bug that booted 1.21.11 thirteen times — see
`LITE_RELEASE_GATES.md`), and the test count has moved. The current binding lives in one place,
`LITE_RELEASE_GATES.md`, and is candidate `8c0e540e…` with **855** Java tests and a **9**-version
matrix.

| Area | Current evidence |
|---|---|
| Unit/integration | 855/855 Java tests on `8c0e540e…` (`docs/reviews/lite-package-build.log`) |
| Tooling contracts | 107/107 `test_contracts.py`; 67/67 `scripts/test_*.py` |
| Version spread | **9/9** certified — Paper 1.21.4 · 1.21.5 · 1.21.6 · 1.21.8 · 1.21.9 · 1.21.10 · 1.21.11, Purpur 1.21.4 · 1.21.11. `1.21.7`, `26.1.1`, `26.1.2`, `26.2` untested — not failures, and never folded into a bigger number |
| Platforms | Spigot sweep PASS, Paper loss + full PASS |
| Performance | Tick-time A/B, cost below measurement resolution |
| Unclean shutdown | `taskkill /F` mid-write: every ItemGuard **record** survived; the item itself rolls back with vanilla `playerdata` |
| Plugin coexistence | BastionForgeLite loaded alongside on the current candidate; identity unchanged |

## Gaps worth closing, in order of what a real server would hit first

### P0 — things a first-week user could plausibly meet

**1. Two players, same item, at the same time.** Every fixture so far runs one bot plus one
staff account. Two players trading a tracked item, or two staff running `/ig check` on the same
item concurrently, has never been exercised. SQLite has one writer; the code paths that queue
around that are untested under contention.
*Shape:* extend `smoke.py` with a concurrent generation — two bots, simultaneous transfer, both
histories asserted.

**2. Reload without restart.** `/reload` and plugin managers are common on live servers and
are a classic source of listener leaks and stale state. Never tested.
*Shape:* runtime case — identity before reload, reload, identity after, plus assert no
duplicate listener registration.

**3. Inventory edge slots.** Armour slots, offhand, and the cursor during a drag are separate
code paths from the main inventory. In-hand tagging (new in this build) was verified on the
hotbar only.
*Shape:* probe cases per slot type.

**4. A second tracked item.** Everything so far uses exactly one. Two tracked items in one
inventory, one dropped, one kept, is the minimum realistic case and would catch any
slot-index confusion in the new tagging path.

### P1 — correctness claims that are currently narrower than they sound

**5. Crafting issuance.** Long-standing open item; an item produced by crafting has never been
verified to receive an identity.

**6. IG-R016 natural block-break.** Also long-standing and still open.

**7. Database growth over time.** `DatabaseSizeAdvisor` warns at 500 MB, but no run has ever
produced a database anywhere near that. The advisor's thresholds are untested against real
growth, and LITE never prunes history.
*Shape:* generate a synthetic history of 1M rows, measure size and query latency for
`/ig history`.

**8. Sweep under a loaded world.** The chunk sweep was measured on an empty flat world. A real
server has thousands of containers; `chunks-per-tick: 8` was chosen without evidence.

### P2 — worth knowing, not blocking

**9. Real power loss**, not just `taskkill /F` — filesystem-level corruption is below what
SQLite controls.
**10. `PRAGMA synchronous`** is never set explicitly; the power-cut run passed on driver
defaults. Pin it rather than inherit it.
**11. Folia** — only 4 files call `Bukkit.getScheduler()`, fewer than the earlier estimate of
13, plus the cross-region read in `InventoryScanTask`. Cheaper than assumed, but still only
worth doing if someone asks.
**12. GUI on a real client** — `/ig gui` has never been seen by a human on this build. Only
Thanh can do this one.

---

# Part 2 — Premium

## Ordering principle

Ship nothing to the paid edition until the free one proves demand. The first two weeks after
the SpigotMC listing are for listening, not building.

## 1. MySQL support — the right first feature

Requested by Thanh, and it is a genuinely good choice for a reason worth stating: **it is the
one feature whose absence blocks a whole class of buyer.** Any network running more than one
server (hub + survival, or several survival shards) cannot use a per-server SQLite file at all
— an item moving between servers would appear to be two items. That is not a nicer version of
a free feature; it is the difference between usable and unusable.

**Why the port is tractable:** only 6 files touch `Connection`/`PreparedStatement`, and there
are 6 uses of SQLite-specific SQL (`AUTOINCREMENT`, `INSERT OR REPLACE`, `last_insert_rowid`).
`SqliteConnectionOwner.java:243` is the single place a connection is opened. The surface is
small and already isolated.

**What it must not become:** a config toggle that half works. The real work is not the driver,
it is what a shared database implies:

- **Two servers writing the same row.** SQLite gave us one writer for free. MySQL does not,
  and neither does a hub plus three shards.
- **Cross-server duplicate detection.** Once the database is shared, the same item appearing
  on two servers *is* the headline feature — and it needs a server identifier on every row to
  report where.
- **Connection loss mid-write.** A network database can vanish in ways a local file cannot.
  Fail-closed behaviour has to be decided, not discovered in production.
- **Migration from SQLite.** Existing LITE users will have history worth keeping. A Premium
  upgrade that silently starts from zero would be a refund.

**Test shape:** a fixture running two Paper servers against one MySQL instance, an item moved
between them, and a duplicate confirmed cross-server. That fixture is the proof the feature
works; without it this is a claim, not a capability.

## 2. Restore and transfer

Still the reason Premium exists. `/ig restore` reissuing an item with its original identity
intact, so the restored item stays traceable and cannot become a quiet duplicate — with every
admin action recorded against the staff member who performed it.

This is the line against CoreProtect (1.27M downloads): it logs actions by location and time
and cannot say whether two swords existing right now are the same sword. Detecting a duplicate
is worth little if the player who lost something still cannot be helped.

Contract already written: `docs/design/2026-09-15-full-restore-contract.md`.

## 3. Issuance monitoring

Catch an operator funnelling items out, whether that is staff abusing access or a member who
obtained op. Design: `docs/design/2026-09-15-premium-issuance-monitoring.md`.

Placed after MySQL and restore because it needs a real abuse log to tune thresholds. Guessing
the rate window produces either false alarms during legitimate events or a detector that never
fires.

## 4. Things ItemGuard could plausibly do, ranked by whether they are worth it

Assessed against the plugin's actual shape, not wishful thinking:

| Idea | Verdict |
|---|---|
| **Cross-server item tracking** | Best of the list. Falls out of MySQL almost for free and nothing else on Spigot does it. |
| **Web dashboard / REST API** | Strong for networks, and the data model already supports it. Real cost is auth and hosting, not queries. |
| **Stacked item tracking** | Highest demand, genuinely hard. `ItemIdentityEligibilityPolicy` requires `maxStackSize == 1`; supporting stacks is a different data model, not a feature flag. Do not start without real demand. |
| **Discord alerts** | Cheap, already stubbed. Nice-to-have, not a reason to buy. |
| **WorldGuard region rules** | Cheap, narrow audience. |
| **Auction house / shop integration** | Would catch a real dupe vector (sell a dupe, buy it back), but each integration is a separate plugin API. Only after the core is solid. |
| **Item value analytics** | Sounds impressive, answers no question an admin actually asks. Skip. |
| **Auto-rollback on detection** | Dangerous. An anti-cheat that deletes a legitimate item costs more than the dupe. Detection plus one-click manual restore is the right shape. |

---

# Immediate next step

None of Part 1 or Part 2 comes before this: **Thanh approves the listing text and uploads
LITE**. Everything above is planning for a product nobody has downloaded yet.
