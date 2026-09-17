# Entity post-receipt source-loss — independent review

Ngày: 2026-08-25

## Final verdict

`VERDICT: PASS`

Reviewer: `cc/claude-opus-4-8`.

- Findings cuối: none.
- `PRODUCTION CHANGE REQUIRED: NO`.
- Scope: một controlled loaded ground-item entity, exact publication/UUID/PDC/SHA.
- Overall vẫn `NOT RELEASE READY`; production untouched.

## Review history phải giữ

### Vòng 1 — `FAIL`

Reviewer ban đầu báo claim transaction-window quá mạnh vì:

1. `removedAt=1787676312067` lớn hơn publication `updated_at=1787676311936` 131 ms, nên reviewer suy ra removal xảy ra sau commit.
2. Rollback-journal file là global, không tự mang transaction ID.
3. Reviewer dùng final `tracked=512` để suy ra reconciliation có thể short-circuit canonical-existing.
4. Bot live verify sau 8 giây vẫn thấy `journal-active`.

Các điểm 1 và 3 dựa trên giả định sai về source/timeline. Điểm 2 là challenge attribution hợp lệ và được giữ để correction review đánh giá lại.

Raw result:

`C:\Users\thanh\AppData\Local\Temp\itemguard_entity_post_receipt_review_result.txt`

### Correction evidence

#### `updated_at` không phải commit timestamp

`ItemSqliteRepository.updateTagPublicationState(...)` bind `updated_at` trong:

```text
UPDATE tag_publications
SET state = ?, updated_at = ?, detail = ?
...
```

Exact controlled trigger là `AFTER UPDATE OF state`, match:

- `OLD.state='PREPARED'`;
- `NEW.state='PUBLISHED'`;
- exact publication ID `ce5d274f-9279-4b5e-9439-c4f33a925a5d`.

Trigger chạy recursive `SELECT` 30,000,000 rows trước khi `statement.executeUpdate()` return và trước `SqliteConnectionOwner.runTransaction()` gọi `connection.commit()`. Vì vậy `removedAt > updated_at` không chứng minh post-commit.

#### Independent SQLite falsifier

Falsifier dùng:

- `journal_mode=DELETE`;
- `synchronous=FULL`;
- cùng `AFTER UPDATE` 30M trigger.

Observed:

- `updatedAt=1787677303176`;
- `journalObservedAt=1787677303177`;
- `updateFinishedAtJournalObservation=false`;
- external reader tại journal observation vẫn thấy `PREPARED / updatedAt=0`;
- `executeReturnedAt=1787677309615`;
- `commitReturnedAt=1787677309618`;
- final `PUBLISHED / updatedAt=1787677303176`.

Kết quả:

`ITEMGUARD_SQLITE_AFTER_TRIGGER_TIMELINE_FALSIFIER_PASS`

Điều này bác bỏ trực tiếp inference `markerTime > updated_at => transaction đã commit`.

Script:

`C:\Users\thanh\AppData\Local\Temp\itemguard_sqlite_after_trigger_timeline_falsifier.py`

#### Canonical short-circuit không phù hợp precondition

Phase-2 precondition/seal trước transaction:

- exact publication `PREPARED`;
- exact canonical `0`;
- exact snapshot `0`;
- restart tạo `IdentityReadinessCoordinator` mới với ready cache trống.

Entity load path gọi:

```text
ItemListener.onEntityAdded
→ ItemTrackingService.isEntityIdentityReady
→ reconcilePhysicalIdentity
→ new TagReconciliationReceipt
→ IdentityReadinessCoordinator.reconcile
→ ItemSqliteRepository.reconcileTagPublication
```

Repository canonical check lúc đó là zero, nên không thể short-circuit. Canonical được insert trong same uncommitted transaction trước exact state update/trigger; final `tracked=512` là post-commit state, không phải arm-time state.

#### Controlled attribution chain

Reviewer correction chấp nhận attribution trong exact fixture dựa trên toàn chuỗi:

1. no journal active tại arm;
2. single `SqliteConnectionOwner` + `SerialDatabaseExecutor` serialize one connection;
3. exact receipt transaction bắt đầu từ entity-load reconciliation;
4. only controlled long trigger match exact publication ID;
5. journal xuất hiện tại receipt clock + 131 ms và vẫn active sau 8 giây;
6. không có controlled long trigger khác;
7. exact UUID/PDC/SHA được revalidate ngay trước remove;
8. offline sau DB close thấy same publication `PUBLISHED`, canonical/snapshot exact digest.

Reviewer không nêu được alternative transaction cụ thể thỏa đồng thời no-journal-at-arm, khởi phát trong 131 ms và giữ journal active hơn 8 giây trên single serial owner.

### Vòng 2 correction — `PASS`

Reviewer chấp nhận:

- temporal finding vòng 1 sai vì coi `updated_at` là commit timestamp;
- journal-active sau 8 giây nhất quán với exact long trigger còn chạy;
- canonical-short-circuit finding sai vì dùng final state thay arm-time state;
- causal attribution đủ cho controlled fixture.

Raw correction result:

`C:\Users\thanh\AppData\Local\Temp\itemguard_entity_post_receipt_review_correction_result.txt`

## Evidence assessment

Reviewer xác nhận claim hẹp được hỗ trợ:

- durable point-in-time receipt;
- exact source loss trong uncommitted exact-trigger window;
- same publication `PUBLISHED`;
- canonical/snapshot exact SHA;
- source absent ngay sau remove và sau graceful restart;
- production change `NO`.

Build attribution:

- runtime candidate `189022b7…7e10`;
- clean build `1607082a…1dd7`;
- `331/331` non-manifest entries byte-identical.

Final evidence:

- `entity-post-receipt-final-evidence.json` SHA-256 `f79f6de6fb006d1997132fbb66c105d2401c9fbad0becba479093868d7e79c86`;
- archive manifest SHA-256 `f52e2eaafe95d6eca74bfbee9eee4e0289b1589fd6b2d7e458ff731fc5cd9c6e` (22 artifacts, gồm original FAIL, correction PASS và falsifier audit).

## Scope limits

- Chỉ một run token `206a2b2b...`, exact identity `B2L0LU/da116b55...`, publication `ce5d274f...`.
- Attribution dựa single serial SQLite owner + exact-only long trigger; không suy rộng sang nhiều long trigger hoặc arbitrary concurrency.
- Không chứng minh general anti-dupe correctness, entity unload/merge/stackable, container post-receipt, scale hoặc production.

## Evidence gaps được giữ

1. Không có raw transaction-ID/callback trace bind journal trực tiếp với publication transaction; attribution là causal inference từ unique long trigger + single owner.
2. Falsifier chứng minh `markerTime > updated_at` có thể vẫn uncommitted, nhưng không đo trực tiếp `commitReturnedAt` trong chính B2L0LU run.
3. Hai gap này được reviewer đánh giá là chấp nhận được cho controlled fixture, không phải direct binding hay production proof.

## Runtime report

`docs/runtime/2026-08-25-controlled-entity-post-receipt-source-loss.md`.
