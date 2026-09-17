# Controlled Paper crash recovery — physical write trước canonical publish

Ngày: 2026-08-24

## Kết luận

`VERIFIED controlled Paper` cho đúng cửa sổ publication của candidate
`9f96899bcdd94f406266c96f8a2003e70958b95ac413655ab18afd45e7465c41`:
physical PDC đã được ghi và player data đã save, canonical transaction chưa commit,
Paper bị force-kill trực tiếp, startup giữ journal fail-closed, rồi exact physical
code+UUID được reconcile thành đúng một canonical item + snapshot khi player
reconnect. Không cấp item trùng và không để claim active.

Đây không phải production evidence và không xác minh multi-copy anti-dupe,
external adapter, issuance transaction hoặc graceful JVM process exit.

## Môi trường và artifact

- Workspace controlled: `E:\AI.WORK\itemguard-paper-smoke`.
- Backup trước test: `E:\AI.WORK\backups\itemguard-paper-smoke-20260824-100450`.
- Paper `1.21.11-131`, Java `21.0.4`, port Minecraft `57484`, query `36103`.
- Paper SHA-256: `6c099540fccd27e10750d1adcb382f1552fb5aa2816aeaf2cc1efb23ddd9e3df`.
- ItemGuard SHA-256: `9f96899bcdd94f406266c96f8a2003e70958b95ac413655ab18afd45e7465c41`.
- Clone-only smoke probe SHA-256: `78463b53c2905a4f16cdf648d5f4ea521c46ee42ece6635f9bdb56a66058c086`.
- `reclaim.issuance-enabled: false` được đọc trực tiếp từ config clone.
- Baseline DB: integrity `ok`; publication `341`; `PREPARED=0`;
  canonical/snapshot `502/502`; claim `8`, tất cả `DENIED`.

## Injection harness

Không thêm crash hook vào ItemGuard production artifact. Harness chỉ tồn tại trong
controlled clone:

1. Smoke probe xóa inventory của dedicated offline test account, đặt đúng một
   `DIAMOND_SWORD` ở slot 8 và đợi ItemGuard ghi PDC.
2. Probe chỉ ghi marker sau `Player.saveData()` với exact code+UUID.
3. Một SQLite trigger controlled-only trì hoãn transition `PREPARED -> PUBLISHED`.
4. Watcher xác minh PID vẫn là Java 21 `paper.jar` và đang giữ port `57484`, rồi
   `taskkill /F` trực tiếp PID listener đó.
5. Trigger được gỡ offline trước restart.

Một attempt đầu bị loại khỏi evidence: tool chỉ kill bash/PTY wrapper, Java child
vẫn sống và publish xong. Attempt này được quarantine tại
`E:\AI.WORK\backups\itemguard-paper-smoke-failed-wrapper-kill-20260824-1018`;
clone sau đó được rollback full backup trước attempt hợp lệ.

## Evidence attempt hợp lệ

Fixture:

- code: `NZ1GMT`;
- item UUID: `c9fe8bd1-a7d6-4827-b839-49d1c22425bf`;
- source: `PLAYER_SLOT:71fcf6be-e1ee-3c92-9a42-893cbe44818c:8`;
- direct Paper PID: `39100`;
- marker physical saved: `1787547328297`.

Sau direct force-kill, trước restart:

- port `57484/36103` đều đóng;
- log không có `Stopping`, `Disabling ItemGuard` hoặc `Database connection closed`;
- `PRAGMA integrity_check = ok`;
- đúng một publication cho identity, state `PREPARED`;
- canonical rows `0`, snapshot rows `0`;
- publication totals: `ABORTED=6`, `PREPARED=1`, `PUBLISHED=335`;
- reclaim claims vẫn `DENIED=8`, không trạng thái khác.

## Restart và reconciliation

1. Gỡ trigger offline; DB vẫn `PREPARED`, canonical/snapshot `0/0`.
2. Start Paper không có bot; sau `Done (21.807s)` và thêm 3 giây, DB vẫn
   `PREPARED`, canonical/snapshot `0/0`. Startup không publish mù.
3. Reconnect cùng dedicated account. Probe đọc slot 8 và xác minh exact
   `NZ1GMT/c9fe8bd1-a7d6-4827-b839-49d1c22425bf` còn tồn tại vật lý.
4. Join inventory scan gọi exact reconcile; `/matdo sos NZ1GMT` bị từ chối
   `PLAYER_INVENTORY - Canonical identity is present in inventory or ender chest`.

Hậu điều kiện cuối:

- `PRAGMA integrity_check = ok`;
- đúng một publication, state `PUBLISHED`;
- đúng một canonical row cùng code+UUID;
- đúng một snapshot v1, payload `232` bytes, SHA-256 `32` bytes;
- publication totals: `ABORTED=6`, `PUBLISHED=336`, `PREPARED=0`;
- reclaim totals: `DENIED=9`; active claim cho code (`PENDING/PREPARED/COMMITTED`) = `0`;
- trigger controlled-only = `0`;
- exact physical identity sống qua crash/restart; không có identity thứ hai.

Focused Java 21 verification sau runtime:

```text
./mvnw.cmd -Dtest=AsyncTagPublicationCoordinatorTest,TagPublicationRepositoryTest test --no-transfer-progress
Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## Shutdown boundary

Sau recovery journey, `/stop` làm ItemGuard log `Database connection closed`,
Paper lưu toàn bộ dimension/RegionFile I/O và nhả port. JVM vẫn treo ở
`MoonriseCommon` worker-pool termination và wrapper phải bị terminate. Vì vậy
`graceful Paper process exit` vẫn `NOT VERIFIED`; không quy root cause cho
ItemGuard từ evidence này.

## Release boundary còn mở

- Multi-copy anti-dupe journey: `NOT VERIFIED`.
- External PlayerVaults/zAuctionHouse absence lookup thật: `NOT VERIFIED`.
- Issuance transaction và item grant: `DISABLED / NOT VERIFIED`.
- Production deploy/restart: `NOT PERFORMED / NOT VERIFIED`.
- Overall: `NOT RELEASE READY`.
