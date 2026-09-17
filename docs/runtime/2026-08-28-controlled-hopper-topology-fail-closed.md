# Controlled Paper — Hopper topology fail-closed

Ngày: 2026-08-28

## Kết luận

`VERIFIED` cho exact candidate `0fa1e247c405cb5022e4cfedcbcc037ba61134bf4af008579460e0f63a592327` trên Paper `1.21.11-131`, Java 21, controlled clone `E:/AI.WORK/itemguard-paper-smoke`:

- block-container topology gồm real double chest physical halves và block hopper tiếp tục cho phép tagged-ready transfer;
- fresh eligible untagged item bị cancel trước rồi publication source scan tạo đúng một identity;
- mọi transfer có một đầu là `HopperMinecart`, `StorageMinecart` hoặc virtual inventory không ánh xạ được block container đều fail-closed cho tracked/eligible item;
- item ngoài tracking scope vẫn được phép;
- hai negative minecart phases không tạo DB/history/observation mutation;
- exact identity giữ qua clean restart và cleanup; controlled clone đã restore operational allowlist byte-exact.

Overall vẫn `NOT RELEASE READY`; production không deploy/restart/verify.

## Source correction và TDD

Root cause trước fix: `ContainerListener` áp policy generic cho `InventoryMoveItemEvent`, nhưng publication resolver chỉ có physical source key cho Bukkit block `Container`. Hopper/storage minecart là entity `InventoryHolder`, nên tagged-ready item có thể được cho phép rời vào topology không thể publication/reconcile vật lý.

Correction nhỏ nhất:

- `BlockContainerPhysicalSlotResolver.supports(Inventory)` dùng cùng physical resolver hiện hữu;
- `HopperTransferPolicy` nhận source/destination support flags;
- tracked hoặc eligible item bị `CANCEL` nếu một trong hai đầu unsupported;
- `CANCEL_AND_SCAN_SOURCE` chỉ còn reachable khi cả hai đầu là supported block-container topology;
- double chest chỉ supported khi cả hai physical halves resolve được location.

RED artifacts:

- `itemguard-hopper-topology-policy-red.log`;
- `itemguard-hopper-topology-wiring-red.log`.

GREEN gates:

- focused hopper/double-chest topology `21/21`;
- full Java 21 `288/288`;
- `git diff --check` PASS;
- exact Opus 4.8 verdict `PASS_FOR_CONTROLLED_PAPER`, blockers rỗng.

## Authoritative runtime

Authoritative attempt: `attempt-3`, token `3d34a4ae-0875-427f-934c-5648a55237f9`.

Artifact attribution:

- ItemGuard: `0fa1e247c405cb5022e4cfedcbcc037ba61134bf4af008579460e0f63a592327`;
- probe: `664f1f9405d55a6d91714f2b92b67ee270b8f8aa7705dbf49d8da5f1eecb945b`.

Positive journey:

1. fresh `DIAMOND_SWORD` ở right physical half/local slot `3` của source double chest;
2. real source hopper event: untagged attempt `1`, cancelled `1`;
3. retry sau publication: tagged source ALLOW `1`;
4. real block hopper → destination double chest: tagged destination ALLOW `1`;
5. publication/canonical/snapshot delta `+1/+1/+1`, exact PUBLISHED source key:
   `BLOCK_CONTAINER_SLOT:<world>:-2:-56:2:3`;
6. exact identity `CV4COL/cf892b6b-4a1b-45a5-bc9b-dc53c3aeb513`, serialized SHA-256 `f59275fee1dd1ef7d82081166ae101548c6a22d78a5a48f7c491e9219ca89be9`.

Adapter checks trên Paper:

- virtual inventory: null holder, null location;
- eligible item bị cancel, ineligible `DIRT` được phép;
- code-only partial identity bị cancel trên supported và virtual topology;
- real `StorageMinecart` holder class `CraftMinecartChest`, không phải block `Container`, event bị cancel;
- real `HopperMinecart` holder không phải block `Container`.

Negative journeys:

- block chest → HopperMinecart: fresh eligible `1/1` cancel, tagged `1/1` cancel;
- HopperMinecart → block hopper: fresh eligible `1/1` cancel, tagged `1/1` cancel;
- cả hai phase giữ item ở source, destination rỗng;
- DB counts và logical SHA-256 giữ exact post-positive state `e2d3ad6f2b55cc73b4eab066b93d1a0a9310cd7672f17cbed4329eda11ce4123`;
- history/observations/findings/stats/claims/prepared/aborted/triggers zero-delta.

Restart/cleanup:

- exact code/UUID/serialized SHA và destination physical half/local slot giữ qua clean restart;
- cleanup removed đúng `1` item và `5` fixture blocks;
- mỗi phase có `Database connection closed` và `All dimensions are saved`;
- ports `57485/36104/57486` đóng;
- restore receipt xác nhận baseline JAR/probe/DB byte hash, DB logical hash/integrity và sidecar/WAL/SHM/journal absent.

## Audit chain

Hai attempt trước không phải technical verdict:

- attempt 1: `HARNESS_CONFIGURATION_FAILURE`, composite probe thiếu historical constructor token; command không chạy; restore PASS;
- attempt 2: `HARNESS_GEOMETRY_PRECONDITION_FAILURE`, flat-world AIR search từ chối trước fixture creation; restore PASS.

Hai attempt được giữ nguyên với `technicalVerdict=NOT_RUN`; không dùng làm evidence cho production behavior.

Independent verifier: `C:/Users/thanh/AppData/Local/Temp/verify_itemguard_hopper_topology_evidence.py` PASS.

## Không được suy rộng

Chưa verified:

- entity/minecart-container persistence hoặc publication support;
- double-hopper concurrency;
- chunk-unload mid-transfer;
- unopened container scanning;
- custom inventory có accessor ném exception;
- hopper-density/TPS scale;
- multi-server/shared filesystem;
- production.

Các topology entity/virtual hiện chỉ được hỗ trợ theo contract fail-closed, không phải gameplay transfer support.

Evidence root: `E:/AI.WORK/evidence/itemguard-hopper-topology-20260828/`.

Sealed archive external receipt: `E:/AI.WORK/evidence/itemguard-hopper-topology-20260828.tar.gz`, SHA-256 `65e8cee7b99a752b3fad7dcf364c7470da2f5cfd7f5b77a1cd38f864421cd935`, `77` files; archive reader đã verify toàn bộ internal `SHA256SUMS.json`. Receipt này được ghi sau seal nên không nằm trong chính archive.
