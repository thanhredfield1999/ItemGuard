# Controlled Paper player + single-chest multi-copy anti-dupe

Ngày: 2026-08-24

## Kết luận

`VERIFIED CONTROLLED PAPER` cho scope hẹp: một exact non-stackable identity xuất hiện đồng thời tại player inventory slot 8 và isolated single chest slot 3 của một account controlled.

- Candidate SHA-256: `0bb8d23b47eea7b10cb40c8db02a392922a924ef660e20b4154b3f2972f8c525`.
- Java 21 full build: `53` suites / `181/181` tests PASS.
- Focused container/anti-dupe: `36/36` PASS; scheduler/coordinator follow-up `18/18` PASS.
- Independent `cc/claude-opus-4-8` review vòng 1 `BLOCK`; sau behavioral scheduling/ordering seam, vòng 2 `PASS`, không blocker.
- Probe SHA-256: `467ede3756c5d5a5d4385653a3078acff7250228d9364735d9703d6f02d821ef`.

Không suy rộng sang double chest, stackable identities, multi-player/external storage, destructive action hoặc production.

## Root cause

`InventoryScanTask` trước slice chỉ ghi completed observations từ player inventory. `ContainerListener` chỉ request identity cho item chưa tagged khi mở container; exact tagged item trong container không được record vào scheduled epoch. Vì vậy player + chest copies không thể đi qua production detector.

## Source slice

- `ContainerInventoryObservationFactory`: stable physical holder ID `BLOCK:<worldUuid>:x:y:z`, slot exact.
- `ItemTrackingService.scanOpenBlockContainerInventory`: chỉ scan top inventory đang mở khi holder là Bukkit block `Container`, location/world hợp lệ, identity `COMPLETE` + ready; virtual/no-location inventory fail-closed; config gate trước Bukkit access.
- `ObservationEpochScanner`: behavioral seam thực thi player → container contiguous cho từng player rồi mới finalize.
- `InventoryScanScheduler`: chỉ có synchronous scheduling boundary; production adapter dùng Bukkit `runTaskTimer`, giữ cancel handle cho shutdown.
- Persistence generic audit xác minh mixed `PLAYER + CONTAINER` tạo đúng một durable finding, stats +1 và retry idempotent.
- Không force-load/chunk/world scan; chỉ top inventory của online player.

## Baseline và migration

Backup trước container work:

- `E:\AI.WORK\backups\itemguard-paper-smoke-pre-container-20260824-150452`.

Controlled clone được restore từ baseline pre-multi-copy:

- `E:\AI.WORK\backups\itemguard-paper-smoke-multicopy-20260824-134325`.

Pre-start read-only baseline:

- schema 6, integrity `ok`;
- tracked/snapshot `503/503`;
- publications `342`, `PREPARED=0`;
- claims `9`, `PENDING=0`;
- target canonical/snapshot `1/1`;
- no `duplicate_findings` table, target findings `0`, duplicate stats `0`;
- bundled/clone gate false, issuance false, container scan true.

Post-start migration:

- schema 7, integrity `ok`;
- cardinalities giữ nguyên;
- unique `idx_duplicate_finding_identity_epoch` tồn tại;
- target findings `0`, observations `0`, stats `0` trước fixture.

## Controlled fixture

- Code: `NZ1GMT`.
- UUID: `c9fe8bd1-a7d6-4827-b839-49d1c22425bf`.
- Player: `71fcf6be-e1ee-3c92-9a42-893cbe44818c`.
- Player slot: `8`.
- Single chest: world UUID `34486cdf-24f0-4ade-9f92-f820b9605e20`, block `(-7,-60,10)`, slot `3`.
- Holder ID: `BLOCK:34486cdf-24f0-4ade-9f92-f820b9605e20:-7:-60:10`.
- Serialized SHA-256 của cả hai copies: `3449737bcf7a3033fab56e142d7060fdcf70571a7e88fb4476a1b340493ae954`.

Probe chỉ chọn AIR block trong naturally loaded chunk và bốn horizontal neighbors không phải chest/trapped chest. Nó xóa stale player slot 5, clone raw exact item slot 8 sang chest slot 3, mở exact chest và giữ player không thao tác inventory.

Clone-only runtime config: `anti-dupe.enabled=true`, `action=NOTIFY`, cooldown `600000 ms`, container scan true; issuance false. Gate được trả false offline sau test.

## Atomic production evidence

Read-only SQLite watcher (`mode=ro`, polling 100 ms) được arm trước fixture. Capture lúc `1787559194350`, khoảng 83 ms sau finding commit:

- finding epoch `1787559194263`;
- exact `PLAYER`, holder exact player UUID, slot 8, complete=1;
- exact `CONTAINER`, holder exact block ID, slot 3, complete=1;
- cùng exact code+UUID;
- đúng 2 rows only;
- one `CONFIRMED/NOTIFY`, `distinct_locations=2`, detail exact;
- stats `0→1`.

Raw evidence:

- `docs/runtime/.container-baseline.json`;
- `docs/runtime/.container-atomic-capture.json`;
- `docs/runtime/.container-final.json`;
- `docs/runtime/.container-restart-final.json`.

## Physical và restart evidence

Sau 75 giây/multiple epochs:

- exact chest vẫn mở;
- player slot 8 và chest slot 3 còn amount 1;
- same code+UUID+serialized hash;
- finding vẫn đúng 1, stats vẫn 1 trong cooldown;
- canonical/snapshot/publication/claims không đổi;
- no issuance/quarantine/removal mutation.

Controlled graceful stop ghi `Database connection closed`, all dimensions saved, ports nhả. Moonrise wrapper vẫn treo và phải terminate sau port close; graceful JVM exit chưa verified.

Sau restart exact candidate:

- Paper `Done (18.521s)`;
- probe locate đúng loaded single chest block, không force-load;
- player slot 8 + chest slot 3 sống qua disk reload với same exact serialized hash;
- chest được mở lại và giữ qua một scheduled epoch;
- durable cooldown qua restart giữ finding count 1 và stats 1;
- DB integrity/cardinalities/no-pending invariants giữ nguyên.

## Boundary còn mở

- Double chest semantics không verified và production path chưa explicitly reject double chest.
- Stackable identities/split stacks không verified; default tracking stackable vẫn false.
- Multi-player/external storage journeys.
- Container crash windows và source-disappears-before-reconcile.
- Destructive quarantine/removal.
- External absence proof, issuance transaction/item grant.
- Graceful Paper JVM exit và production.

Overall vẫn `NOT RELEASE READY`.
