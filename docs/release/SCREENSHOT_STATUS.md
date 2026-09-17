# Screenshot status — ItemGuard LITE 1.0.0

**Media set is complete.** Six banners plus the resource icon, all built from real client
frames captured on the shipping candidate's behaviour. A screenshot of text the plugin did not
print would be a fabricated claim, so every entry names the build it came from.

## Candidates

| Build | SHA-256 | Note |
|---|---|---|
| Superseded | `34d7fab2…9e0cd` | Stats line read `Duplicate findings: N` — misleading, re-shot |
| **Captured on (superseded)** | `c776a020…8fdf` | The build actually running when these frames were taken, and an earlier build than the one below — it is not shipped and must not be uploaded |
| Superseded | `a952d161…d71727` | Named as the shipping jar until the C1/H fixes landed; the frames were not re-shot for the jar below |
| **Shipping** | `8c0e540ec646ffec38cd1409cb69b8c339d1d83f7ba499841bbe6866d624ce4a` | The jar in `release/spigot-upload/` (re-pinned 2026-09-17 after the third review's fixes). The build before it, `80fc610b…`, is superseded and is not shipped |

**The capture build and the shipping build are not the same jar, and that has to be stated
rather than papered over.** Between them the changes were: Spigot/Purpur compatibility, the
snapshot-v2 encoding, the plugin.yml description line, the database size advisor, and then the
C1/C2/C3 and H1/H3/H4/H5 fixes. The first four alter no string these banners show (they were
checked when the listing first went out). The later ones are behavioural — a readiness reconcile,
an adoption rule, a two-epoch confirmation rule, a cooldown floor, a filter TTL — plus one *new*
message (the filter window notice) that no banner displays. None of them removes or rewrites a
line that is visible in a frame.

Re-verified rather than assumed on 2026-09-17 — the exact text in each banner was grepped against
the shipping source, at these lines:

| Banner text | Still produced by shipping source |
|---|---|
| `Duplicate detections: N (distinct items: M)` | `src/main/java/com/itemguard/lite/LiteCommand.java:169-170` |
| `This item has no tracking ID. Only non-stacking items are tracked…` | `LiteCommand.java:74` |
| `Item ID: #CODE` | `LiteCommand.java:76` |
| `it is not proof of the item's origin.` | `LiteCommand.java:188` |
| `[DUPE ALERT!] … (Code: …) has multiple copies!` | `src/main/resources/messages_en.yml:40` |
| `Locations: {locations}` | `messages_en.yml:41` |
| `Recorded history, not live custody` | `LiteMenuChrome.java:34`, `:115` |

A grep proves the string is still in the source; it does not prove the frame renders it. The
owner's client acceptance is what closes that, and it is still outstanding.

If any displayed string ever changes, these frames expire and must be re-shot. A screenshot is
a claim about what the plugin prints; it stops being evidence the moment the print changes.

## Banners (`assets/*.png`, 1280x720)

| File | Shows | Source frame |
|---|---|---|
| `01-check.png` | `/ig check` — an ID, and the "no tracking ID" case | `21.27.03.png` |
| `02-history.png` | `/ig history` with its own window stated on screen | `21.33.15.png` |
| `03-timeline.png` | `/ig history #ID`, including "not proof of origin" | `21.34.10.png` |
| `04-gui.png` | `/ig gui` menu + "Recorded history, not live custody" tooltip | `21.33.35.png` |
| `05-duplicate-alert.png` | `[DUPE ALERT!]` lines with `Locations:` | `21.42.31.png` |
| `06-stats.png` | `/ig stats` — `Duplicate detections: 1 (distinct items: 1)` | `00.03.12.png` |

Rebuild any time with `python scripts/build_listing_banners.py`. Each banner is a crop of the
frame plus a caption strip; plugin text is never re-typed into the image.

## What banner 6 actually proves

Captured on the shipping candidate with the same identity present in **ten** places at once
(`duplicate_findings`: one row, `distinct_locations=10`, `COUNT(DISTINCT item_uuid) = 1`). The
line reads `1 (distinct items: 1)` — one detection, one item.

That is the whole point of the change. On the earlier build the same situation printed a bare
`Duplicate findings:` count that climbed 4 → 12 → 26 as each scan re-detected the same sword,
which a server owner would read as twenty-six duplicated items.

## Unused frames

`21.36.00.png`, `21.42.35.png`, `21.49.37.png` — stats captures carrying the superseded
`Duplicate findings` wording. Kept for the record; **must not be published.**

`21.34.55.png`, `22.03.48.png`, `22.03.59.png`, `22.04.37.png` — spare captures, no banner
needed.
