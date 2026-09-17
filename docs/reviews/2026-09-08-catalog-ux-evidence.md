# Catalog UX — parent evidence

Scope: design + disposable offline prototypes only, 2026-09-08. No Java/schema/config/runtime mutation in this UX task. Prior P0/P1 edits remain untouched. This document does not mark roadmap stages complete.

## Observed

Repo `E:/AI.WORK/ItemGuard`, main, HEAD `1b7d055` confirmed by git. Source audit and official HavenBags API references are recorded in `docs/design/2026-09-08-item-catalog-ux.md`. HavenBags installed version and live coverage are NOT VERIFIED.

## Independent review and disposition

First review `2026-09-08-catalog-ux-review.json`: CHANGES_REQUIRED; subtype success, modelUsage includes claude-opus-5 (and Haiku auxiliary).

- Duplicate back buttons: accepted usability concern, changed slot0 to coverage. The claim that category navigation duplicates category filter is not technically equivalent; no need to rely on that rationale.
- Person labels: accepted separation actor/current-probe/last-holder; only `Giữ gần nhất` on catalog, actor only timeline. Did not adopt more ambiguous `Ghi nhận cuối`; kept location in short lore rather than removing it to meet an arbitrary four-line target.
- Partial-page coverage: confirmed missing explicit state; added subtitle, coverage and page lore, distinct from query failure.
- Creative clone: accepted as test requirement, NOT confirmed exploit. No Paper test has been performed.
- Ever-held query cap/current-owner: confirmed against repository lines 775–843; requires new keyset query, else unavailable. Prototype chain is synthetic only.
- Pager adjacent to back/close: accepted UX fix; 47/51 paging, 45 back, 53 close, buffer slots.
- HavenBags empty API result: absence cannot be concluded across unverified capability. An unbound bag should say unbound/no configured owner when API explicitly proves that, not collapse all such states into unknown. Actual semantics await version-specific audit.

R2 `2026-09-08-catalog-ux-r2.json`: PROPOSED_DESIGN_OK for revised text snapshot. It is not source/adapter/runtime approval. Nonblocking parent clarifications were then added in design §9; root back disabled in both prototypes.

## Execution

Command: `node sketches/verify-catalog.cjs`
Result on final prototypes: OFFLINE_DOM_ONLY, 44 cases, 0 failures; 22 cases each; sharedTemplateExact=true.

Full test names and actual per-file SHA-256 are in `sketches/verification.json`. Cases cover entry variants, 54 slots, 36+4 paging, filter intersections, source vs storage, person/status, search and cancel, detail/back preserving page/query, timeline gaps, A→B→A retention, bag owner vs carrier and nested return, unrelated item no invented chain, empty/query failure/partial coverage, close/reopen, links and no external assets.

`git diff --check` exit 0. No Maven tests needed for this document/HTML-only change, and no full Java PASS is claimed.

## Limitations / blockers

- Browser harness stopped at Chrome permission request (`Allow remote debugging for this browser instance`). No bypass/retry after permission wall, no screenshot/pixel/overflow verification. User has not granted browser connection approval.
- jsdom installed only in `%LOCALAPPDATA%/Temp/itemguard-ux-check`, not added to ItemGuard pom or repo dependencies. `HTMLDialogElement.showModal/close` shimmed for DOM test; no actual browser modal/focus acceptance claim.
- A malformed first copy of variant B used a tool-truncated read result. Detected by byte/text comparison and replaced from full base64 transfer; final variants differ only by mode, verified in test.
- HTML mockups are Minecraft layout diagrams with persistent text labels/lore explanation; they are not a native client renderer or server integration. All names/items/timestamps are explicit synthetic examples.
- Native inventory shift/drag/number-key/creative-clone, permissions/async races, SQLite pagination/custody migrations, actual plugin adapters, save/restart and user visual acceptance remain unverified and require implementation approval plus runtime gates.

## Open

User chooses A (direct catalog, recommended) or B (task-first entry) and approves read-only product contract before schema/adapter implementation. Source-plugin list and HavenBags installed version must be discovered before integrations. No grant/delete permission is implied.
