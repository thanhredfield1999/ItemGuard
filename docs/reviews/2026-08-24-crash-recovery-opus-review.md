# Review crash recovery độc lập — 2026-08-24

## Phạm vi

- Model: exact `cc/claude-opus-4-8`, gọi trực tiếp qua 9router.
- Artifact runtime: `target/ItemGuard-1.0.0.jar`, SHA-256
  `9f96899bcdd94f406266c96f8a2003e70958b95ac413655ab18afd45e7465c41`.
- Paper `1.21.11-131`, Java `21.0.4`.
- Evidence: source publication/repository/reconcile/listener, focused publication
  `12/12`, full Java 21 `167/167`, và
  `docs/runtime/2026-08-24-physical-write-canonical-publish-crash-recovery.md`.
- Mục tiêu: tìm phản ví dụ cho physical-write trước canonical-publish crash window,
  atomicity, startup/reconcile, duplicate identity và claim/issuance hậu điều kiện.

## Verdict

`PASS` — không có blocker trong đúng scope controlled single player-slot crash recovery.

Reviewer xác nhận:

- call order là revalidate → physical write → canonical publish submit;
- attempt wrapper-kill đầu đã bị loại và rollback đúng; attempt hợp lệ kill trực tiếp
  Java child đang giữ port, không có graceful shutdown;
- sau kill DB `PREPARED`, canonical/snapshot `0/0`; startup không publish mù;
- reconcile yêu cầu exact `code + item_uuid + PREPARED` từ PDC vật lý;
- canonical item, snapshot và journal transition nằm trong một SQLite transaction,
  lỗi CAS rollback toàn transaction;
- reconnect tạo đúng một publication/canonical/snapshot, không cấp item thứ hai;
  `/matdo sos` persist `DENIED/PLAYER_INVENTORY`, không active claim;
- SQLite trigger controlled-only chỉ kéo dài cùng production transaction path và
  đã được gỡ offline trước recovery, nên không đổi atomicity.

## Findings không blocking

1. `LOW`: nếu physical write xong nhưng publish submission ném và item bị hủy
   trước khi reconcile chạy, canonical có thể không tồn tại. Fail-closed nhưng
   scenario source-disappears này ngoài scope vừa verified.
2. `LOW`: generator code 6 ký tự không retry collision atomically; đã nằm ở
   `IG-R014`, cần xử lý trước multi-copy/release.
3. `LOW`: trigger SQLite chỉ mở rộng timing window; không phải production config.
4. `SUGGESTION`: ghi rõ `readyIdentities`/`reconciliationInFlight` là cache RAM;
   restart xóa cache và exact PDC observation kích hoạt reconcile lại.

## Scope verdict

- `VERIFIED controlled`: physical PDC + playerdata sống qua force crash; journal
  giữ `PREPARED` fail-closed; startup không publish mù; exact reconnect reconcile
  thành đúng một canonical item + snapshot, không duplicate/active claim.
- `NOT VERIFIED`: block/open-container crash windows, source mất trước reconcile,
  multi-copy anti-dupe, external adapters, issuance transaction/item grant,
  graceful Paper process exit, production deploy/restart.
- Overall vẫn `NOT RELEASE READY`.
