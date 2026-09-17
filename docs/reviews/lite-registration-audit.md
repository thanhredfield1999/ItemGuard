# LITE registration audit — source inspection

Scope: current ItemGuardLite.java, ItemGuard.java, ItemLossListener.java and tools/lite-runtime/smoke.py. This is not runtime or independent-review evidence.

## Observed entrypoints

- ItemGuardLite extends ItemGuard; overrides registerCommands with LiteCommand executor/tab completer and listener. Only itemguard is resolved here.
- Parent onEnable still runs loadManagers, registerHooks, registerListeners, scheduleTasks and registerMetrics.
- Registered shared listeners: CatalogUi, GUIListener, FilterChatListener, PlayerListener, ContainerListener, ItemListener, ItemLossListener, CraftListener. Removing Full commands does NOT remove these listeners.
- ItemLossListener.start installs an inventory watcher every 20 ticks. **RESOLVED 2026-09-14**: onGroundItemDamaged previously recorded loss for classified damage causes without checking whether the damage actually killed the item. It now only remembers the reason; the loss is written by the new onGroundItemRemoved(EntityRemoveEvent) under a fail-closed allowlist. Offline evidence only — see `2026-09-14-item-loss-terminal-evidence.md`. The exact Paper removal cause for a lava burn is still unmeasured.
- Parent constructs FindItemService and ItemGuardAPI even in LITE. Construction alone is not evidence of a reachable destructive command; audit public consumers separately.
- Parent registers bStats (ID 34029) and edition/language/anti_dupe_mode charts. Do not describe the current implementation as having no telemetry; verify opt-out and document it before release.

## Smoke readiness

- smoke.py EXPECTED still pins aba4c0c13da1e060c491586946cb3f9a50508f73b36dfd8ceb1d640ec2520a3f, not the latest build receipt. Do not disable its hash check.
- Stage compiles the probe and copies immutable Paper dependencies into a fresh namespace; admission rejects consumed fixtures and verifies manifest/controller hashes.
- Latest candidate needs exact pinning only after registration audit and relevant fixes are complete. Changing the pin is not proof of compatibility with existing probe assumptions.
- No fixture staged or launched during this audit.

## Required next work

1. ~~Reproduce damage-with-surviving-item behavior at the ItemLossListener seam; test actual terminal observation rather than accepting a damage cause as proof of destruction.~~ Done 2026-09-14 at the unit layer (RED observed, mutation-proven). A controlled Paper fixture confirming the real removal cause for a burned item is still open.
2. Inspect shared GUI/filter listeners for reachable Full actions; add registration/behavioral guards where needed without deleting unrelated Full implementation.
3. Verify bStats opt-out/documentation and actual source ownership before changing telemetry.
4. Finish independent review and align smoke probe with current UI, then pin the final candidate and run an approved fresh fixture with cleanup.

Status: registration inventory complete for the inspected boot path; safety audit NOT complete. No release-ready claim.
