# ItemGuard LITE — SpigotMC submission handoff

> **SUPERSEDED 2026-09-17 — do not paste this file.** Its version table and its form instructions
> predate the candidate `94eb0dad…13de3c` and still claim thirteen tested versions and a 26.x
> support tick, neither of which the jar has evidence for.
>
> The current handoff is generated: `release/spigot-upload/_PASTE-THIS-TO-CLAUDE.md`
> (source of truth: `release/spigot-upload/description.bbcode.txt`, generator:
> `scripts/build_spigot_handoff.py`). Nothing generates *this* file, which is exactly why it
> drifted; copying the generated content back into it by hand would relaunch the same problem.
> Kept only as a record of what was handed over before.

Paste this whole file to the assistant that will fill in the form.
Target page: https://www.spigotmc.org/resources/add

## Instructions for the assistant

Fill every field below on the SpigotMC "Add resource" form, then **STOP before clicking
Submit**. The human will review the filled form and press Submit himself. Do not publish.

Two things to be careful about:

1. The description is **already BBCode**. Paste it raw into the description box. Do not
   convert it, do not re-wrap it, do not "improve" the formatting. If the editor is in
   rich-text/WYSIWYG mode, switch it to BBCode mode first (the `[ ]` toggle button), or the
   tags will be escaped and show as literal text.
2. Do not edit any wording. Every number and claim in it has been verified against the
   compiled jar; changing a sentence can make it false.

---

## Field: Resource type

    Spigot Plugin

(Pick this first — the category dropdown only appears afterwards.)

## Field: Title / Resource name

    ItemGuard LITE — Item Identity & Duplicate Detection (Paper 1.21.4+)

## Field: Tagline / short description

    Give every valuable item a permanent ID, see where it has been, and get told when the same ID shows up in two places.

## Field: Category

    Tools and Utilities

## Field: Version string

    1.0.0

## Field: Price

    Free

## Field: Native Major MC Version

    1.21

Single-select. This is the version the plugin primarily targets, not the support range.

## Field: Tested Major MC Versions

Tick exactly these three:

    1.21
    26.1
    26.2

Spigot only offers major groups here, so the eleven individually-tested point releases
cannot be expressed in this field — they are listed inside the description instead.

## Field: Tags

    anti-dupe, duplication, item-tracking, logging, moderation, anti-cheat, paper

## Field: Source Code

    (leave empty — the plugin is proprietary, source is not published)

## Field: Donation Link

    (leave empty)

## Field: Languages Supported

    English, Tiếng Việt

## Field: Contributors

    (leave empty)

---

## File upload

Upload exactly one file, no archive:

    E:\AI.WORK\ItemGuard\release\upload\ItemGuard-LITE-1.0.0.jar

Size 5,113,520 bytes.
SHA-256 a952d16146082ffacfbcbf6032fdba0634c86e8099cbf6802685c2f76db71727

The SHA-256 printed in the description must match this file. If it does not, stop and tell
the human — the page would be making a false claim.

## Icon

    E:\AI.WORK\ItemGuard\docs\release\assets\itemguard-logo-512.png

## Screenshots — upload in this order, with these captions

| # | File (under E:\AI.WORK\ItemGuard\docs\release\assets\) | Caption |
|---|---|---|
| 1 | 01-check.png | /ig check — the ID, and the honest answer when there is none |
| 2 | 02-history.png | One item's history |
| 3 | 03-timeline.png | Timeline, with its own scope stated |
| 4 | 04-gui.png | Read-only browser |
| 5 | 05-duplicate-alert.png | The alert staff actually see |
| 6 | 06-stats.png | Detections vs distinct items |

All six are crops of real client frames. No mock-ups.

---

## Field: Licence

Paste verbatim:

