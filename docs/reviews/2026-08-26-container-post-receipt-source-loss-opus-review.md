# Container post-receipt source-loss — independent review

Ngày: 2026-08-26

## Verdict

- Reviewer: `cc/claude-opus-4-8`.
- `VERDICT: PASS`.
- `PRODUCTION CHANGE REQUIRED: NO`.
- Scope: một isolated single chest, loaded chunk, slot 3, exact publication/source/PDC/SHA.
- Overall: `NOT RELEASE READY`; production untouched.

## Claim được chấp nhận

Exact container reconciliation đã mint point-in-time durable receipt. Clone-only watcher xóa exact slot sau receipt trong exact delayed reconciliation window. Same publication commit `PUBLISHED`, canonical/snapshot giữ exact 235-byte SHA; source absent theo fresh live state, offline full-world NBT và restart journey. Hành vi không được mô tả thành atomic Bukkit+SQLite guarantee hoặc source-alive-at-commit.

## Findings

1. `updated_at=1787682868693` là thời điểm bind `UPDATE`, chạy trước exact `AFTER UPDATE` trigger và commit. `journalObservedAt/removedAt=1787682868723/8724` phù hợp transaction chưa commit; không được dùng `updated_at` làm commit timestamp.
2. Rollback journal là global. Attribution không trực tiếp/cryptographic, nhưng đủ cho exact controlled trial nhờ no-journal-at-arm, fresh ready cache + canonical0, one serial SQLite owner, only exact long trigger, exact PDC/SHA revalidation và offline same-publication result. Reviewer không tìm được alternative transaction phù hợp toàn evidence.
3. `player.openInventory` có thể fire `InventoryOpenEvent` trước watcher, nhưng item đã tagged nên `scanContainerInventory` skip first-tag. Watcher được đăng trước explicit `scanOpenBlockContainerInventory` receipt path; không thấy race/bypass.
4. Fixture không tái diễn stale `BlockState` incident: sau `world.save()` nó gọi fresh `resolveContainer -> getState()` rồi mới kiểm slot empty. Offline NBT là oracle độc lập.
5. Marker booleans được ghi literal sau runtime guards, nên verify marker có phần tautological; tuy vậy controller throw trước write nếu check fail, và offline NBT/restart bù đắp oracle.
6. Restart bot, DB close, dimensions save và NBT parse xác nhận source absent bền vững.

## Scope limits

- Chỉ một trial isolated single chest, slot 3, loaded chunk, sequential.
- Không suy rộng double chest, hopper/non-chest, unloaded/concurrent, relocation, stackable, repeated stress, scale hoặc production.
- `world.save()` và controller là fixture-only, không phải production hot-path.
- Chỉ chứng minh point-in-time receipt semantics, không chứng minh distributed transaction.

## Evidence gaps

- Không có transaction ID/log binding journal trực tiếp với exact publication; attribution là causal inference.
- Không có counter-run chứng minh fixture fail khi trigger vắng hoặc source vẫn present.
- Marker booleans không lưu raw runtime boolean ngoài throw guards.

Các gap này không chặn claim controlled hẹp, nhưng chặn mọi suy rộng release/production.

## Evidence được review

- Candidate: `189022b7b0e954d5e830896cf7ead09419fade85895d412073230df2d3767e10`.
- Probe: `6bbce8ea0a66787c378b81ca270196fb56b2f4ec13acd18f1df93a32d0a3569e`.
- Final evidence: `a16e66e7c6e9414c72d9e8e28f7f12d59454621af90b3da3f36b14685d174c23`.
- Review raw result: `C:\Users\thanh\AppData\Local\Temp\itemguard_container_post_receipt_review_result.txt`.
- Raw result SHA-256: `51caa4d2bd3eb5ca6ea162ab3f6d9e7fa0ae7946ebcd6b166d6b1538dbc6d2e8`.
- Archive SHA-256: `3537254cfdfe8377fa5240a75bbe31e4cd2849fa0a214d97f90d6166321141e3`.

Runtime report: `docs/runtime/2026-08-26-controlled-container-post-receipt-source-loss.md`.
