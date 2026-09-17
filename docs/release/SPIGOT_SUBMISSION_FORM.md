> **SUPERSEDED 2026-09-17 — do not paste this file.** Written against an older build and never
> brought forward: its title (`ItemGuard LITE - Item ID & History Inspector (Paper 1.21.11)`), its
> tagline, its section 6 ("Media to attach — NOT YET PRODUCED", the media now exists) and the
> candidate hash at the bottom all describe `a952d161…`, a jar that has since had the C1/C2/C3 and
> H1–H5 fixes applied. Use the generated `release/spigot-upload/_PASTE-THIS-TO-CLAUDE.md` instead —
> it is built from `description.bbcode.txt`, checks its own hash against the jar, and reads the
> `Languages Supported` answer out of the jar rather than taking it on trust.
> `python scripts/check_listing_copies.py` fails if a file like this one quotes a stale jar or
> calls itself current without saying so. Kept as a record of what was handed over before.

# SpigotMC submission form — ItemGuard LITE 1.0.0

Status: **DRAFT — not submitted.** This file is the exact text to paste into each SpigotMC
field, plus the media checklist. Nothing here authorizes uploading. Owner approval is still
required, and the screenshots listed in section 6 do not exist yet.

Source of every claim: `docs/release/2026-09-12-lite-listing-draft.md`. If the two disagree,
the listing draft wins — it is the file the evidence is bound to.

---

## 1. Resource type

`Spigot Plugin` → category **Tools and Utilities** (secondary fit: Miscellaneous).
Not "Premium". This is a free resource.

## 2. Resource name (field: *Title*)

```
ItemGuard LITE - Item ID & History Inspector (Paper 1.21.11)
```

SpigotMC titles are plain text; the em dash in the listing draft is replaced with a hyphen so
it renders identically everywhere. Keep "LITE" capitalised — it distinguishes this from Full,
and keep the `(Paper 1.21.11)` suffix: the version picker in section 5 can only say "1.21", so
the title is where the exact target stays visible.

## 3. Tagline (field: *Tag line*, 1 short sentence)

```
Give every non-stackable item a permanent ID and a readable history, so staff can investigate instead of guess.
```

## 4. Version string (field: *Version*)

```
1.0.0
```

The plugin descriptor reports `1.0.0-lite` in console; the marketplace version field stays
`1.0.0` so update checks and the file name line up.

## 5. Tested Minecraft versions (field: *Tested Minecraft Versions*)

Tick **1.21** only.

Do not tick 1.20 or below, and do not write `1.21.x+` anywhere. The only version with runtime
evidence is **Paper 1.21.11 on Java 21**. SpigotMC's version picker is coarser than our
evidence, so the description must restate the exact target — see section 7, first line.

## 6. Media to attach (NOT YET PRODUCED)

| # | File | Shows | Status |
|---|---|---|---|
| 0 | `docs/release/assets/itemguard-lite-logo-256.png` | Resource icon (256x256) | **Ready** |
| 1 | `01-check.png` | `/ig check` — ID shown, and the "no tracking ID" case | **Ready** |
| 2 | `02-history.png` | `/ig history` list with the stated window | **Ready** |
| 3 | `03-timeline.png` | `/ig history #ID` timeline of one item | **Ready** |
| 4 | `04-gui.png` | `/ig gui` menu + "Recorded history, not live custody" tooltip | **Ready** |
| 5 | `05-duplicate-alert.png` | `[DUPE ALERT!]` warnings with `Locations:` | **Ready** |
| 6 | `06-stats.png` | `/ig stats` showing `Duplicate detections: 1 (distinct items: 1)` | **Ready** |

All six banners are built by `scripts/build_listing_banners.py` from real client frames in
`assets/raw/`; each is a crop plus a caption strip, never re-typed plugin text. Banner 6 was
captured on the shipping candidate with one identity present in ten places at once, which is
why it reads `1 (distinct items: 1)`. The older stats frames show the superseded
`Duplicate findings` wording and must not be published.

