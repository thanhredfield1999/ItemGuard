# ItemGuard FULL (Premium) — paid-edition listing draft — NOT PUBLISHED

**Status: draft for Thanh to review, cut, price and publish.** Nothing here is published, and every
capability claim below is built from `docs/release/LITE_VS_FULL.md` (the source-audited truth table,
re-audited 2026-09-19) and from the runtime receipts bound in `run/` — not from the roadmap. What the
plugin does not do is listed with the same weight as what it does, because the 2026-09-15 audit of the
LITE listing exists precisely because a paid page promising something the code cannot do is the most
expensive mistake this project can make.

Artifact the claims are true for:

    target/ItemGuard-1.0.0-shaded.jar
    SHA-256 09886ec451d020daa4b9b943e27f222adae4b13ec77c24e5a8bf7a2fc74c62d6

---

## Resource title

```
ItemGuard FULL — tracked identities across a whole network, and a reclaim hand-over that cannot duplicate
```

## Tagline (short description field)

```
The same permanent item identity as LITE, now shared across every server in your network: see which server an item has been on, hand a provably-missing item back without ever creating a second copy, and read the audit trail from one place.
```

## Category / platform

- Category: Admin Tools (or Anti-Griefing / Anti-Cheat — owner decides; LITE's listing used Admin Tools)
- Platform: **Paper 1.21.11 verified.** The 1.21.4+ floor is supported by intent but has not been re-run
  since the MySQL schema moved to version 10 — say "1.21.11 verified, 1.21.4+ untested since v10" unless
  those runs are made. Java 21.
- Backing store: MySQL 8 (shared) or SQLite (single server). LITE is SQLite-only.

---

## Main description (BBCode-ready body text)

### LITE tells staff that the same identity is in two places. On a network, nobody knows where the other place is.

A player on `survival` opens a ticket: *"someone has my sword."* Staff check the chest it should be in —
empty. They check the player's history on that server: the item was last seen on `skyblock`, three days
ago, in a container that no longer exists.

ItemGuard FULL is the same identity engine as LITE, with two things on top: a **shared database** so one
server can answer questions about items another server is holding, and a **hand-over** that gives a lost
item back without ever creating a copy of it.

---

### What it does that LITE cannot

**One schema, every server.** Observations, history, snapshots, catalog and reclaim claims live in one
MySQL schema with a `server_id` on every row. Two Paper servers sharing it are proven in a controlled
fixture: a cross-server sighting is reported as *"seen on server-1 and server-2, in this window"*. The
rule refuses to say more than that — a legitimate move and a copy are indistinguishable from
observations alone, so the message names servers and never claims a duplication.

**Handing an item back, safely.** `/matdo sos <id>` and `/finditem giveoldid <id>` return the *full
ItemStack snapshot* (not a rebuilt item from material and name) under a protocol that is designed around
its failure modes:

- **arm** — the claim moves to `PREPARED`, which locks the identity: no second claim can exist while a
  hand-over is in flight;
- **deliver** — one stack, server thread, an empty slot checked immediately before the write;
- **settle** — delivered becomes `COMMITTED` (permanent: that identity can never be handed out again),
  not delivered becomes `DENIED` and retryable.

A full inventory costs the player a retry, not the item. A hand-over that was delivered but could not be
recorded is reported as exactly that, and asks for a human — it is never shown as success.

**Absence has to be proven first.** Before anything is handed over, the plugin checks the player's
inventory, ender chest, and any supported storage plugin. A storage plugin that *is installed but cannot
be read* denies the reclaim; that is deliberate and cannot be configured away. A plugin that is *not
installed at all* is treated according to `reclaim.external-absence-mode` — read the two switches below,
because they decide whether reclaim can ever fire on your server.

**The admin surface.** `/finditem` gains `infoitem`, `infoplayer`, `infodupe`, `readfinding`, `readdupe`
and `checktps`, with a Bukkit-free reporter so the wording is unit-tested. `checktps` reports the
plugin's own numbers — scans, epochs, findings, alerts, sweep passes, p50/p95/last scan duration — and
says so rather than impersonating server TPS. Findings can be marked read, once, with the actor's name
(MySQL schema v10; on SQLite the command answers "needs the MySQL backend" rather than a silent zero).

**Discord, on its own switch.** A confirmed finding posts an embed naming the code, the identity and how
many locations it was seen in — captured by a local HTTP sink in the controlled gate, not assumed.

**Everything LITE has, plus the switches that do real work.** Cleanup is configurable and now off by
default; staff alerts go to the `itemguard.notify` permission on both editions; the browser, live search
and WorldGuard region gate are unchanged.

---

### What it does NOT do

This section is deliberately as long as the one above.

- **It never deletes, confiscates, moves or teleports an item.** `anti-dupe.action` accepts
  `REMOVE_NEWER`, `REMOVE_OLDER` and `REMOVE_ALL`, and the resolver downgrades every one of them to
  `NOTIFY` — a destructive choice is refused, and since 2026-09-19 the plugin logs a startup warning
  instead of ignoring it silently.
- **There is no per-player cooldown or quota on reclaim yet.** The limits today are: one active-or-committed
  claim per identity, forever; and the absence proof. A player can ask repeatedly for identities they own
  and have lost.
- **No give-back queue.** `addbackitem`, `removebackitem` and `showbackitem` do not exist.
- **No new-identity clone.** `givenewid` is not implemented; creating an identity means creating and
  tagging one, and that path is not built.
- **No quarantine or `RESOLVED` state.**
- **No player-facing reclaim screen.** `/matdo check` is chat; eligibility/cooldown/quota GUI does not
  exist yet.
- **Teleport-to-container does not exist**, and its permission was deleted from `plugin.yml` rather than
  left advertising nothing.
- **PlayerVaults and zAuctionHouse contents cannot be read** by any supported API path this plugin
  audited. If such a plugin is installed, reclaim denies. This is a limit of those APIs, not a bug, and
  it is the reason the external-absence switch exists.
- **The closed-container sweep is not proven on Premium/MySQL.** LITE's harness proves that path on LITE;
  the Premium gates cover inventories and open containers.
- **A crash between delivery and commit is not covered** by any gate. The plugin's behaviour in that
  window is documented (the claim stays `PREPARED`, the identity stays locked, the player is told to
  contact staff) but has not been measured.
- **It is not read-only.** It writes identity tags into item data and rows into your database.

---

### Two switches decide whether the paid features do anything

Owners get burned by plugins that quietly do nothing. These two ship **off/false**, on purpose, and this
is what they mean:

| Key | Ships as | What it decides |
|---|---|---|
| `anti-dupe.enabled` | `false` | Whether duplicate findings run at all. Detection is proven end to end (two real stacks → one finding → one staff alert → one Discord payload, nothing removed), but alerting is the switch an owner should turn on deliberately. |
| `reclaim.issuance-enabled` | `false` | Whether `/matdo sos` and `/finditem giveoldid` may hand an item back. Off means every eligible request is denied and the reason is recorded. |
| `reclaim.external-absence-mode` | `STRICT` | **Read this one twice.** `STRICT` refuses when PlayerVaults/zAuctionHouse are not installed — which means on a server that does not run them, reclaim can never fire. `INSTALLED_ONLY` skips a plugin that is not there (recorded in the claim's evidence as `NOT_APPLICABLE`) and still refuses when an installed one cannot be read. |
| `anti-dupe.observation-retention-minutes` | `30` | How long observation rows are kept. They answer "two places in this audit" and "which servers has this identity been seen on inside a window". Keep it at least as large as `multi-server.cross-server-window-minutes`, or a sighting is deleted before anyone asks. |

---

### Commands

| Command | Edition | What it does |
|---|---|---|
| `/ig`, `/igcheck`, `/ighistory`, `/igsearch`, `/igstats` (plus `/ig browser`, `/ig reload`, `/ig migrate`) | both | LITE's investigation surface, unchanged |
| `/finditem startfinding, starttaking, stopfinding, listfinding, removefinding, clearfinding` | FULL | search requests, tracking, quarantine intent |
| `/finditem infoitem, infoplayer, infodupe, readfinding, readdupe, checktps` | FULL | admin reports; findings triage; the plugin's own scan metrics |
| `/finditem giveoldid <id>` | FULL | return a proven-absent item to its recorded owner (admin) |
| `/matdo check`, `/matdo sos <id>` | FULL | a player sees eligible items and asks for one back |
| `/ig migrate` | FULL | one-way SQLite → MySQL migration, dry-run first |

Aliases exist for an international audience: `itemsearch` for `/finditem`, `itemreturn` for `/matdo`.

### Permissions

`itemguard.check`, `itemguard.history`, `itemguard.history.others`, `itemguard.search`,
`itemguard.stats`, `itemguard.gui`, `itemguard.notify` (duplicate alerts — the only alert node),
`itemguard.finditem.admin`, `itemguard.giveoldid` (hands an item out, separate on purpose),
`itemguard.matdo`, `itemguard.reload`, `itemguard.migrate`, `itemguard.track`. Deleted rather than
promised-and-empty: `itemguard.restore`, `itemguard.teleport`, `itemguard.bypass`.

### Requirements, install and migration

Paper 1.21.11 + Java 21; MySQL 8 for the shared path. Drop the jar in `plugins/`, start once, configure
`database.type: MYSQL` with URL/user/password and a `server-id` per server. `/ig migrate` copies a
SQLite install into MySQL: dry-run first, refuses a non-empty target, stamps `server_id`, verifies
per-table counts and never writes to the source. Backups are `mysqldump` **without** `--databases`
(that form injects the source schema name and a later restore writes back into it); a restore that is
missing one of the plugin's tables is refused by name, not repaired. Full runbook:
`docs/release/PREMIUM_HANDOFF.md`.

### Verification

Every number below is bound to the artifact hash at the top of this document, and each runtime gate has
its own receipt in `run/`:

- **966 automated tests**, 0 failures; the Vietnamese-literal gate 0 violations; 70 tooling contracts
- **MySQL schema gate**: 43/43 across 11 tagged classes on the same tree, including the v9 → v10 upgrade path
- **Eight controlled Paper/MySQL gates**: single Paper startup/restart; two Paper servers sharing one
  schema; SQLite → MySQL migration; reliability (fail-closed under a database outage, no retry, port
  released); a real-client gameplay journey; backup/restore; the **reclaim hand-over in four
  generations**; and **duplicate detection end to end**

**Not verified, and stated rather than hidden:** the 1.21.4+ floor since schema v10; closed-container
sweeps on Premium/MySQL; PlayerVaults/zAuctionHouse contents; destructive anti-dupe actions (refused by
policy); a crash between delivery and commit; and behaviour at network scale beyond two servers. The
gates were re-run on one machine — that is a controlled fixture, not a production deployment.

### Support, licence, refunds, price

Owner's decisions, not written here. See the decision sheet below.

---

## Owner decision sheet (not part of the published text)

1. **Price and model.** One-time per network, one-time per server, or annual with updates? The plugin's
   cost structure is your time, not per-request infrastructure, which argues for one-time; the counter
   argument is that a support burden on a niche tool is recurring, which argues for an annual update
   entitlement. LITE being free sets the anchor: FULL is what a *network* pays for.
2. **What the page promises first.** My recommendation: the two headlines are "one schema, every server"
   and "hand an item back without creating a copy", because both have runtime receipts. Reclaim lacks a
   cooldown/quota, so the page should not promise "abuse-proof reclaim".
3. **Whether the switches ship as they are.** `reclaim.issuance-enabled: false` plus
   `external-absence-mode: STRICT` means a buyer who installs and changes nothing gets a silent plugin
   for the paid feature. Either the page teaches the two keys in the first screen, or one defaults need
   to change (and then the runtime gate should be re-run with that default).
4. **Screenshots.** None exist for FULL. The gates produce behaviour, not images: the catalog browser, a
   finding alert, a reclaim success, `checktps`. Thanh takes these on a real client — that is the
   acceptance step, not something a harness can hand over.
5. **What is in the first paid release.** From the unimplemented list, mine would be: reclaim
   cooldown/quota (it is the one gap a buyer will hit in week one) and the player-facing reclaim screen;
   `givenewid` and quarantine are features, not gaps.
6. **Market check.** LITE's Spigot page exists; no competitor price comparison has been done for FULL.
   If you want one, say so and I will research the actual listings rather than guess a number.
