# Controlled natural container-break gate — inconclusive

Ngày: 2026-08-29

## Kết luận

`INCONCLUSIVE_FIXTURE_ARCHITECTURE_BLOCKED` cho natural player break của block container trên Paper `1.21.11-131`.

Không có product verdict, không xác nhận defect production, không có authoritative stopped SQLite/NBT transition, không chạy restart/cleanup journey và không mở release/deploy gate. Overall vẫn `NOT RELEASE READY`.

Exact candidate giữ nguyên: `2e1fb7d8717a02b316bef9c017d09d67b8705c694519433276bd6e316d5adc5e`. Clone-only probe: `f26fb36a0d878ccc62205a1f6d02e164e397cf12345f6c7d8bee649467bd466a`.

## Mục tiêu gate

- Real Mineflayer player dùng vanilla survival dig phá exact chest trong pre-write reserve.
- `BlockBreakEvent`, `BlockDropItemEvent` và entity-add path phải non-vacuous.
- Old block-source proposal phải `ABORTED/SOURCE_CHANGED`.
- Dropped content phải nhận fresh canonical identity đúng policy, không mất/nhân đôi/mint sai identity.
- Stopped SQLite/NBT reconstruction, restart, validation-before-mutation cleanup và unconditional restore phải PASS.

## Pre-runtime verification

Immutable bundle cuối: `review-bundle-attempt-5`.

- Manifest SHA-256: `b30074699200d32ca4f24e17aff02b63bdd3e9ff67f9cca7d0b82e8297b950cb`.
- Exact set: `37` manifest entries / `38` files including `manifest.json`, zero symlink/extra/missing.
- Static contracts: `67/67 PASS`.
- Fixture contracts: `21/21 PASS`.
- Node syntax: PASS.
- Extra-file negative test: fail-closed PASS.
- Independent fallback review (không phải Claude): literal `PASS_FOR_CONTROLLED_PAPER`.

Review PASS chỉ cho phép chạy controlled Paper; không phải runtime/product verdict.

## Runtime attempts

### Attempt 1 — non-authoritative startup harness failure

`EntitySourceLossFixtureController` legacy yêu cầu system property lúc enable; phase runner chưa bind token. Probe fail trước bot/break event. Restore exact baseline PASS.

### Attempt 2 — non-authoritative Mineflayer compatibility failure

Probe và `/prepare` chạy, nhưng `prismarine-block` ném `TypeError: enchantments is not iterable` khi tính dig time cho enchanted axe. Lỗi xảy ra trước `BlockBreakEvent`. Restore exact baseline PASS.

### Attempt 3 — non-authoritative client chunk-stream race

Server teleport actor tới remote fixture và gửi `NOW`, nhưng Mineflayer chưa nhận chunk; `blockAt` trả `undefined`, không có dig/break/drop event. Restore exact baseline PASS.

### Attempt 4 — inconclusive architecture boundary

- Probe enable, `/prepare`, `PREPARE_ARMED`, `NOW` và client-side exact-chest readiness đều qua.
- Final result marker không xuất hiện sau `180` giây.
- Fixture không persist receipt riêng cho `DIG_STARTED`, `DIG_RESOLVED`, `BlockBreakEvent`, `BlockDropItemEvent`, entity-add hoặc fresh-identity completion.
- Vì vậy evidence không phân biệt được Mineflayer dig treo trước `BlockBreakEvent` với product path không hoàn tất sau event.
- Paper shutdown sạch: `Database connection closed`, `All dimensions are saved`.
- Outer restore PASS; independent stopped check: DB byte/logical exact baseline, integrity `ok`, JAR hashes exact, zero trigger/sidecar, controlled ports closed.

Không được dùng missing final marker để kết luận product PASS hoặc defect.

## Root cause của blocker

Fixture gộp nhiều boundary bất đồng bộ vào một final marker. Khi marker thiếu, không có phase telemetry độc lập để định vị failure. Sau ba hardening harness liên tiếp, tiếp tục vá cùng kiến trúc sẽ vi phạm rule-of-three và tăng nguy cơ false verdict.

## Thiết kế bắt buộc trước rerun

1. Persist bounded receipts độc lập cho client chest ready, dig start và dig resolve/failure.
2. Persist append-only receipts riêng cho `BlockBreakEvent`, `BlockDropItemEvent`, entity-add, old proposal completion và fresh identity completion.
3. Mỗi receipt gắn exact run token, actor UUID, block/entity identity, timestamp/tick và predecessor hash.
4. Stopped verifier tái dựng state machine từ receipts + raw SQLite/NBT; không tin final marker hoặc pipeline exit code.
5. Review immutable bundle mới trước Paper; dùng namespace runtime mới.
6. Không sửa production nếu chưa có RED chứng minh defect production.

## Evidence

- Root: `E:/AI.WORK/evidence/itemguard-natural-container-break-20260829`.
- Final verdict: `final-verdict.json`.
- Attempt classifications: `attempt-1..4/classification.json`.
- Final reviewed bundle: `review-bundle-attempt-5`.
- Review receipt: `review-attempt-5-pass.json`.

## Boundary

Không commit/push/deploy/restart production. Natural break/place/physics, crash/tombstone/relocation, density/performance, Folia, multi-server và production vẫn mở. Các gate chunk-unload, exact-byte block replacement và logical double-chest chunk-border đã PASS trước đó giữ nguyên; không rerun hoặc hạ cấp chúng.
