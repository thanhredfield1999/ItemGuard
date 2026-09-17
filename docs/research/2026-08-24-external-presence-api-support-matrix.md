# External presence API support matrix

Ngày: 2026-08-24
Trạng thái: `VERIFIED research + unit`; external runtime `NOT VERIFIED`; issuance `CLOSED`.

## Mục tiêu

`/matdo sos` chỉ được eligible khi mọi storage bắt buộc chứng minh canonical
`item_uuid` không còn tồn tại. Plugin được cài hoặc API trả một phần dữ liệu không
đủ để kết luận `ABSENT`.

## PlayerVaults: hai plugin khác nhau

### Official PlayerVaultsX line — Bukkit name `PlayerVaults` 4.4.x

- Repo chính thức hiện tại: `KittehDev/PlayerVaultsX`, pinned commit
  `6446db7dcc7ad71c80e1bb564d97f88e3919047c`; repo cũ
  `drtshock/PlayerVaults` trỏ sang repo này.
- Runtime release quan sát được: `4.4.14 [1.21.11 - 26.2]`; source head
  `4.4.15-SNAPSHOT`, Java 21, plugin name `PlayerVaults`, main class
  `com.drtshock.playervaults.PlayerVaults`.
- Public API có:
  - `VaultManager#getInstance()`;
  - `getVaultNumbers(String holder)`;
  - `getVault(String holder, int number)`;
  - `vaultExists(String holder, int number)`.
- Holder chính xác là `playerUuid.toString()`, không phải display name.
- API này chưa phù hợp release gate ItemGuard:
  - `getVaultNumbers(holder)` có thể đọc file đồng bộ và tạo file holder bị thiếu;
  - `getVault(...)` tạo/manipulate Bukkit `Inventory`/`ItemStack`;
  - không có hard bound/pagination cho số vault hoặc tổng slot;
  - không có explicit thread-safety/main-thread contract.

Quyết định: `UNAVAILABLE`. Có public API nhưng không phải bounded, side-effect-free,
read-only absence query. Không được chạy synchronous file I/O + toàn bộ vault scan
trong command main-thread phase; cũng không được chuyển Bukkit inventory/PDC scan
sang async khi upstream không cam kết thread-safe.

### Modrinth `PlayerVaultsX` 1.0.x — separate fork

- Project `t1rxso3U`, package/main `com.playervaultsx`, Bukkit name
  `PlayerVaultsX`; metadata không công bố source và license `All Rights Reserved`.
- Artifact public methods chỉ expose open/save operations; không có public method
  enumerate mọi vault ID và đọc contents không mở admin session.

Quyết định: `UNAVAILABLE`. Không reflection private, không đọc YAML/storage nội bộ,
không command scraping. Không nhầm fork này với official `PlayerVaults` 4.4.x.

## zAuctionHouse V3

- Official API: `com.github.Maxlego08:zAuctionHouseV3-API:3.2.1.9`, pinned
  commit `e7c4cc15a4b6b94bb74eb21bf1ad8dcb0c4be5a6`.
- API ServicesManager có `AuctionManager`, `IStorage`, `AuctionItem#getItemStacks()`;
  online-player methods gồm selling/expire/buying lists.
- Không xác minh được official runtime plugin name từ API repo/plugin metadata.
- V3 không có offline UUID owner-specific bounded query; all-storage methods trả
  full synchronous `List`; không có pagination/read consistency contract.
- V3 và V4 dùng nhiều FQCN giống nhau nhưng ABI khác nhau, nên không compile cả hai
  adapters vào một classpath mà chưa có isolation strategy.

Quyết định: `UNAVAILABLE`. Không full-storage scan trên server thread, không DB/JSON
internal access và không reflection ABI probing.

## zAuctionHouse V4

- Source chính thức pinned commit
  `6d2fa0542877b2f83bfd0256b85a7b55fdb468da`; published API
  `fr.maxlego08.zauctionhouse:zauctionhousev4-api:4.0.1.2`, Java 21,
  Bukkit name `zAuctionHouse`.