```
ItemGuard LITE - Licence
Copyright (c) 2026 thanhredfield1999. All rights reserved.

This is proprietary software. It is offered free of charge, but it is not open source
and it is not public domain.

You MAY:
  - Download ItemGuard LITE and run it on any Minecraft server you operate, including
    commercial and public servers, with no fee and no player limit.
  - Keep backups of the JAR and of the database it creates.
  - Edit plugins/ItemGuard/config.yml and the message files for your own server.

You MAY NOT:
  - Redistribute, re-upload, mirror, resell or bundle the JAR, whether modified or not.
    Link to the official SpigotMC resource page instead.
  - Decompile, disassemble, deobfuscate, reverse engineer or modify the JAR, or create
    derivative works from it, except where that right cannot be waived under the law
    that applies to you.
  - Remove or alter the plugin name, author, version string or this licence.
  - Claim authorship of the plugin or present it as your own work.

No warranty
The software is provided "as is", without warranty of any kind, express or implied,
including but not limited to the warranties of merchantability, fitness for a particular
purpose and non-infringement. The author is not liable for any claim, damages, data loss
or other liability arising from the software or its use. You are responsible for keeping
your own backups; see the README for the stopped-server backup procedure.

Third-party components
This plugin bundles bStats (bstats-bukkit), which is distributed under the MIT licence by
its own authors and remains subject to that licence.

Contact
Support and bug reports: https://discord.gg/EnSNSNVV5G
```

---

## Field: Description

Paste the block below **raw, as BBCode**, with no modification:

