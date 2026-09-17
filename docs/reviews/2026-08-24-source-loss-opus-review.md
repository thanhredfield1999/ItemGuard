# Independent source-loss reconciliation review

Ngày: 2026-08-24

## Kết luận

`VERDICT: PASS` ở vòng 4 cho exact candidate:

- model: `cc/claude-opus-4-8` qua 9router trực tiếp;
- SHA-256: `189022b7b0e954d5e830896cf7ead09419fade85895d412073230df2d3767e10`;
- Java 21: `58` suites / `201/201` PASS;
- focused final gate: `21/21` PASS;
- `git diff --check`: PASS.

PASS này chỉ áp dụng source/review scope. Runtime source-loss được ghi riêng tại
`docs/runtime/2026-08-24-controlled-player-slot-source-loss-reconciliation.md`.
Production vẫn chưa deploy hoặc verify.

## Review history

### Vòng quota/transport

- Các request đầu trả HTTP `429`; bounded wrapper 6 attempts cũng 429.
- Một non-stream request sau quota reset kết thúc `502` sau gần 10 phút.
- Đây là transport/account failures, không phải verdict.
- Harness sau đó dùng SSE streaming, exact model, một request mỗi vòng và hash-pin.

### Vòng 1 — BLOCK

Hai HIGH:

1. Single-flight key chỉ theo `code:UUID` có thể làm exact receipt bị bỏ khi wrong
   receipt đang in-flight; test synchronous cũ không chứng minh async behavior.
2. Strict source key làm source relocation không có recovery path.

TDD response:

- thêm async RED cho wrong-in-flight/exact receipt;
- thử explicit relocation authority có code+UUID+same tagged digest và physical
  destination locator.

### Vòng 2 — BLOCK

Reviewer bác relocation authority: same tagged bytes ở locator mới không chứng minh
cùng physical object liên tục; duplicated bytes có thể publish PREPARED identity.

Response:

- xóa toàn bộ relocation authority/API/SQL;
- giữ contract gốc exact source key fail-closed;
- source move/relocation recovery được ghi ngoài verified scope;
- thêm regression same tagged bytes ở source khác vẫn deny.

### Vòng 3 — BLOCK do evidence bundle + một liveness finding

- Reviewer không nhận đủ `createPublication`, snapshot codec và DB owner nên chưa
  thể chứng minh tagged-vs-pre-tag digest và transaction serialization.
- Reviewer phát hiện player inventory reconcile sau restart bị chặn nhầm bởi
  `container-scan-enabled`.

TDD/source response:

- full-receipt single-flight key gồm code+UUID+sourceKey+digest; wrong/exact
  receipts độc lập, exact duplicates coalesce;
- dispatcher failure không mark ready;
- player inventory anchored scan không phụ thuộc container gate;
- block-container scan vẫn giữ direct fail-closed guard trước Bukkit access;
- bundle bổ sung codec, publication ordering, serial DB owner/executor, join scan và
  exact tests.

### Vòng 4 — PASS

Reviewer xác nhận:

- detached readiness cache-only, không blind-publish;
- relocation/same-tag other locator fail-closed;
- stored publication `sha256` và receipt đều dùng tagged `serializeAsBytes()`;
  pre-tag `source_digest` là field riêng chỉ dùng revalidation trước physical write;
- one-connection serial transaction, commit/rollback và exact source/digest gate;
- full-receipt single-flight không làm exact receipt starvation;
- player scan vẫn active khi container scanning disabled.

## Final scope

`VERIFIED source/unit/review`:

- PREPARED reconcile cần exact code, UUID, stable source key và tagged SHA-256;
- wrong source/digest, same bytes ở locator khác, virtual/ambiguous path deny;
- detached `ItemStack` không mint receipt hoặc submit reconciliation;
- readiness chỉ cache sau durable success callback;
- full-receipt in-flight coalescing không overwrite exact receipt.

`NOT VERIFIED / residual`:

- source biến mất sau valid receipt observation nhưng trước SQLite commit;
- legitimate relocation recovery;
- mass entity-load/backpressure and transient in-flight memory bound;
- entity/block-container source-loss crash journeys;
- production.

## Reviewer non-blocking notes

- burst nhiều distinct physical receipts có thể làm transient in-flight set lớn;
- entity chunk-load có thể tạo nhiều anchored reconcile requests;
- tagged-byte determinism cần controlled Paper oracle.

Controlled runtime sau review đã chứng minh tagged physical SHA khớp publication và
snapshot SHA; xem runtime report. Các scale/backpressure notes vẫn open.