**Media set is complete.**

Rules for these images:

- Every screenshot must come from a **real client on a real server** running the exact
  candidate. No mockups, no composites of text that the plugin did not print.
- Capture in English (default language) so the images match the listing.
- Feature banners, if any, are built **from** these screenshots — not drawn from imagination.

## 7. Description (paste into the rich-text body)

> **Paper 1.21.11 / Java 21 only.** SpigotMC is the marketplace here; this is not a vanilla
> Spigot compatibility claim, and no other server version is supported.

### What it does

- Assigns a tracked identity to non-stackable items and records events: first tracked, picked
  up, dropped, used, moved, container activity, death.
- One command only: `/itemguard`, alias `/ig`.
  - `/ig check` — read the ID of the item in your hand. It reads an existing tag; it does not
    mint an ID just because you looked.
  - `/ig history` — your own recent activity, one row per item, drawn from a window of your
    45 most recent recorded events.
  - `/ig history #ID` — drill into one of your own items. Another player's ID needs
    `itemguard.history.others`.
  - `/ig gui` — a paged menu of your own recorded items, 28 per page. Clicks and drags inside
    the menu are cancelled; nothing can be taken out of it.
  - `/ig search #ID` — staff lookup of one exact ID.
  - `/ig stats` — staff database statistics.
- **Duplicate detection:** when the same identity is seen in two places within one completed
  inventory scan, the console records `ITEMGUARD_DUPLICATE_CONFIRMED` and online staff holding
  `itemguard.notify` get a chat warning. A cooldown limits repeat alerts.
- **Scan coverage, stated plainly:** the scan reads online players' inventories and sweeps
  every container in already-loaded chunks - chests, barrels, shulkers, hoppers - open or
  closed. Two copies hidden in two closed chests in a loaded area do raise a warning.
- **What it still cannot see:** the sweep never loads a chunk, so a stash in a region nobody
  visits is not compared. Ender chests, minecart and animal inventories, and offline players
  remain out of scope. The sweep is bounded per tick and can be turned down or off via
  `anti-dupe.sweep.enabled` / `anti-dupe.sweep.chunks-per-tick`.
- **Honest handover counting:** throwing your own item down and picking it back up does not
  count as a handover, and two players passing one item back and forth cannot inflate it.

### What it deliberately does not do

- Never deletes, confiscates, moves or grants items. The duplicate action is forced to
  `NOTIFY` in code; editing `config.yml` cannot enable removal.
- No restore, reclaim, lost-item recovery or teleport. No such command is registered, so no
  permission — including a wildcard grant — can reach one.
- No general text search, no live world scan. `/ig search` takes an exact ID.
- No WorldGuard and no Discord integration; both are forced off in code for LITE.
- Automatic history deletion is disabled in code for LITE, so your history is not pruned
  underneath you.

### Setting up shops, VIP kits and gacha prizes

Build template items from a fresh `/give` — never by copying an item that came out of a
player's inventory. A copied item carries its identity tag with it, so every copy you hand out
has the **same** ID, and ItemGuard will correctly report one identity in many places at once,
for every buyer. Templates made from a clean `/give` receive their own ID when each player
first handles them.

LITE has no command to strip an identity from an item. If a template is already contaminated,
rebuild it from a fresh `/give`.

### Please read before you install

- **ItemGuard LITE is not a read-only plugin.** It writes identity tags into item PDC and
  periodically scans inventories and open containers (defaults: 600-tick interval, container
  scanning on). Only the `/ig gui` menu is read-only. It is a non-destructive writer — but a
  writer.
- Recorded history is not live custody: the last recorded location is where an item **was**
  seen, not where it is now. Missing history is not proof an item never existed.
- Crafting whose output would need a new tracked identity is **cancelled fail-closed**. The
  refusal message is non-localized. What has been observed is the cancellation; issuance of a
  tracked identity through crafting has **never been verified**. Do not read this as crafting
  support.