- Public service entrypoint: `AuctionPlugin` qua Bukkit `ServicesManager`, rồi
  `getAuctionManager()`.
- Public methods gồm:
  - `getPlayerSellingItems(UUID)`;
  - `getItems(StorageType, Predicate<Item>)`;
  - `Item#isActivelyListed()`;
  - `AuctionItem#getItemStacks()`.
- Đây vẫn chưa phải bounded absence contract:
  - implementation `getPlayerSellingItems(UUID)` lọc đồng bộ toàn bucket
    `StorageType.LISTED`;
  - không có indexed/page cursor API cho owner;
  - `resolveItemsForPage` chỉ bounded sau khi caller đã có toàn bộ IDs;
  - `LISTED` query không tự bao phủ `PURCHASED` và `EXPIRED`;
  - UI sorted cache có thể stale, nên không dùng làm authoritative absence proof.

Quyết định: `UNAVAILABLE`. Không dùng synchronous full-bucket scan trong `/matdo`;
không chuyển Bukkit `ItemStack`/PDC inspection off-thread; không đọc DB/JSON nội bộ.

Điều kiện mở capability: upstream cung cấp indexed lookup theo owner/identity hoặc
bounded cursor/page query, side-effect-free, thread contract rõ và snapshot/read
consistency bao phủ mọi storage ItemGuard coi là có thể giữ physical item.

## Implementation ItemGuard

- `ExternalPresenceProbeFactory` phát hiện exact Bukkit name/version để tạo reason:
  - `PlayerVaults` 4.4.x: public API nhưng sync file I/O/side effect/no hard bound;
  - `PlayerVaultsX` 1.0.x: separate fork, thiếu enumerate/read API;
  - zAuctionHouse 3.x: public synchronous full-list API, không có hard bound hoặc
    cross-storage read consistency;
  - zAuctionHouse 4.x: public synchronous full-bucket API, không bao phủ đủ storage.
- Detection không bao giờ tự trả `ABSENT`; absent, unsupported và API-insufficient
  đều `UNAVAILABLE`.
- `ReclaimCapabilityGate` deny `UNAVAILABLE/ERROR`; issuance luôn đóng.
- Không thêm compile dependency vì chưa có adapter đáp ứng contract.

## Verification

- RED ban đầu: factory chưa tồn tại.
- RED research update: 3/5 factory tests fail vì reason cũ không phân biệt official
  PlayerVaults, separate fork và zAuctionHouse V4 sync full-bucket API.
- Follow-up RED→GREEN: mixed `PRESENT + ERROR` phải phân loại `DENIED_ERROR`;
  zAuctionHouse V3 phải có exact reason; lookup exception/null phải thành
  `DENIED_ERROR/CAPABILITY_SETUP`. Focused hai lớp `16/16`, full `167/167`.
- Independent exact-hash review: `cc/claude-opus-4-8` qua 9router `PASS`, không
  tìm thấy đường `ABSENT`, `ELIGIBLE` có issuance, item grant hoặc exception escape
  trong scope.
- External plugin runtime: `NOT VERIFIED`; controlled fixture chưa cài licensed
  PlayerVaults/zAuctionHouse runtime.

## Sources

- https://github.com/drtshock/PlayerVaults/blob/824b4789fce31c6c27abca6b66e8ed382ac63fd6/README.md
- https://github.com/KittehDev/PlayerVaultsX/tree/6446db7dcc7ad71c80e1bb564d97f88e3919047c
- https://www.spigotmc.org/resources/playervaultsx.51204
- https://api.modrinth.com/v2/project/t1rxso3U
- https://github.com/Maxlego08/zAuctionHouseV3-API/tree/e7c4cc15a4b6b94bb74eb21bf1ad8dcb0c4be5a6
- https://zauctionhouse.groupez.dev/development-portal/informations
- https://github.com/GroupeZ-dev/zAuctionHouse/tree/6d2fa0542877b2f83bfd0256b85a7b55fdb468da
- https://docs.groupez.dev/zauctionhouse/development/api
- https://repo.groupez.dev/releases/fr/maxlego08/zauctionhouse/zauctionhousev4-api/maven-metadata.xml
