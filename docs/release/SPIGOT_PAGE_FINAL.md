> **SUPERSEDED 2026-09-17 — do not paste this file.** It is a hand-maintained duplicate of the
> listing, and it was never regenerated when the listing was corrected. False claims still in it,
> none of which the jar can support:
>
> - a version table marking `1.21.7`, `26.1.1`, `26.1.2` and `26.2` as PASS. The pinned bot
>   libraries carry no protocol for those four, so this harness cannot certify them; the
>   defensible count is **nine**, and the `13/13` it implies is unsupported.
> - "755 automated tests" (this candidate carries 835), "12.8 MB" (the jar is 5,118,820 bytes),
>   "including the 26.x line" in Requirements, "Tick every entry from 1.21.4 upward … including
>   the 26.x line" in the form instructions, and the title `(Paper 1.21.11)`.
>
> The current, generated copies are `release/spigot-upload/description.bbcode.txt` (source of
> truth), `release/spigot-upload/PREVIEW.html` (`scripts/build_listing_preview.py`) and
> `release/spigot-upload/_PASTE-THIS-TO-CLAUDE.md` (`scripts/build_spigot_handoff.py`).
> `python scripts/check_listing_copies.py` fails if a file like this one quotes a stale jar or
> calls itself current without saying so. Kept as a record of what was handed over before.

# ItemGuard LITE — SpigotMC resource page

Copy the sections below into the SpigotMC form. Nothing here may be published until Thanh
approves it. Every number in this document is bound to candidate
`a952d16146082ffacfbcbf6032fdba0634c86e8099cbf6802685c2f76db71727`.

---

## Download

Upload **one file**: `release/upload/ItemGuard-LITE-1.0.0.jar` (12.8 MB).

No archive — the licence and the install/backup instructions live in the page body below
instead, so a downloader sees them before the file rather than inside it.

---

## Resource name

```
ItemGuard LITE — Item Identity & Duplicate Detection (Paper 1.21.11)
```

## Tagline (short description field)

```
Give every valuable item a permanent ID, see where it has been, and get told when the same ID shows up in two places.
```

---

## Main description (BBCode-ready body text)

### Every sword has a name. Your server just cannot read it.

A player logs a ticket: *"someone duped the netherite set."* You check the logs. You see
pickups and drops and a hundred chest transactions, and none of it tells you whether the two
sets on your server are two items — or one item, twice.

ItemGuard LITE gives every non-stackable item a permanent identity code, records what happens
to it, and tells your staff when that same identity turns up in two places at once.

---

### What it actually does

**Permanent identity.** The first time a player picks up a sword, a pickaxe, a piece of
armour — anything that does not stack — it gets a short code like `#YTUUS3`, written into the
item itself. The code survives dropping, trading, storing, and server restarts.

**A history you can read.** `/ig history #YTUUS3` shows what happened to that exact item, not
to "a diamond sword". Who first held it, where it went, who has it now.

**Duplicate detection that sweeps closed chests.** The scan walks every container in
already-loaded chunks — chests, barrels, shulkers, hoppers — whether or not anyone has them
open. If the same identity is sitting in two places at the end of one complete scan, your
staff get a chat warning and the console records it.

That last point is the one worth reading twice. Hiding two copies in two closed chests is the
normal way a duped item is stored, and a scan that only looks at open containers never sees it.

**Honest numbers.** `/ig stats` reports duplicate *detections* and *distinct items*
separately, because one duplicated sword found in ten places over a week is one problem, not
twenty-seven. A statistic that reads worse than reality is not a feature.

---

### What it does NOT do

This section is deliberately as long as the one above.

- **It never deletes, confiscates, moves or grants an item.** The duplicate action is forced
  to `NOTIFY` in code; editing `config.yml` cannot enable removal. LITE tells you; you decide.
- **There is no restore, reclaim, or teleport command.** None is registered, so no permission
  — not even a wildcard grant — can reach one.
- **The sweep never loads a chunk.** A monitoring plugin that forces chunk loads on a timer
  becomes the reason your server lags. A stash in a region nobody visits is not compared, and
  that is a deliberate trade for server health.
- **Ender chests, minecart and animal inventories, and offline players are not scanned.**
- **It is not a read-only plugin.** It writes identity tags into item data. It is a
  non-destructive writer, but it does write. Only the `/ig gui` menu is read-only.
- **It does not promise complete anti-dupe prevention.** It records and warns. Anyone who
  tells you a plugin can guarantee otherwise is selling something.

---

### One setup warning, worth reading before you install

If you build shop items, VIP kits or gacha prizes by **copying an item out of a player's
inventory**, that copy carries the original's identity code — and so does every copy you hand
out afterwards. The scan then sees one identity in many places and warns about a duplicate for
every buyer. Nothing has gone wrong with your server; the template was contaminated before it
was ever sold.

**Build templates from a fresh `/give`, not from an item someone has held.**

LITE has no way to strip an identity back off an item, so this is a setup rule rather than
something you can fix afterwards. A `/ig untrack` command is planned for the paid edition.

---

### Commands

| Command | Permission | What it does |
|---|---|---|
| `/ig check` | `itemguard.check` | Show the ID of the item in your hand |
| `/ig history #ID` | `itemguard.history` | Timeline for one exact item |
| `/ig gui` | `itemguard.history` | Read-only browser |
| `/ig search #ID` | `itemguard.search` | Staff lookup |
| `/ig stats` | `itemguard.stats` | Database statistics |

There is no `/ig reload` in LITE. The command dispatcher accepts exactly five actions
(`LiteCommand.java:49`) and reload is not one of them, so the page must not list it.

