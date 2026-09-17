# Screenshot session plan — ItemGuard LITE 1.0.0

Seven screenshots are needed for the SpigotMC listing. Every one of them has to be taken from
a **real Minecraft client on a real server** running the exact release candidate. A screenshot
of text the plugin did not actually print is a fabricated claim, so none of these can be
produced by an agent, a mockup or an image generator.

- Candidate: `8c0e540ec646ffec38cd1409cb69b8c339d1d83f7ba499841bbe6866d624ce4a`
- Server: Paper 1.21.11, Java 21, language English (default)
- Save to: `docs/release/assets/`

The six banners currently shipping were captured on an earlier build (`c776a020…`); their text
was re-verified against this candidate's source on 2026-09-17 and is recorded, line by line, in
`SCREENSHOT_STATUS.md`. Re-shooting this session is therefore a *quality* decision, not a
correctness one — but any of these four things makes it mandatory: a displayed string changed, a
new screen was added to the GUI, the layout moved, or a frame shows a state the plugin no longer
produces.

## Opening the session

The manual launcher enforces one server at a time and a deadline. From the repo root:

```
python tools/lite-runtime/manual.py stage --op Thanh
python tools/lite-runtime/manual.py run <EXACT_ROOT_PRINTED_BY_STAGE> --detach --minutes 45
```

Use the exact path `stage` prints. When the session ends:

```
python tools/lite-runtime/manual.py stop <EXACT_ROOT>
```

`stop` is mandatory even if the session went badly — see `MANUAL_LIFECYCLE.md`.

## Setup inside the game

1. Join with a staff account that has op (needed for `search`, `stats`, `notify`).
2. Hold a **non-stackable** item — a diamond sword, a tool, armour. Stackable blocks are not
   tracked, so they produce empty screenshots.
3. Generate some history before capturing: drop the item, pick it back up, put it in a chest,
   take it out. Without events, the history and GUI shots are blank and prove nothing.

## The seven shots

| # | File | Command / action | Must be visible in frame |
|---|------|------------------|--------------------------|
| 1 | `01-check.png` | `/ig check` holding the tracked item | The item ID line |
| 2 | `02-history.png` | `/ig history` | Several rows, one per item |
| 3 | `03-timeline.png` | `/ig history #ID` | Ordered events with times/locations |
| 4 | `04-gui.png` | `/ig gui` | The paged menu with real entries, paging row |
| 5 | `05-gui-timeline.png` | Click an entry in the GUI | The item's timeline rendered in the menu |
| 6 | `06-duplicate-alert.png` | Trigger a duplicate detection | The chat warning to `itemguard.notify` staff |
| 7 | `07-search-stats.png` | `/ig search #ID` then `/ig stats` | Both staff outputs |

Notes:

- Shot 6 is the hardest: it needs the same identity to be observed in two places within one
  completed inventory scan. If it cannot be produced honestly during the session, **ship six
  screenshots** and drop the duplicate image. Do not stage a fake alert line.
- Chat shots: press F1 to hide the HUD only if the chat stays readable; otherwise leave it on.
- Keep the resource pack default. The listing should show what a normal server looks like.

## After the session

1. `stop <EXACT_ROOT>` and confirm the receipt, the owned PIDs and the released port.
2. Copy the PNGs into `docs/release/assets/`.
3. Only then build the feature banners — they are cropped/annotated versions of these exact
   frames, never redrawn content.
