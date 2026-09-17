# ItemGuard LITE — release candidate

## Target and artifact

- Distribution destination: SpigotMC resource site. Server platform: **Paper**, not vanilla Spigot or Folia.
- Current target: Paper 1.21.11 / Java 21. `api-version: 1.21.11` remains unchanged.
- Artifact: `target/ItemGuard-LITE-1.0.0.jar`.
- SHA-256: `3a94d308be5e17a4298769ccb4ad8213864f637733197dbb89af49857ce7bf44`.
- Item timelines now show how often an item genuinely changed hands. Throwing your own item on the floor and picking it back up is not counted; another player receiving it is. Repeated swapping between the same two players moves custody but does not raise the count, controlled by `tracking.custody-window-ms` (default 15 minutes). Holder names require `itemguard.history.others`; the counts do not.
- The browser is divided into three zones: a guide row, a framed content area, and a glass navigation row with paging and a centred page indicator. The redundant close button was removed; Minecraft already supplies closing. Disabled controls are labelled instead of blank, and an empty browser opens a guided screen. ItemGuard uses normal casing and no small caps.
- Latest clean package: 641 tests PASS, no failures/errors/skips. Earlier independent review of a prior build returned BLOCK; the fixes are included here: a member can no longer read another actor's account name or coordinates through the owner-scoped history window, and automatic history deletion is disabled in code for LITE rather than only by the shipped config.
- A controlled Paper startup fixture must be run for this exact hash before upload. It is not proof of client-visual quality, gameplay completeness, crash safety or production safety.
- Build receipt: `target/lite-build-receipt.json`; evidence: `docs/reviews/2026-09-11-lite-evidence.md`.
- This is an offline candidate plus one controlled-smoke pass. It is not proof of client-visual quality, gameplay completeness, crash safety or production safety.

## Scope

Shared engine: non-stackable item identity, recorded history, SQLite and duplicate warnings. LITE defaults to English; `general.language: vi` enables Vietnamese. The new command/preview text is bilingual; visual acceptance of the full shared-engine messages is pending.

LITE only creates identities for weapons, tools, armour/Elytra, spear tiers, bow/crossbow/trident and shield. It deliberately excludes golden apples, totems, Netherite materials/templates, Dragon Egg, Nether Star, Beacon, Conduit, Heart of the Sea, Fishing Rod, Shears, Flint & Steel, Brush, Spyglass and Recovery Compass — even when named or enchanted. Full administrators can opt eligible non-stackable materials in through `tracking.force-track-materials`; stackable materials remain excluded because per-item identity is fail-closed for stacks.

Commands (also `/itemguard`):

- `/ig check`: read the ID on the held item; does not create an ID merely for checking.
- `/ig history`: own recent history overview; the GUI shows one tracked identity per slot, with retained action totals, and clicking it opens that identity's bounded event timeline.
- `/ig search #ID` or `/ig history #ID`: staff-only exact item-ID history lookup, not general text search or a live-world scan.
- `/ig gui`: read-only paged overview of recorded identities (45 per page); click an item for its bounded timeline. `/ig gui #ID` remains staff-only detail lookup.
- `/ig stats`: staff database statistics rendered as readable values, not a Java object string.

Historical locations are not live custody. Missing history is not proof that an item does not exist. Overview totals cover retained database history; each item timeline remains bounded. The GUI is intentionally a small recent-history preview, not the Full catalog.

A member's history window is scoped by the identity's current holder, so it can contain events recorded while a different player held the item. Without `itemguard.history.others`, those foreign events stay visible as events but the other player's account name and recorded location are withheld. Staff with that permission see the full actor detail.

LITE writes identity tags into tracked items and periodically scans inventories/containers; that is a non-destructive PDC write, not a read-only plugin. Craft attempts whose output would need a new tracked identity are cancelled fail-closed with a non-localized message. Automatic history deletion is disabled in code for LITE.

Permissions: `itemguard.check` and `itemguard.history` default to everyone. `itemguard.history.others`, `itemguard.search`, `itemguard.stats`, `itemguard.notify`, `itemguard.admin` default to operators. No reclaim, withdraw, lost-item, teleport, external-container or external-storage commands are registered. Duplicate action is forced to NOTIFY even if an old config requests removal. WorldGuard and Discord features are disabled in LITE; it does not inherit region-level protection policy from Full.

## Packaging boundary

`ItemGuardLite` is a separate entry point, not a renamed Full command executor. The package retains tested shared/internal Full classes but exposes only the LITE command surface. This is not obfuscation or an entitlement-security boundary. The plugin name and PDC namespace remain `ItemGuard`, so **never install Full and LITE together**.

Build from the canonical repository with Java 21 and Python available:

    JAVA_HOME='C:/Program Files/Java/jdk-21' python scripts/package_lite.py

The packager runs Maven clean verify, rejects failed/skipped tests, creates the shaded LITE JAR, checks every entry against the source JAR except the explicit descriptor/config/manifest substitutions, and emits hashes. It never starts or deploys a server.

## Testing safety

Use only a fresh, explicitly approved isolated Paper test fixture with new plugin data. Do not replace an existing Full installation, import production databases/playerdata or downgrade item/database snapshots. Stop/start rather than `/reload` or hot loaders. Existing consumed fixtures/receipts remain consumed.

Known shared-engine gates remain open: tracking publication around natural break and crafting, stackable/merge coverage, multiplayer/concurrency, crash/unload recovery, production scale and native/runtime authority gates in CURRENT_STATE.md. Some crafting paths are intentionally fail-closed; this candidate must not be advertised as gameplay-complete or guaranteed anti-dupe protection. Test-server startup and permission/GUI/gameplay/restart journeys have not been performed for this JAR.

Do not upload publicly until the exact LITE artifact has approved controlled runtime evidence. Client visual acceptance is separate.

## Other versions

Fresh frozen-source compilation passed with Paper API 1.21.4-R0.1-SNAPSHOT (JDK 21) and 26.2.build.123-stable (JDK 25, Java 21 bytecode). That is source/API evidence only. The current JAR still rejects Paper below 1.21.11. CraftItemStack physical-handle reflection and serialized item migration require actual version-specific runtime testing. Do not list `1.21.4+` or `1.21.11+` as verified support.
