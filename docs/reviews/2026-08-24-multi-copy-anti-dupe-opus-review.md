# Independent multi-copy anti-dupe review

Ngày: 2026-08-24

## Reviewer

- Model exact: `cc/claude-opus-4-8` qua 9router trực tiếp.
- Review độc lập source, tests, migration và controlled runtime oracle.
- Review không thay thế Paper runtime evidence.

## Candidate cuối

- SHA-256: `5864c2a15e9de6b3c5b16c2f7672f1a26ea504b03e02c5f4637823e2a2d74f34`.
- Java 21 full build: `175/175` PASS.
- Focused epoch/repository/schema/finalizer gate: `22/22` PASS trước full build.

## Review history

### Vòng 1 — BLOCK

Hai blocker evidence/design được chỉ ra:

1. `ScanEpochGenerator` chưa chứng minh strict monotonic qua restart/clock rollback trong evidence set, trong khi old-epoch cleanup giả định epoch tăng.
2. Runtime oracle chỉ chờ finding có thể false-pass/false-fail nếu identity cache chưa warm hoặc observation rows đã cleanup; cần bắt đúng two exact rows của finding epoch.

Suggestion quan trọng: loại N+1/unbounded per-finding insert loop.

### Cách đóng blocker

- Generator seed từ max persisted epoch lấy từ cả `item_observations` và `duplicate_findings`; test reopen DB + clock rollback PASS.
- Runtime oracle bắt buộc finding epoch có đúng two rows: holder `PLAYER`, exact account, exact code+UUID, slots `5/8`, `epoch_complete=1`.
- Read-only watcher được arm trước fixture, capture transaction khoảng 70 ms sau commit và trước cleanup.
- N+1 được thay bằng set-based `INSERT OR IGNORE ... SELECT ... RETURNING`; audit bền toàn bộ, report callbacks giới hạn 64.
- Durable cooldown, retry idempotency và missing config fail-closed có regression.

### Vòng 2 — PASS

Verdict exact: `PASS`, không blocker.

Reviewer xác nhận trong scope:

- migration v6→v7 no-loss và future-version fail-closed;
- unique finding epoch + `INSERT OR IGNORE` idempotency;
- durable cooldown boundary;
- persisted epoch floor restart-safe;
- serial ordering record → complete/audit → cleanup;
- canonical exact code+UUID join chặn mismatch false-positive;
- async DB completion marshal main thread;
- destructive action hard-downgrade `NOTIFY`;
- không issuance/removal/quarantine path.

## LOW / boundary

- Epoch đầu sau clone có thể skip vì identity-ready cache chưa warm; runtime oracle phải verify observation rows, không suy từ một scheduled tick.
- Cột `distinct_locations` thực tế đếm unique observation rows; với unique `(epoch, holder, slot, uuid)` và two slots trong journey này, value `2` đúng scope.
- Branch cooldown lớn hơn epoch-millis là fail-closed false-negative và không thực tế với production epoch time, nhưng vẫn ngoài runtime proof.
- Callback scheduling failure sau disable chưa có controlled Paper injection riêng.

## Runtime follow-through

Final controlled journey sau review đã PASS với exact candidate:

- atomic read-only capture đúng finding + slots `5/8` cùng epoch;
- stats tăng đúng một;
- physical verify sau 75 giây giữ two exact copies và serialized hash;
- canonical/snapshot/publication/claims không đổi;
- issuance tắt.

Runtime report: `docs/runtime/2026-08-24-controlled-player-slot-multi-copy-anti-dupe.md`.

Overall vẫn `NOT RELEASE READY`; PASS chỉ áp dụng player-slot multi-copy `NOTIFY` trong controlled clone.
