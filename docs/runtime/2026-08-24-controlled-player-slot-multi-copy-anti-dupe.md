# Controlled Paper player-slot multi-copy anti-dupe

Ngày: 2026-08-24

## Kết luận

`VERIFIED CONTROLLED PAPER` cho đúng scope hai bản sao vật lý của cùng exact identity trong hai player inventory slots của một account controlled.

Candidate ItemGuard:

- SHA-256: `5864c2a15e9de6b3c5b16c2f7672f1a26ea504b03e02c5f4637823e2a2d74f34`.
- Java 21 full build: `175/175` tests PASS.
- Independent `cc/claude-opus-4-8` review vòng 2: `PASS`, không blocker.

Không suy rộng evidence này sang container, external storage, destructive quarantine, issuance hoặc production.

## Root cause trước slice

`InventoryScanTask` ghi exact observation ledger rồi complete epoch, nhưng không có production consumer tạo duplicate finding/audit. Vì vậy hai physical copies có thể được ghi ledger mà không tạo finding hoặc staff notification.

## Thay đổi đã kiểm

- Schema v7 thêm durable `duplicate_findings` và unique `(item_uuid, scan_epoch)`.
- Complete epoch + finding insert + stats increment + old-epoch cleanup nằm trong một serial SQLite transaction.
- Finding insert dùng set-based `INSERT OR IGNORE ... SELECT ... RETURNING`; tất cả findings được persist, callback/report main-thread giới hạn 64 mỗi epoch.
- Durable cooldown theo prior finding; retry cùng epoch và epoch trong cooldown không tăng finding/stats.
- `ScanEpochGenerator` seed từ max persisted observation/finding epoch để giữ strict monotonicity qua restart/clock rollback.
- Missing `anti-dupe.enabled` fail-closed về `false`.
- Destructive release gate hard-closed; configured destructive action downgrade về `NOTIFY`.
- DB completion callback được marshal về Paper main thread trước staff notification.

## Controlled setup

- Clone: `E:\AI.WORK\itemguard-paper-smoke`.
- Paper port: `57484`.
- Full backup trước test: `E:\AI.WORK\backups\itemguard-paper-smoke-multicopy-20260824-134325`.
- Invalid evidence run được quarantine: `E:\AI.WORK\backups\itemguard-paper-smoke-multicopy-invalid-oracle-20260824-135959`.
- Probe final SHA-256: `88beac7f298ef00ebe1f5343b34c91fc56495e5cbc6c16832ad886aad5102d04`.
- Clone-only config: `anti-dupe.enabled=true`, `action=NOTIFY`, cooldown `600000 ms`.
- `reclaim.issuance-enabled=false`.
- Fixture: code `NZ1GMT`, UUID `c9fe8bd1-a7d6-4827-b839-49d1c22425bf`.
- Account UUID: `71fcf6be-e1ee-3c92-9a42-893cbe44818c`.
- Physical slots: `5` và `8`.

## Migration evidence

Trước deploy:

- schema `6`;
- integrity `ok`;
- tracked/snapshot `503/503`;
- publications `342`, `PREPARED=0`;
- reclaim claims `9`, `PENDING=0`;
- duplicate stats `0`, observations `0`.

Sau startup exact candidate:

- schema `7`;
- integrity `ok`;
- tracked/snapshot/publication/claim cardinality giữ nguyên;
- `duplicate_findings` tồn tại;
- `idx_duplicate_finding_identity_epoch` là unique index;
- findings/stats vẫn `0/0` trước fixture.

## Physical fixture evidence

Clone-only probe clone raw `ItemStack` từ slot 8 sang slot 5, set amount 1 và gọi `Player.saveData()`.

Prepare marker:

- exact code + UUID;
- slots `[5,8]`;
- serialized item SHA-256 `3449737bcf7a3033fab56e142d7060fdcf70571a7e88fb4476a1b340493ae954`.

Sau 75 giây và nhiều scheduled epochs, verify marker giữ cùng serialized SHA-256 và exact slots `[5,8]`. Kết luận: `NOTIFY` không remove/mutate hai physical copies.

## Atomic production-scan evidence

Read-only SQLite watcher được arm trước bot. Watcher chỉ mở URI `mode=ro`, polling 100 ms; không trigger, INSERT, UPDATE hoặc DELETE.

Capture lúc `1787555240665`, khoảng 70 ms sau finding commit:

- finding epoch: `1787555240591`;
- status: `CONFIRMED`;
- action: `NOTIFY`;
- distinct locations: `2`;
- stats: `duplicates_detected=1`;
- observation 1: holder `PLAYER`, đúng account UUID, slot `5`, exact code+UUID, `epoch_complete=1`;
- observation 2: holder `PLAYER`, đúng account UUID, slot `8`, exact code+UUID, `epoch_complete=1`.

Capture raw đã lưu tại `docs/runtime/.multicopy-atomic-capture.json`; hậu kiểm tổng hợp tại `docs/runtime/.multicopy-final.json`.

## Durable postconditions

Final verifier PASS:

- đúng một durable finding cho exact UUID;
- stats tăng đúng `0 -> 1`, không tăng lại trong cooldown qua nhiều epochs;
- canonical item đúng một row;
- snapshot đúng một row;
- tracked/snapshot vẫn `503/503`;
- publications vẫn `342`, `PREPARED=0`;
- claims vẫn `9`, `PENDING=0`;
- issuance tắt;
- integrity `ok`;
- deployed candidate hash khớp exact build hash;
- two physical copies vẫn exact và không bị mutate.

Observation rows của finding epoch sau đó được old-epoch cleanup theo thiết kế; atomic read-only capture bảo tồn exact rows trước cleanup.

## Attempt bị loại

1. Harness attempt đầu gửi args cho command implementation yêu cầu zero args; command không mutate, marker không tạo, DB chỉ có observation slot 8. Run này bị loại.
2. Run tiếp theo tạo finding đúng nhưng DB read-back diễn ra sau old-epoch cleanup nên không còn observation rows của finding epoch. Dù bot/log/finding đúng, run bị loại khỏi proof và clone được rollback wholesale trước final run.

Chỉ final atomic-capture run được dùng làm runtime evidence.

## Boundary còn mở

- Block/open-container multi-copy và crash windows.
- Multi-player/external PlayerVaults/zAuctionHouse true absence lookup.
- Source mất trước reconciliation.
- Destructive quarantine/removal.
- Issuance transaction/item grant.
- Graceful Paper JVM process exit: ItemGuard DB/world save và port close PASS; Moonrise wrapper vẫn cần terminate sau port close.
- Production deploy/restart.

Overall project vẫn `NOT RELEASE READY`.
