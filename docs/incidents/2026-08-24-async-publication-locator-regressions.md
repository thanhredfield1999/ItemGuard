# Incident: async publication locator regressions

Ngày: 2026-08-24
Trạng thái: `VERIFIED controlled` cho các bản sửa; production `NOT VERIFIED`.

## Triệu chứng

1. Entity benchmark hậu async trả `complete=0`, timeout 5 giây và không tạo canonical identity.
2. Player inventory journey thấy `DIAMOND_SWORD` nhưng `/matdo check` trả rỗng. Journal lặp `PLAYER_SLOT:...:0 → ABORTED/SOURCE_CHANGED`.

## Root cause

### Entity lifecycle

`ItemSpawnEvent` xảy ra trước khi Paper thêm entity vào world registry. Runtime telemetry trên Paper 1.21.11 cho thấy tại event: `valid=false`, `serverLookup=false`. Khi controlled server không có loaded chunk, event không bị cancel nhưng entity không được add. Giữ handle event làm async locator vì vậy không ổn định.

### Inventory physical identity

Paper 1.21.11 `CraftInventory#getItem(int)` gọi `CraftItemStack.asCraftMirror(NMS ItemStack)` mỗi lần. Hai lần đọc cùng slot tạo hai Bukkit wrapper khác nhau, nên `current == source` luôn sai. `javap` trên exact server JAR xác nhận `CraftItemStack` có public field `handle` trỏ NMS `ItemStack` vật lý.

## Bản sửa tối thiểu

- Entity publication chuyển sang Paper `EntityAddToWorldEvent`, resolve lại exact UUID sau event rồi mới reserve/write.
- Benchmark chỉ dùng chunk đang được player load tự nhiên; không force-load.
- Inventory source token dùng exact slot + SHA-256 digest + cùng NMS physical handle. Bukkit wrapper có thể đổi; item giống hệt thay vào slot có handle khác nên vẫn fail-closed.
- `ItemGuardAPI.trackPlayerItemAsync()` dùng cùng physical-handle policy; detached clone/different handle bị từ chối.
- Reserve failure được dispatch về main thread qua `TagPublicationTarget.preparationFailed()` thay vì bị im lặng.

## Regression tests

- `ItemListenerEventContractTest`: bắt buộc post-insertion handler, cấm `ItemSpawnEvent` path.
- `InventoryPhysicalSourcePolicyTest`: same handle/digest PASS; identical replacement different handle FAIL; digest change/missing handle FAIL.
- `AsyncTagPublicationCoordinatorTest`: reserve failure được báo trên main thread.
- `TagPublicationRepositoryTest`: stale publish không được materialize canonical rows.

## Verification

- Full build: `152` tests, 0 failures/errors/skips.
- Artifact: `f0ef15d0f2c765a3a84dbe9428673ff96cb79af12e4f0d122eff47f6cac4a712`.
- Final benchmark 100: dispatch `10.081 ms`, complete `100/100`, PDC ready `1437.969 ms`.
- Inventory run `4c63613c-98b9-4f9d-8a00-bde072d646b9`: PASS, code `SQRO58`, `PLAYER_SLOT → PUBLISHED`, snapshot v1.
- SOS run `74e68e4b-91e6-4cee-8dd1-9d2a47a2cd51`: PASS, exact item present nên persisted `DENIED/PLAYER_INVENTORY`.
- DB cuối: schema v6, `PREPARED=0`, tracked/snapshot counts bằng nhau.

## Còn lại

- Chưa crash-inject đúng cửa sổ physical-write/canonical-publish trên Paper.
- WorldGuard API thật, PlayerVaultsX/zAuctionHouse và issuance transaction vẫn chưa verified.
- Controlled Paper đóng ItemGuard/DB sạch nhưng JVM test vẫn treo ở Moonrise termination; chưa quy root cause cho ItemGuard.