- Behaviour around natural block breaking has **never been verified** and is disclosed as an
  open item.
- Do not install LITE and ItemGuard Full together — same plugin name, same PDC namespace.
- Back up `plugins/ItemGuard/itemguard.db` **with the server stopped**, together with any
  `-journal` / `-wal` / `-shm` sidecar files. A copy taken from a live server is not a safe
  backup.

### Metrics

ItemGuard LITE initialises bStats (plugin ID **34029**, shared with Full) and sends three
custom charts: `edition`, `language`, `anti_dupe_mode`. **There is no ItemGuard-specific
opt-out.** The only opt-out is the server-wide `plugins/bStats/config.yml` → `enabled: false`,
which disables collection for every bStats plugin on that server.

### Language

English by default. Set `general.language` to `vi` for Vietnamese. The crafting refusal message
is fixed wording in every language.

## 8. Permissions (paste as a table or list)

Taken from `src/main/resources/lite/plugin.yml` and the dispatch in
`src/main/java/com/itemguard/lite/LiteCommand.java`.

```
itemguard.check           default: true   /ig check
itemguard.history         default: true   /ig history AND /ig gui (the menu has no node of its own)
itemguard.history.others  default: op     investigate other players' items; also what makes
                                          /ig search return anything useful
itemguard.search          default: op     /ig search (the node the command checks)
itemguard.stats           default: op     /ig stats
itemguard.notify          default: op     duplicate alerts in chat
itemguard.admin           default: op     parent node only - grants the six above. LITE
                                          registers no admin command.
```

There is **no update checker** in LITE. bStats is the only outbound connection the plugin
makes.

Note on the usage line: the plugin descriptor advertises `/ig help`, and any unrecognised
argument — `help` included — prints
`/ig check | history | search #ID | stats | gui`. `help` is not a subcommand of its own.

## 9. External / donation links

Support / discussion: `https://discord.gg/EnSNSNVV5G`

Put this in the Discord field if the resource form has one; otherwise state it in the
description under a "Support" heading. It is also printed in the shipped `README.txt` and in
`LICENSE.txt`.

## 10. Licence to state on the resource

Proprietary, free of charge. The exact terms ship as `LICENSE.txt` inside the ZIP:

- Free to run on any server, public or commercial, no fee, no player cap.
- Config and message files may be edited for your own server.
- No redistribution, re-upload, mirroring, reselling or bundling — link to the resource page.
- No decompiling, reverse engineering or modifying the JAR.
- No warranty; bStats is bundled under its own MIT licence.

If SpigotMC asks for a licence label, say **"Proprietary / free to use, redistribution not
permitted"** — do not tick an open-source licence.

## 11. File to upload

```
release/ItemGuard-LITE-1.0.0.jar
```

Contains exactly `ItemGuard-LITE-1.0.0.jar`, `README.txt` and `LICENSE.txt`.

- Candidate JAR SHA-256: `a952d16146082ffacfbcbf6032fdba0634c86e8099cbf6802685c2f76db71727`
- Bundle SHA-256: `(no archive - the JAR is uploaded on its own)26bf4fca2b194d43ba7de3f38933a2ec8671f42c20d5a204793c4868`

Rebuilding the JAR replaces both hashes and voids the runtime evidence bound to them. If that
happens, re-run the fixtures before submitting.

## 12. Update / changelog text for the first release

```
First public release of ItemGuard LITE.

Item identity, event history, a read-only browsing menu and NOTIFY-only duplicate alerts,
for Paper 1.21.11 on Java 21. No restore, reclaim or teleport in LITE.
```

## 13. Blockers before this form can be submitted

1. **Owner approval of this text** and of the natural block-break disclosure wording.
2. Post-upload: verify the resource URL and that the version shown is `1.0.0`, then record
   both in the release gates file.

Console prints `1.0.0-lite` while the marketplace version is `1.0.0`; the README explains this
so it does not arrive as a bug report.
