# LITE history clutter correction

## Observed RCA

Read-only query of manual fixture itemguard-lite-manual-05201e247811 found 9 item_history rows across 3 codes: 6 PICKUP, 3 DROP. No exact duplicate rows were demonstrated. Old LITE /ig history and /ig gui rendered each event independently, repeating the same item multiple times. Do not describe this as proven duplicate DB writes; do not delete audit records.

## Fix

- Personal history and GUI now show one row/icon per item code within the most recent 45 owner-scoped events, newest activity first, with latest action and explicitly window-scoped event count.
- /ig history #ID lets members inspect their own events within that same bounded personal window; it never calls the global by-code query. Staff exact-ID investigation retains its permissions and full bounded event timeline.
- Detailed timeline preserves repeated legitimate drops/pickups, includes readable action/date labels. GUI click/drag remain cancelled. No writer/schema or live data changes.
- Full window is labelled as possibly truncated, never proof that older events exist or proof of origin/current custody.

## Actual verification

Parent full package run: 411 tests, zero failures/errors/skips; docs/reviews/lite-history-parent-build.log and lite-package-build.log.
New presentation tests replay observed 3-item/9-event shape; summary one row/code, exact-ID keeps 3 pickups/2 drops for one item, member foreign-ID denial/global-query exclusion, GUI 3 icons with click/drag cancellation.
Candidate target/ItemGuard-LITE-1.0.0-test.jar SHA256 fa70222cb70ff7eaa855bf56905dc2805600f69fb008aefb2dea51d8836ebe1e.

Delegation through Codex failed before edits; direct Claude CLI timed out after partial implementation (missing GUI methods). Parent completed and tested the actual source. No claim of completed Claude review or preserved TDD RED proof for this change; full build and behavioral test coverage are verified.

NOT runtime/visual verified for new SHA. Manual server still has the old d86135e7 candidate; no replacement/reload/restart performed. A new approved session is required for in-game acceptance. Existing immutable smoke PASS remains evidence for old SHA only. Known stats formatting defect is outside this fix.
