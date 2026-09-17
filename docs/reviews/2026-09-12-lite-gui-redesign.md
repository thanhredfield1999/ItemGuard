# LITE GUI redesign — zones, guidance, navigation (2026-09-12)

## Result

`PASS_CONTROLLED_SMOKE` for the redesigned browser. Candidate `6b119be549d3c548b3f935d8f958dd810046c21e40025e89cc32405b55c3e3da`, fixture `E:/AI.WORK/30_KET_QUA_THU_NGHIEM/itemguard-lite-isolated-3826b179b522` (consumed), Paper 1.21.11 / Java 21.

## What changed and why

The previous browser wrote tracked items into raw slots 0..44 with no frame, no guide, no page indicator, no close button, and an empty screen produced only a chat line. The redesign follows the zone pattern used by BastionForge (`LiteMenuLayout`/`SalvageMenuLayout`: layout constants separated from rendering, a guide slot near the top, a decorative frame, an action row at the bottom), adapted to a read-only browser.

Typography note: ItemGuard deliberately does **not** use small caps, unlike Genstycoon/HEOMC/BastionForge. A source scan of `src/main/java/com/itemguard/lite` confirms zero small-caps codepoints.

### Zones

- Row 1: guide. Slot 4 states what the screen shows, how many items are listed, what a click does, and that recorded history is not live custody.
- Rows 2 to 5: a framed content area of 28 slots (4 x 7) that never touches the border columns. `PAGE_SIZE` is derived from the content area so paging and layout cannot drift apart.
- Row 6: navigation. Previous at 45, page indicator at 47, close at 49, next at 53. On a timeline the bottom-left control becomes Back, so the exit never moves corner.
- Border and content background use different panes (`BLUE_STAINED_GLASS_PANE` vs `GRAY_STAINED_GLASS_PANE`) so the zones separate at a glance, and every slot is painted — no unexplained holes.

### Interaction

- Disabled paging is still a labelled control that says why ("Already on the first page"), rather than a blank slot.
- An empty browser now opens a guided empty state naming a concrete next step (`/ig check`) and repeating that missing history is not proof of absence.
- Clicks route by named slot constants: close closes, content opens that identity's timeline, previous/next page by exactly one content page, back returns to the overview. Every click is still cancelled.

## Verification

- Offline: `LiteMenuLayoutTest` 9, `LiteMenuChromeTest` 8, `LiteMenuRenderTest` 8, `LiteMenuNavigationTest` 5, plus the existing LITE suite — 52/52 LITE tests. Full project `clean test package`: **448/448 PASS**.
- Each new class was written test-first. Observed RED included the missing `LiteMenuLayout`, the missing `LiteMenuChrome`, all 8 render assertions before the rewrite, and 3 navigation assertions before the router changed.
- Runtime, generation 1: `gui-open`, `gui-guide-row`, `gui-close-button`, `gui-framed-border`, `gui-click-readonly` all PASS, alongside `permissions`, `identity`, `history`, `duplicate-not-removed`. Generation 2 after one clean restart: `restart-identity`, `restart-history` PASS. Zero ERROR/exception/probe-fail in both generations.
- The bot's inspect-before-click receipt recorded the real rendered content slot: `slot 10, diamond_sword, count 1`, and the cursor stayed empty after clicking.
- Cleanup: all four children exited 0, none forced; port 54699 refuses connections.

## Harness corrections made along the way

Two harness defects were found and fixed before the passing run, both in test tooling rather than the product:

1. `LiteProbe.java` asserted slot 0 was `PAPER`. Slot 0 is now the decorative border, so the probe now asserts the guide book at 4, the barrier at 49, a glass pane at 0, and a non-empty first content slot at 10.
2. `bots.cjs` still expected a window titled `LITE -` and clicked slot 0. The title is now `ItemGuard - ...` and the first identity is at slot 10; the bot was updated and now reports which material it inspected.

Attempt on fixture `itemguard-lite-isolated-4dbc4c4fc212` failed on defect 2 and is consumed with `HARNESS_FAIL`; it was not replayed.

## Boundaries

Still `NOT_RELEASE_READY`. This is automated runtime evidence, not client visual acceptance: Thanh has not yet inspected the new GUI in a real client, and Vietnamese presentation acceptance is still outstanding. No crafting/natural-break, scale, concurrency, crash or guardian claims; no other Paper version; no public upload, commit, push, deploy or production change.
