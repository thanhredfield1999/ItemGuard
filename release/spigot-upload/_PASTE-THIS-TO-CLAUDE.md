# ItemGuard LITE — SpigotMC submission handoff

GENERATED FILE — do not edit. Rebuild with `python scripts/build_spigot_handoff.py`
after changing any listing text, or this copy silently goes stale.

Paste this whole file to the assistant that will fill in the form.
Target page: https://www.spigotmc.org/resources/add

## Instructions for the assistant

Fill every field below on the SpigotMC "Add resource" form, then **STOP before clicking
Submit**. The human will review the filled form and press Submit himself. Do not publish.

**About the files.** You cannot read the human's disk, and the jar/icon/screenshots are not
embedded in this document — they are binary. They all sit in the SAME FOLDER as this file:

    ItemGuard-LITE-1.0.0.jar
    icon-512.png
    screenshot-1-check.png  ...  screenshot-6-stats.png

If you are driving a browser, the human must attach or drag those files into the upload
inputs — ask him to do it at the right moment, or tell him which file goes in which slot and
let him pick. Everything that is TEXT is fully embedded below and needs nothing from disk.

Two things to be careful about:

1. The description is **already BBCode**. Paste it raw into the description box. Do not
   convert it, do not re-wrap it, do not "improve" the formatting.

   **Switch the editor to BBCode mode before pasting** — the `[ ]` / "Toggle BB code" button
   in the editor toolbar. In rich-text mode XenForo escapes the tags and they appear as
   literal `[B]` text on the published page. This already happened once on a first attempt.

   The markup uses only `[B]`, `[SIZE]`, `[LIST]`/`[*]`, `[CODE]` and `[SPOILER]` — the tags
   every XenForo install accepts. Earlier drafts used `[HR]`, `[ICODE]` and `[TABLE]`, which
   this Spigot install does not render. Do not reintroduce them.

2. Do not edit any wording. Every number and claim in it has been verified against the
   compiled jar; changing a sentence can make it false.

---

## Field: Resource type

    Spigot Plugin

(Pick this first — the category dropdown only appears afterwards.)

## Field: Title / Resource name

    ItemGuard LITE — Item Identity & Duplicate Detection (Paper/Purpur/Spigot 1.21.4+)

## Field: Tagline / short description

    Log every valuable item and prove where it came from. Each tool, weapon and armour piece gets a permanent ID plus full history — /ig check answers it instantly. Free, no setup, no database.

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

Tick exactly one:

    1.21

`26.1` and `26.2` were ticked on the earlier build of this plugin and must not be ticked now: the
jar on this page has never been run on the 26.x line, and this field is a claim of support. Spigot
only offers major groups here, so the individually-tested point releases cannot be expressed in
this field — they are listed inside the description instead, which is where the nine verified
builds and the four untested ones are stated separately.

## Field: Tags

    anti-dupe, duplication, item-tracking, logging, moderation, anti-cheat, paper

## Field: Source Code

    (leave empty — the plugin is proprietary, source is not published)

## Field: Donation Link

    (leave empty)

## Field: Languages Supported

    English

Read out of the jar's own `messages_*.yml` entries, not typed in here: LITE is English only, by
design and by packaging gate (`MessageLanguagePolicy` makes the edition decide the language, and
`message` files for other languages are removed from the jar). If the jar ever ships another
language, this field follows it and this paragraph needs rewriting.

## Field: Contributors

    (leave empty)

---

## File upload

Upload exactly one file, no archive. It is next to this document:

    ItemGuard-LITE-1.0.0.jar

Size 5,122,519 bytes.
SHA-256 8c0e540ec646ffec38cd1409cb69b8c339d1d83f7ba499841bbe6866d624ce4a

The SHA-256 printed in the description matches this file — this document is generated and
refuses to build if they ever diverge.

## Icon

    icon-512.png

## Screenshots — upload in this order, with these captions

| # | File | Caption |
|---|---|---|
| 1 | screenshot-1-check.png | /ig check — the ID, and the honest answer when there is none |
| 2 | screenshot-2-history.png | One item's history |
| 3 | screenshot-3-timeline.png | Timeline, with its own scope stated |
| 4 | screenshot-4-gui.png | Read-only browser |
| 5 | screenshot-5-duplicate-alert.png | The alert staff actually see |
| 6 | screenshot-6-stats.png | Detections vs distinct items |

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

Paste the block below **raw, as BBCode**, with the editor in BBCode mode.