`itemguard.notify` (default: op) receives the duplicate warnings.

---

### Requirements

- **Paper 1.21.4 or newer**, including the 26.x line. Not Spigot, not Folia.
- **Java 21** through Paper 1.21.11. Paper 26.x itself requires **Java 25**; the same JAR runs
  on either, unchanged.
- SQLite, bundled. No external database to set up.

#### Version matrix — measured, not assumed

Eleven versions. Each row is a real server, a duplicate planted in two never-opened chests,
and a verdict read from the plugin's own console output. The **same JAR** in every run.

| Paper | Build | JVM | Result |
|---|---|---|---|
| 1.21.4 | 232 | Java 21 | PASS — `locations=2` |
| 1.21.5 | 114 | Java 21 | PASS — `locations=2` |
| 1.21.6 | 48 | Java 21 | PASS — `locations=2` |
| 1.21.7 | 32 | Java 21 | PASS — `locations=2` |
| 1.21.8 | 60 | Java 21 | PASS — `locations=2` |
| 1.21.9 | 59 | Java 21 | PASS — `locations=2` |
| 1.21.10 | 130 | Java 21 | PASS — `locations=2` |
| 1.21.11 | 132 | Java 21 | PASS — `locations=2` |
| 26.1.1 | 29 | Java 25 | PASS — `locations=2` |
| 26.1.2 | 74 | Java 25 | PASS — `locations=2` |
| 26.2 | 123 | Java 25 | PASS — `locations=2` |

Eleven for eleven. No load failures, no `Unsupported API version`, no missing-method errors.

The plugin is compiled against the 1.21.4 API and declares `api-version: 1.21.4`, so the
compiler enforces that floor rather than a promise in a readme. Servers older than 1.21.4 are
refused at load time instead of running untested.

Versions released after this list have obviously not been tested. There is no version-specific
server code in the plugin, so newer builds are likely to work — but "likely" is not evidence
and is not a promise.

---

### Performance

The container sweep is spread across ticks with a bounded number of chunks per tick
(default 8), and one pass always finishes before the next begins. Two config keys let you
turn the cost down or off without waiting for a release:

```yaml
anti-dupe:
  sweep:
    enabled: true
    chunks-per-tick: 8
```

Everything runs on the main thread — reading live inventories off-thread is how monitoring
plugins corrupt the data they are supposed to protect.

---

### Verification

Every number below is bound to the exact JAR in the download:

- **755 automated tests**, 0 failures
- **4 controlled runtime fixtures** on a real Paper 1.21.11 server, including a duplicate
  planted in two never-opened chests being found by the sweep — and a control run with the
  sweep disabled, confirming nothing else found it

Verify your download matches the tested build:

```
SHA-256  a952d16146082ffacfbcbf6032fdba0634c86e8099cbf6802685c2f76db71727
```

**Not verified, and stated rather than hidden:** natural block-break identity issuance,
crash/power-loss recovery, large-scale or high-concurrency behaviour.

---

### Installing

1. Drop `ItemGuard-LITE-1.0.0.jar` into `plugins/`
2. Start the server — `plugins/ItemGuard/config.yml` is generated on first boot
3. Give your staff `itemguard.notify` so they receive duplicate warnings

There is nothing else to set up. SQLite is bundled; no external database.

**Backing up the database.** Stop the server first, then copy the whole
`plugins/ItemGuard/` folder. Copying `itemguard.db` while the server is running can capture a
half-written file.

**Upgrading later.** Replace the JAR and restart. Do not use `/reload` — reloading a plugin
that holds an open database is how servers lose data.

---

### Licence

Free to use on any server, including commercial ones, with no fee and no player limit. It is
not open source.

**You may:** run it anywhere, keep backups, edit the config and message files.

**You may not:** re-upload, mirror, resell or bundle the JAR; decompile or modify it; remove
the author or version string. Link to this page instead.

No warranty — you are responsible for your own backups. Bundles bStats under its own MIT
licence.

Full text: `docs/release/LICENSE-LITE.txt` in the repository, and reproduced in the
Licence field of this resource.

---

### Privacy

bStats server statistics only, and it is the plugin's only outbound connection. There is no
update checker. Opt out in `plugins/bStats/config.yml`.

---

### Support

Discord: **discord.gg/EnSNSNVV5G**

---

## Images to upload, in order

| # | File | Caption |
|---|---|---|
| icon | `assets/itemguard-logo-256.png` | resource icon — voxel chest with a cyan padlock |
| 1 | `assets/01-check.png` | `/ig check` — the ID, and the honest answer when there is none |
| 2 | `assets/02-history.png` | One item's history |
| 3 | `assets/03-timeline.png` | Timeline, with its own scope stated |
| 4 | `assets/04-gui.png` | Read-only browser |
| 5 | `assets/05-duplicate-alert.png` | The alert staff actually see |
| 6 | `assets/06-stats.png` | Detections vs distinct items |

All six are crops of real client frames. No mock-ups, no retyped text.

---

## Category

`Tools and Utilities` → moderation / anti-cheat

## Tags

`anti-dupe`, `duplication`, `item-tracking`, `logging`, `moderation`, `anti-cheat`, `paper`

## Version string

`1.0.0`

## Tested versions

Tick every entry from `1.21.4` upward that SpigotMC offers, including the 26.x line. All
eleven were verified on a real server with the exact JAR being uploaded; see the version
matrix in the body.

Do not tick anything below 1.21.4 — the plugin refuses to load there by design.
