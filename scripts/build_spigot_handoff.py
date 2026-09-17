"""Rebuild the SpigotMC handoff document from the live listing sources.

The handoff embeds the description verbatim so the assistant filling in the form needs
nothing from disk. That embedding is a copy, and a copy kept in step by hand goes stale: on
2026-09-15 an independent review found the handoff still carrying the pre-Spigot page ("not
Spigot, not Folia", "Tested on 11 Paper versions") while description.bbcode.txt beside it had
already been updated. Whoever ran the handoff would have published the wrong page.

So the copy is generated, never edited. Run this after changing any listing text:

    python scripts/build_spigot_handoff.py

It fails loudly rather than writing a handoff that disagrees with the artifact.
"""
from __future__ import annotations

import hashlib
import re
import sys
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
UPLOAD = REPO / "release" / "spigot-upload"
PASTE = REPO / "docs" / "release" / "paste"
JAR = UPLOAD / "ItemGuard-LITE-1.0.0.jar"

TITLE = "ItemGuard LITE — Item Identity & Duplicate Detection (Paper/Purpur/Spigot 1.21.4+)"

# What a shipped message file is called on the form. A language the jar carries but this table does
# not name is a hard stop: filling the field from a guess is how the listing claimed Vietnamese.
LANGUAGE_NAMES = {"en": "English", "vi": "Tiếng Việt"}
MESSAGE_FILE = re.compile(r"^messages_([a-z]{2}(?:_[a-z]{2})?)\.yml$")


def read(path: Path) -> str:
    if not path.exists():
        sys.exit(f"missing source: {path}")
    return path.read_text(encoding="utf-8").replace("\r\n", "\n").strip()


def languages_supported(jar: Path) -> str:
    """The form's *Languages Supported* answer, read out of the jar instead of typed in.

    This line used to be the literal `English, Tiếng Việt`. LITE ships English only — the
    packaging gate removes the Vietnamese file and `MessageLanguagePolicy` forces the edition to
    `en` whatever `config.yml` asks for — so that literal was a false claim on the form, in the
    same family as the stale "Tested Minecraft Versions" tick it sits next to. A displayed claim
    has to come from the artifact it describes.
    """
    with zipfile.ZipFile(jar) as archive:
        codes = sorted(
            {match.group(1) for name in archive.namelist() if (match := MESSAGE_FILE.match(name))}
        )
    if not codes:
        return "(leave empty — the jar ships no message file)"
    names = []
    for code in codes:
        if code not in LANGUAGE_NAMES:
            sys.exit(
                f"the jar ships messages_{code}.yml and this generator cannot name that language.\n"
                "  add it to LANGUAGE_NAMES — do not fill the field in from a guess."
            )
        names.append(LANGUAGE_NAMES[code])
    return ", ".join(names)


def main() -> None:
    description = read(UPLOAD / "description.bbcode.txt")
    tagline = read(PASTE / "02-tagline.txt")
    licence = read(PASTE / "04-licence-field.txt")

    if not JAR.exists():
        sys.exit(f"missing jar: {JAR}")
    digest = hashlib.sha256(JAR.read_bytes()).hexdigest()
    size = JAR.stat().st_size
    languages = languages_supported(JAR)

    # The description prints the SHA-256 for downloaders to check. If it does not match the jar
    # sitting next to it, the page is lying about the file — refuse rather than regenerate.
    if digest not in description:
        sys.exit(
            "the SHA-256 in description.bbcode.txt does not match the jar in this folder.\n"
            f"  jar         {digest}\n"
            "  fix the description (or repackage) before rebuilding the handoff."
        )

    document = f"""# ItemGuard LITE — SpigotMC submission handoff

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

    {TITLE}

## Field: Tagline / short description

    {tagline}

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

    {languages}

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

Size {size:,} bytes.
SHA-256 {digest}

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
{licence}
```

---

## Field: Description

Paste the block below **raw, as BBCode**, with the editor in BBCode mode.

```
{description}
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
"""

    target = UPLOAD / "_PASTE-THIS-TO-CLAUDE.md"
    target.write_text(document, encoding="utf-8")
    print(f"wrote {target}")
    print(f"  sha256 {digest}")
    print(f"  size   {size:,} bytes")

    # The runbook tells the owner to paste the title out of this file, so the file has to be the
    # same string this document just used. It used to be hand-maintained and had drifted to
    # "Item Logger, History Tracker & Duplicate Detection" against a title field that said
    # something else entirely.
    name_field = PASTE / "01-resource-name.txt"
    name_field.write_text(TITLE + "\n", encoding="utf-8")
    print(f"wrote {name_field}")


if __name__ == "__main__":
    main()
