# Block-container source-loss — independent review

Ngày: 2026-08-25

## Verdict

`VERDICT: PASS` — review cuối bằng `ag/claude-opus-4-6-thinking` không tìm
thấy blocker cho claim controlled hẹp của isolated single chest.

Candidate được review:

- ItemGuard SHA-256:
  `189022b7b0e954d5e830896cf7ead09419fade85895d412073230df2d3767e10`.
- Clone-only probe SHA-256:
  `09ac3963d37bec3518d7ec4ba2e068596286ef14ebf22b578567b599472b0af7`.
- Paper `1.21.11-131`, Java `21.0.4`.
- Java 21 trước runtime: `58` suites / `201/201` PASS.
- Production untouched; overall `NOT RELEASE READY`.

## Findings

Reviewer chủ động tìm phản ví dụ ở ba nhóm và kết luận `none`:

1. Negative continuity không forge được từ artifacts đã đưa:
   - prestart seal yêu cầu ports đóng, exact region hash manifest, same
     publication `PREPARED`, canonical/snapshot `0/0`, trigger `0`;
   - negative verifier độc lập đọc lại cùng postcondition;
   - source call path chỉ reconcile/publish khi exact physical item có đủ tags
     tại locator và vượt exact receipt checks.
2. Positive attribution gắn được với scheduled container scan:
   - restore ghi exact bytes, save world, fresh read-back exact hash rồi mở chest;
   - `InventoryScanTask` gọi block-container scan;
   - durable `CONTAINER` observation chỉ được ghi sau reconcile thành công;
   - `observed_at`, `scan_epoch`, publication `updated_at` đều sau `restoredAt`;
   - offline NBT sau graceful stop thấy đúng một restored item ở exact slot/PDC.
3. Hash continuity không drift:
   - physical/marker/publication/snapshot cùng SHA-256
     `1c5a8301...6b736b`;
   - candidate/probe khớp exact hash;
   - final log ordering chứng minh run token -> startup -> restore -> present ->
     stop -> DB closed -> dimensions saved.

## Supported claim

Cho đúng scope một non-stackable item trong isolated single chest tại stable
`BLOCK_CONTAINER_SLOT` (world UUID + x/y/z + slot 3), trên controlled clone:

- source removal giữ publication `PREPARED`, canonical/snapshot `0/0`, active
  reclaim `0`, no issuance;
- exact same-slot byte restore chuyển same publication sang `PUBLISHED`, tạo
  canonical +1 và snapshot +1 với exact matching SHA;
- durable exact `CONTAINER` observation xuất hiện sau `restoredAt`;
- offline NBT sau graceful stop xác nhận đúng một item với exact code/UUID.

## Remaining scope

Review không chứng minh và không mở gate cho:

- atomic Bukkit + SQLite;
- crash/source-loss sau valid receipt;
- relocation sang slot/container/world khác;
- double chest, stackable hoặc non-chest container;
- unloaded/force-loaded chunk và concurrent multi-player access;
- destructive action, issuance, reclaim execution hoặc anti-dupe enforcement;
- schema migration/reload/version upgrade;
- production hoặc general release readiness.

Raw result:
`C:\Users\thanh\AppData\Local\Temp\itemguard_container_source_loss_final_review_result.txt`.
Runtime evidence:
`docs/runtime/2026-08-25-controlled-block-container-source-loss-reconciliation.md`.