```
[SIZE=6][B]Which sword is the real one?[/B][/SIZE]

Someone reports a dupe. Your logs show a hundred pickups and drops — and still can't tell you if those two netherite sets are two items, or one item twice.

ItemGuard puts a permanent ID on every valuable item and warns you when the same ID shows up in two places.

[HR]

[SIZE=5][B]What it does[/B][/SIZE]

[B]🏷 Permanent ID[/B] — every non-stackable item gets a code like [ICODE]#YTUUS3[/ICODE]. Survives drops, trades, restarts.

[B]📜 History per item[/B] — [ICODE]/ig history #YTUUS3[/ICODE] tells you who had [I]that[/I] sword, not "a sword".

[B]🔍 Sees inside closed chests[/B] — chests, barrels, shulkers, hoppers, furnaces, droppers, any container block. Two copies in two locked chests is how duped gear is actually hidden.

[B]📊 Honest stats[/B] — one duped sword spotted ten times reads as one problem, not ten.

[HR]

[SIZE=5][B]What it won't do[/B][/SIZE]

[B]Never deletes, takes or gives items.[/B] It warns, you decide. Locked in code — no config changes it.
[B]Never loads chunks to scan.[/B] Your TPS isn't the price of monitoring.
[B]Never claims to stop every dupe.[/B] It records and warns. That's the honest claim.

[HR]

[SIZE=5][B]Commands[/B][/SIZE]

[TABLE]
[TR][TD][ICODE]/ig check[/ICODE][/TD][TD]ID of the item in your hand[/TD][/TR]
[TR][TD][ICODE]/ig history #ID[/ICODE][/TD][TD]Full timeline of one item[/TD][/TR]
[TR][TD][ICODE]/ig gui[/ICODE][/TD][TD]Read-only browser[/TD][/TR]
[TR][TD][ICODE]/ig search #ID[/ICODE][/TD][TD]Staff lookup[/TD][/TR]
[TR][TD][ICODE]/ig stats[/ICODE][/TD][TD]Database stats[/TD][/TR]
[/TABLE]

[HR]

[SIZE=5][B]Install[/B][/SIZE]

Drop the JAR in [ICODE]plugins/[/ICODE] → restart → give staff [ICODE]itemguard.notify[/ICODE]. Done.

[B]Paper 1.21.4+[/B] including 26.x · Java 21 (Java 25 on Paper 26.x) · not Spigot, not Folia

[HR]

[SIZE=5][B]⚠ Before you set up shops[/B][/SIZE]

Making a shop item or gacha prize by [B]copying an item from a player's inventory[/B]? That copy keeps the original's ID, and so does every copy you sell — so every buyer trips the dupe alarm.

[B]Use a fresh [ICODE]/give[/ICODE] for templates.[/B]

[HR]

[SPOILER="Tested on 11 Paper versions"]
Each row is a real server with a real planted duplicate, verdict read from the plugin's own console. All eleven runs used the exact jar published here (SHA-256 below).

[TABLE]
[TR][TD][B]Paper[/B][/TD][TD][B]Build[/B][/TD][TD][B]Java[/B][/TD][TD][B]Result[/B][/TD][/TR]
[TR][TD]1.21.4[/TD][TD]232[/TD][TD]21[/TD][TD]✅[/TD][/TR]
[TR][TD]1.21.5[/TD][TD]114[/TD][TD]21[/TD][TD]✅[/TD][/TR]
[TR][TD]1.21.6[/TD][TD]48[/TD][TD]21[/TD][TD]✅[/TD][/TR]
[TR][TD]1.21.7[/TD][TD]32[/TD][TD]21[/TD][TD]✅[/TD][/TR]
[TR][TD]1.21.8[/TD][TD]60[/TD][TD]21[/TD][TD]✅[/TD][/TR]
[TR][TD]1.21.9[/TD][TD]59[/TD][TD]21[/TD][TD]✅[/TD][/TR]
[TR][TD]1.21.10[/TD][TD]130[/TD][TD]21[/TD][TD]✅[/TD][/TR]
[TR][TD]1.21.11[/TD][TD]132[/TD][TD]21[/TD][TD]✅[/TD][/TR]
[TR][TD]26.1.1[/TD][TD]29[/TD][TD]25[/TD][TD]✅[/TD][/TR]
[TR][TD]26.1.2[/TD][TD]74[/TD][TD]25[/TD][TD]✅[/TD][/TR]
[TR][TD]26.2[/TD][TD]123[/TD][TD]25[/TD][TD]✅[/TD][/TR]
[/TABLE]

Compiled against the 1.21.4 API, so the floor is compiler-enforced. Older servers are refused at load rather than running untested. Newer releases are untested — likely fine, not promised.
[/SPOILER]

[SPOILER="Performance & config"]
The chest sweep runs a few chunks per tick and finishes one pass before starting the next. Turn it down or off any time:

[CODE]
anti-dupe:
  sweep:
    enabled: true
    chunks-per-tick: 8
[/CODE]

All on the main thread — reading live inventories off-thread is how monitoring plugins corrupt the data they're meant to protect.

Out of scope: ender chests, minecart/animal inventories, offline players, and regions nobody has visited.
[/SPOILER]

[SPOILER="Verification & licence"]
[CODE]
SHA-256  a952d16146082ffacfbcbf6032fdba0634c86e8099cbf6802685c2f76db71727
[/CODE]

755 automated tests, 0 failures. Runtime-tested on real Paper servers, including a control run with the sweep disabled to prove the sweep is what finds the duplicates.

Not verified, said out loud rather than hidden: natural block-break ID issuance, crash recovery, very large or high-concurrency servers.

bStats only — the plugin's single outbound connection, no update checker. Opt out in [ICODE]plugins/bStats/config.yml[/ICODE].

Free on any server including commercial, no player limit. Not open source: don't re-upload, mirror, resell or decompile — link here instead.
[/SPOILER]

[B]Support:[/B] [URL]https://discord.gg/EnSNSNVV5G[/URL]
```

---

## Final check before handing back

- Description box is in BBCode mode and the tags render (use Preview) — no literal `[B]` or
  stray `[/HR]` visible in the preview.
- The SHA-256 shown in the description matches the uploaded jar.
- Tested versions are 1.21, 26.1, 26.2 — and nothing below 1.21.
- **Submit has NOT been clicked.** Hand control back to the human.