```
[SIZE=5][B]ItemGuard LITE — Item Logger & History Tracker[/B][/SIZE]
[I]A player logs in with a second Mace. Duped, traded, or theirs all along?
Right now you're guessing. With ItemGuard you run one command and know.[/I]

Every valuable item gets a permanent ID as soon as it reaches a player, and a log of everywhere
it has been. Drop it, chest it, restart the server, cross worlds — the ID stays. When the same ID
turns up in more than one place at once, staff are alerted with the item's code and how many
places it was seen in — then [B]/ig history[/B] shows where it has been.

[B]Already running CoreProtect?[/B] Keep it. CoreProtect logs [I]actions by location and
time[/I] — who opened which chest, when. It can't tell you whether two swords that exist
right now are the same physical sword. That question is what ItemGuard answers, and the two
sit side by side without overlapping.

[B]Try it in 60 seconds[/B]
[LIST=1]
[*]Drop the jar in [I]/plugins[/I] and restart. No database setup, no dependencies.
[*]Hold any tool, weapon, or armour piece and run [B]/ig check[/B].
[*]That's your item's ID and its full trail. Nothing to configure first.
[/LIST]

[B]What you get[/B]
[LIST]
[*][B]Stop arguing with players[/B] — a full audit log of who held it and where it went. Ban
decisions backed by a record, not a hunch.
[*][B]Find duplicates hiding in storage[/B] — the sweep reads chests nobody has opened, in
chunks the server already has loaded. It never loads chunks itself, and it does not read
storage minecarts or donkey bags — nor will it let a tracked item move into or out of one,
because it cannot see the slot it would land in.
[*][B]Answer "is this legit?" in one command[/B] — [B]/ig check[/B] on any suspicious drop.
[/LIST]

[B]Commands[/B]
[CODE]/ig check      what is this item, and whose was it
/ig history    full trail for one item
/ig search     find an item by its code
/ig stats      tracking totals
/ig gui        browse it visually[/CODE]

Players only see their own items. Staff need [B]itemguard.history.others[/B] for anyone else's.

[B]Straight about the limits[/B] — LITE logs and reports; it never deletes or rolls back. It
tracks items that don't stack (tools, weapons, armour, shulkers), not stacked materials, and
skips ender chests. Want something that deletes extra stacks? A different plugin fits better.

[B]One thing it blocks, said plainly:[/B] until a craft output can be given an identity safely,
crafting a tool, weapon or armour piece is cancelled and the player is told why. Nothing else
about crafting changes, and the switch is in config.yml:
[CODE]tracking:
  cancel-untracked-craft-output: true   # false = let those crafts through, the ID arrives at the
                                        # next scan or pickup instead[/CODE]
Every other refusal stays fail-closed whatever that is set to: a crafted copy of an already
tagged item is still refused, and so is a tagged item whose identity cannot be verified.

[B]Two things to know up front[/B] — history is never auto-deleted (the plugin warns in console
past 500 MB rather than dropping evidence), and item IDs live on the items, so they remain in
the item data if you uninstall. Both explained in the spoilers below.

[B]Verified on[/B] nine Paper and Purpur builds, listed below — each one a real server with a real
planted duplicate. 1.21.7 and the 26.x line are [B]untested[/B]: not known to fail, simply not
measured yet, and this page will say so until they are. Java 21 · not Folia.

[SPOILER="Premium — planned, not released yet"]
LITE answers [I]where did this item come from[/I]. Premium is about [I]giving it back[/I].

Nothing here exists today. It's listed so you know the direction — no release date promised.
LITE stays free and keeps every feature it has now.

[LIST]
[*][B]/ig restore[/B] — reissue an item a player lost to a crash, a bug, or a scam, with the
original identity intact, so the restored item is still traceable and can't become a quiet dupe.
[*][B]/ig transfer[/B] — return an item to its rightful owner, recorded.
[*][B]Admin audit log[/B] — every restore and transfer logged against the staff member who did
it. A tool that can create items is only safe if it watches the admins too.
[*][B]Issuance monitoring[/B] — LITE tells you an item's history; it cannot tell you that an
operator has been quietly minting gear. Premium watches the rate at which tagged items enter
the world per staff account, and flags the pattern that matters: the same person issuing
valuable items repeatedly, or an account that suddenly gained op doing it. Detection and a
record first, with blocking as an option you switch on — an anti-cheat that freezes a
legitimate admin mid-event is worse than one that reports.
[*][B]Discord alerts[/B] and [B]WorldGuard region rules[/B].
[/LIST]

Why the split: detecting a duplicate is worth little if you still can't help the player who
actually lost something. That recovery path is the paid part.
[/SPOILER]

[SPOILER="Tested on 9 servers — every build listed"]
Each row is a real server with a real planted duplicate, verdict read from the plugin's own
console, each with its own confirmation code. All ran the exact jar published here.

[CODE]Paper      Build   Java   Result
1.21.4     232     21     PASS
1.21.5     114     21     PASS
1.21.6     48      21     PASS
1.21.8     60      21     PASS
1.21.9     59      21     PASS
1.21.10    130     21     PASS
1.21.11    132     21     PASS

Purpur 1.21.4   2416    21     PASS
Purpur 1.21.11  2568    21     PASS

Not tested: 1.21.7 · 26.1.1 · 26.1.2 · 26.2
Spigot 1.21.4 was tested on an earlier build of this plugin; it has not been re-run against the
jar on this page, so it is listed as untested rather than as a pass.[/CODE]

Purpur is a Paper fork on the same API, so both endpoints were checked rather than every
release in between. The gap at 1.21.7 is not a result about that release: the test client used
here has no protocol data for it, so no honest verdict can be produced either way.

Spigot has no prebuilt download, so it is compiled with BuildTools. An earlier build of this
plugin was verified that way; the jar on this page has not been, and is not claimed to have been.
Spigot lacks some Paper APIs, so one difference is visible to you: items are tagged when a
player picks them up, holds them, or a container scan reaches them — rather than at the moment
they spawn into the world.

Compiled against the 1.21.4 API, so the floor is compiler-enforced — older servers are refused
at load rather than running untested. Newer releases are untested: likely fine, not promised.
[/SPOILER]

[SPOILER="Performance, privacy, licence"]
[B]Measured, not guessed.[/B] Six servers were run — three with the plugin, three without,
alternating — on an identical workload of 400 hopper pairs shuffling tracked items, which is
the busiest code path this plugin has. Tick time was sampled from the server's own counter.

[CODE]mean ms/tick      run 1   run 2   run 3    median
without ItemGuard  0.87    0.79    0.87      0.87
with ItemGuard     0.82    0.99    0.76      0.82[/CODE]

The gap between the two (0.05ms) is [I]smaller than the variation between repeats of the same
arm[/I] (up to 0.23ms). These runs were made on the build immediately before this one; the
change since then only adds work when a player clicks or swaps an item, not on the hopper path
measured here. So the honest statement is a bound, not a figure: on this path the
cost is below what the test can resolve — under roughly 0.2ms of a 50ms tick budget. Anyone
claiming a precise number from data like this is reading noise.

What this does [B]not[/B] cover: a hundred real players touching commands and GUIs, other
plugins competing for the same tick, or memory growth over weeks.

[B]History is never auto-deleted in LITE.[/B] The database only grows — deliberate, because an
investigation log you can't trust to be complete isn't worth much. The plugin watches the file
and prints one console warning past [B]500 MB[/B] ([B]database.warn-size-mb[/B], 0 to silence).
It never deletes anything; if size becomes a problem, stop the server and archive the file —
items keep their IDs.

[B]Item IDs live on the items.[/B] Remove the plugin and those tags stay in the item data.
Invisible in game and harmless, but LITE has no untag command, so it's fair to say so.

The chest sweep is throttled ([B]chunks-per-tick: 8[/B] by default) and never loads chunks that
aren't already in memory, so it cannot drag in unloaded terrain.

[CODE]anti-dupe:
  sweep:
    enabled: true
    chunks-per-tick: 8

database:
  warn-size-mb: 500   # console warning past this size; 0 = off[/CODE]

Storage is a local SQLite file inside the plugin folder. Nothing is uploaded anywhere. The only
outbound connection is bStats, which you can switch off.

SHA-256 of the jar on this page:
[CODE]8c0e540ec646ffec38cd1409cb69b8c339d1d83f7ba499841bbe6866d624ce4a[/CODE]

Free on any server, including one that charges players. Don't resell or reupload it.
[/SPOILER]

[B]Found a dupe it missed, or something behaving oddly?[/B] Tell me and I'll look:
[URL=https://discord.gg/EnSNSNVV5G]discord.gg/EnSNSNVV5G[/URL]
```

---

## Final check before handing back

- Use Preview. Headings are bold and larger, bullets render as a real list, the spoilers
  collapse, and the version table shows as a monospace block.
- **No literal `[B]`, `[LIST]`, `[CODE]` or `[/SPOILER]` visible anywhere in the preview.**
  If you see them, the editor was in rich-text mode — clear the box, toggle BBCode, paste again.
- The version table lists **nine** builds, every one of them re-run against the jar on this page.
  `1.21.7` and the `26.x` line are marked untested — they are not supported claims and must not be
  described as such. This line used to read "1.21, 26.1, 26.2", which was true of an earlier build
  and became false the moment the table was corrected; it lives in this generator rather than in
  the description, so regenerating never fixed it.
- **Submit has NOT been clicked.** Hand control back to the human.
