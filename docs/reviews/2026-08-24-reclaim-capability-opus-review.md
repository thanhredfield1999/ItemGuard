# Review reclaim capability độc lập — 2026-08-24

## Phạm vi

- Model: exact `cc/claude-opus-4-8`, gọi trực tiếp qua 9router.
- Artifact: `target/ItemGuard-1.0.0.jar`, SHA-256
  `9f96899bcdd94f406266c96f8a2003e70958b95ac413655ab18afd45e7465c41`.
- Evidence đầu vào: Java 21 clean package `47` suites / `167` tests; focused
  reclaim/factory `16/16`; source gate/evaluator/factory/command/tests/config và
  current-state/risk/research docs.
- Chế độ read-only; không deploy, restart hoặc sửa runtime.

## Verdict

`PASS` — không có blocker trong scope.

Reviewer xác nhận:

- `ERROR` ưu tiên `PRESENT` trong classification và blocking-evidence ordering;
- zAuctionHouse V3/V4/fallback đều `UNAVAILABLE`, không có nhánh `ABSENT`;
- lookup version `RuntimeException`/null xảy ra trong supplier được evaluator đổi
  thành `DENIED_ERROR/CAPABILITY_SETUP`;
- cả nhánh eligible và non-eligible đều đi tới `persistDenial`; không có item grant;
- không tìm được accidental issuance hoặc exception escape thuộc contract hiện tại.

## Lưu ý không blocking

- Fatal JVM `Error` ngoài `LinkageError` không được bắt; không nên biến
  `OutOfMemoryError`/`StackOverflowError` thành lỗi nghiệp vụ tùy tiện.
- Version chuỗi chính xác `"3"`/`"4"` dùng fallback reason chung nhưng vẫn
  `UNAVAILABLE`.
- Reviewer yêu cầu đối chiếu claim recovery ngoài source pack. Đối chiếu sau review
  xác nhận `ReclaimClaimService.deny()` dùng CAS `PENDING -> DENIED`;
  `DatabaseManager` gọi startup recovery; repository chỉ recover `PENDING` sang
  `DENIED` và giữ `PREPARED` lock. `ReclaimClaimServiceTest` và
  `ReclaimClaimRepositoryTest.startupRecoveryDeniesPendingButPreservesPreparedLock`
  đang xanh trong full build. Đây là source/integration evidence, không phải
  controlled Paper evidence cho candidate hiện tại.

## Boundary còn giữ

- Candidate hiện tại chưa controlled-Paper runtime riêng.
- Crash injection physical-write/publish, multi-copy anti-dupe, external adapter
  thật, issuance transaction, graceful Paper exit và production vẫn chưa verified.