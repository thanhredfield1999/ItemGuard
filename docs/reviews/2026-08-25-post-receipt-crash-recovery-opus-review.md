# Post-receipt unclean-stop recovery — independent review

Ngày: 2026-08-25

## Verdict

- Reviewer: `cc/claude-opus-4-8` qua local 9router.
- `VERDICT: PASS`.
- Findings: none.
- `PRODUCTION CHANGE REQUIRED: NO`.

## Scope reviewer nhận

Scope hẹp exact player slot:

- physical source mất và được `Player.saveData()` sau valid receipt;
- exact SQLite reconciliation transaction đang trong controlled delayed window;
- Java child dừng không sạch;
- restart phải giữ same publication `PREPARED`, canonical/snapshot `0/0`;
- exact same-player/same-slot bytes restore phải tạo đúng một canonical/snapshot và chuyển same publication sang `PUBLISHED`;
- không suy rộng sang production, entity/container, relocation, concurrency hoặc scale.

## Evidence reviewer đối chiếu

- ItemGuard candidate SHA:
  `189022b7b0e954d5e830896cf7ead09419fade85895d412073230df2d3767e10`.
- Probe SHA:
  `486d2743e83810e5daa81fa033e09d8f8825b1b52db61d0eff057af57c5128c8`.
- Stop evidence SHA:
  `742cc8adc0536a5c8356dd892b9d09f44651d8fdfc3899ae2536d9aebd4165b1`.
- Negative evidence SHA:
  `de650fa5c88e9e9536b4cf9baabaaa40ef544c5f8a659420bed6eacb9ef31bd5`.
- Final evidence SHA:
  `728c7c8caf5d4874b16f31e53cb4b8ffbd4fd4a390bbe7e030ede3c063c6c5dc`.
- SQLite: schema 7, journal mode `delete`, integrity `ok`.
- Exact fixture publication:
  `c96b7113-635f-4708-b1bb-b16ff30f604d`.
- Physical/snapshot digest:
  `db62fc481756b963cddf03e32e7e0847ea816dcc1d2363934803f0f7655866df`.

Reviewer cũng nhận production source cho receipt, coordinator, SQLite owner,
repository transaction và player scan, cùng clone-only fixture controller.

## Reviewer assessment

Reviewer xác nhận:

1. raw DB/journal/playerdata không đổi qua exact stop checkpoint;
2. restart recovery giữ publication `PREPARED`, canonical/snapshot `0/0`, integrity `ok`;
3. source vẫn vắng và reclaim fail-closed, không issuance;
4. exact same-slot restore chuyển same publication sang `PUBLISHED`;
5. đúng một canonical và một snapshot 231 bytes có digest bằng physical bytes;
6. production state-CAS, digest equality và transaction commit/rollback enforce invariant trong scope;
7. không tìm được canonical/snapshot leak, double-publish hoặc publish với bytes khác.

## Caveat về journal evidence

Reviewer mô tả zero header như dấu hiệu journal transaction window. Báo cáo runtime
không dùng zero header riêng lẻ làm proof: tám byte đầu đều zero cả bản raw
before/after stop. Claim target-window dựa trên tổng hợp:

- exact delayed trigger cho đúng publication ID;
- marker thấy journal tồn tại trước removal và sau `Player.saveData()`;
- stop cách removal 68 ms;
- raw DB/journal/playerdata hash giữ nguyên qua stop;
- restart trả đúng `PREPARED/canonical0/snapshot0`.

Vì vậy verdict được giữ nhưng claim không mạnh hơn artifacts thực tế.

## Residual risks reviewer giữ mở

- Chỉ một fixture/run token và một điểm timing 68 ms.
- Không stress nhiều lần hoặc lấy mẫu toàn dải transaction timing.
- Recovery engine trace là gián tiếp qua exact pre/post artifacts và DB state.
- Không bao phủ nhiều slot/người chơi đồng thời.
- Không bao phủ entity/container post-receipt, relocation hoặc production.

## Source

Raw review output:
`C:\Users\thanh\AppData\Local\Temp\itemguard_post_receipt_crash_review_result.txt`.

Runtime report:
`docs/runtime/2026-08-25-controlled-post-receipt-crash-recovery.md`.
