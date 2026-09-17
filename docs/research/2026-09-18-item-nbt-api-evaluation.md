# Item-NBT-API (`tr7zw/Item-NBT-API`) — evaluated 2026-09-18, not adopted

**Question asked:** can this be applied to ItemGuard?
**Verdict:** no, not now. ItemGuard's identity already lives in the native
`PersistentDataContainer`, which needs no dependency, and adopting this one would cost jar size,
an install step for server owners, and a version-mapping layer that ItemGuard's baseline makes
expensive. The conditions that would change this answer are listed at the end, so the question is
not re-litigated from scratch next time.

## What the source says (OBSERVED, read 2026-09-18)

| | |
|---|---|
| Repository | `github.com/tr7zw/Item-NBT-API` — MIT, 685 stars, 93 forks |
| Latest release | **2.16.1**, committed 2026-09-17 (the day before this evaluation) — actively maintained |
| Modules | `item-nbt-api`, `item-nbt-plugin`, `nbt-injector`, `nbt-data-api`, `mappings-parser`, `wiki` |
| Claim | "Add custom NBT tags to Items/Tiles/Entities without NMS! Modify NBT and store it in Files, other NBT, or as String in yaml/json/SQL/Redis." |
| Distribution | SpigotMC resource 7939, Modrinth, CodeMC maven repository |
| API shape | `NBT.createNBTObject()`, `ReadableNBT`/`ReadWriteNBT`, SNBT parsing (`NBT.parseNBT`), NBT files, data-fixer utils, interface proxies |

Two usage modes, both from the project's own Maven wiki:

1. **Depend on the NBTAPI plugin** (`de.tr7zw:item-nbt-api-plugin`, `provided`, `depend: [NBTAPI]`).
   This is what the author recommends, and it puts a second plugin on every server that runs
   ItemGuard.
2. **Shade it in** — explicitly "not recommended" by the author, and it needs an empty
   `META-INF/.mojang-mapped` file for 1.20+ Paper, a relocation, and care because "it might also
   break other reflections/nms logic".

The module list matters: `nbt-injector` and `mappings-parser` are the library's own
version-mapping layer. User code never writes NMS, but the library sits on the server's internals,
which is where the maintenance burden goes when a Minecraft version changes.

## What ItemGuard actually uses (OBSERVED in this tree)

- Identity is stored in the **`PersistentDataContainer`** through
  `ItemGuard.java:358` (`new NamespacedKey(this, key)`), the same mechanism for the item identity
  key and for the GUI's own bookkeeping keys (`gui/HistoryGUI.java`, `gui/GUIListener.java`).
  PDC is native Bukkit API, available on both Spigot and Paper, and needs no dependency.
- Interop with **other plugins' PDC** is already a shipped, tested concern:
  `tracking/ForeignPluginPdcSurvivalTest` plus the two-plugin controlled fixture recorded in
  `docs/release/LITE_RELEASE_GATES.md`. The interop case that would motivate an NBT library is
  therefore already covered by the mechanism the item already uses.
- The one place ItemGuard needs the server's own object is narrow and already solved:
  `tracking/CraftItemStackPhysicalHandle.java` resolves `org.bukkit.craftbukkit[.v1_21_R3].CraftItemStack`
  by reflection, tolerating both the Paper/Purpur and the versioned Spigot class names. That is a
  *bounded* reflection path, not a mappings library.
- LITE ships **5,122,519 bytes** against SpigotMC's 10 MB upload limit, and `pom.xml` already
  documents dropping nineteen sqlite-jdbc native platforms to get under it. A second shaded library
  is not free here.

## Why "not adopted" is the honest answer

1. **It would not replace anything ItemGuard uses.** PDC is not a limitation ItemGuard is working
   around; it is the mechanism the identity, the history and the GUI already rely on, with runtime
   evidence behind it (power-cut, foreign-plugin coexistence, restart). Adding NBT-API would be a
   second way to do what one way already does.
2. **Both adoption modes have a cost ItemGuard would pay and not amortise.** A plugin dependency
   adds an install step and a new fail-closed path to design and test (what happens when a server
   has ItemGuard but not NBTAPI). Shading costs jar size, needs the `.mojang-mapped` marker, and
   buys a version-mapping layer to track across 1.21.4–1.21.11 plus Purpur — for a feature that is
   not on any roadmap.
3. **The project's own rule applies.** Third-party libraries are cherry-picked after review, not
   adopted wholesale, and every added dependency is a support surface. Nothing here has been
   requested by a user, measured as a gap, or needed to fix a defect.

## When this verdict should be revisited

Not "if it becomes popular" — each of these is a concrete trigger:

- **Interop/forensics with a plugin that stores identity in raw NBT** rather than PDC. If a
  customer's network runs an NMS-based dupe plugin or an item tool whose data is only in NBT and
  never in PDC, reading it through PDC will not work, and this becomes the shortest path.
- **A durability/interop defect ItemGuard cannot solve through PDC**, demonstrated by a failing
  controlled fixture rather than by reading the other plugin's source.
- **The FULL edition needing NBT file or SNBT utilities** (importers/exporters for migrations).
- **A supported Minecraft version changing PDC behaviour** — currently not the case on the
  baseline the harness certifies.

If any of those happens, the review must also decide the adoption mode (plugin dependency versus
shading) and re-run the affected controlled fixtures, because a second install dependency changes
the failure modes, not just the code.

## Sources

- `https://github.com/tr7zw/Item-NBT-API` (README, module list, license, release 2.16.1) — read 2026-09-18
- `https://github.com/tr7zw/Item-NBT-API/wiki/Using-Maven` (both adoption modes, the `.mojang-mapped`
  requirement, the "not recommended" note on shading) — read 2026-09-18
- `https://github.com/tr7zw/Item-NBT-API/wiki/Using-the-NBT-API` (API surface) — read 2026-09-18
- This tree: `ItemGuard.java:358`, `tracking/CraftItemStackPhysicalHandle.java`,
  `tracking/ForeignPluginPdcSurvivalTest`, `pom.xml` (shade filter and its rationale)

**limits:** no code from the library was run, no benchmark was taken, and no ItemGuard fixture was
executed for this evaluation. Star counts, download counts and badges are not evidence of quality
or performance. The artifact version was not resolved from a repository by this session.
