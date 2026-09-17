# Post-receipt source-loss — independent review

Ngày: 2026-08-25

## Verdict

`VERDICT: PASS`

Reviewer: `ag/claude-opus-4-6-thinking`, fallback theo routing sau khi
`cc/claude-opus-4-8` trả HTTP 429.

- Findings: none.
- `PRODUCTION CHANGE REQUIRED: NO`.
- Hành vi được phân loại là expected point-in-time semantics, không phải defect.

## Evidence assessment

Reviewer xác nhận controlled evidence đủ cho scope exact player slot:

1. Baseline clone offline, candidate SHA `189022b7…7e10`, schema 7,
   integrity `ok`, trigger/PREPARED/active claims `0/0/0`.
2. First-tag tạo exact one `PREPARED`; canonical/snapshot `0/0`.
3. Receipt match code, item UUID, `PLAYER_SLOT` source key và tagged digest.
4. `SqliteConnectionOwner` dùng one serial executor/one connection,
   `journal_mode=DELETE`, `synchronous=FULL`, explicit commit/rollback.
5. Exact-publication `AFTER UPDATE PREPARED -> PUBLISHED` delay trigger giữ
   reconcile transaction mở. Probe yêu cầu rollback journal tồn tại với size > 0
   trước removal và vẫn tồn tại sau `Player.saveData()` slot trống.
6. Same publication commit `PUBLISHED`; canonical/snapshot exact SHA, totals +1.
7. Offline NBT sau graceful stop/restart không còn slot/code/UUID; zero durable
   observation sau `removedAt`.
8. Trigger gỡ offline, `PREPARED=0`, DB integrity `ok`, ports đóng.
9. Hai reclaim attempts đều `DENIED ... absence cannot be proven`, active claims
   `0`, anti-dupe/issuance false; không issuance.
10. Production untouched.

## Semantics assessment

`TagReconciliationReceipt` là physical proof point-in-time. Sau khi receipt hợp
lệ, SQLite transaction atomically materialize canonical + snapshot + PUBLISHED.
Bukkit inventory và SQLite không có distributed transaction, nên source có thể
mất sau receipt nhưng trước commit. Canonical publication vẫn commit và ghi lại
trạng thái đã được xác minh tại receipt time.

Reviewer kết luận hành vi này không tạo duplicate, không issuance và không phải
data-loss defect. Observation subsystem đúng khi không ghi presence sau removal;
reclaim tiếp tục fail-closed khi không chứng minh được absence ở external stores.

## Residual risks

- Crash/force-kill sau removal nhưng trước DB commit: `NOT VERIFIED`.
- Entity/container/relocation variant: `NOT VERIFIED`.
- Multi-player, scale/concurrency/backpressure: `NOT VERIFIED`.
- External absence proof và issuance: vẫn gate đóng.
- Production: `NOT VERIFIED / untouched`.

## Recommended wording

> Post-receipt source loss dùng point-in-time semantics. Receipt chứng minh exact
> physical identity tại một locator ở thời điểm mint receipt. Nếu source mất sau
> đó nhưng trước SQLite commit, canonical publication vẫn commit atomically. Điều
> này không phải atomic Bukkit+DB guarantee và không chứng minh source còn tồn tại
> tại commit time.

Runtime report:
`docs/runtime/2026-08-25-controlled-post-receipt-source-loss.md`.

Full reviewer output được giữ tại:
`C:\Users\thanh\AppData\Local\Temp\itemguard_post_receipt_review_result.txt`.
