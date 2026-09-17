# Review release-critical độc lập — 2026-08-23

## Phạm vi và model

- Requested model: `cc/claude-opus-4-8`.
- Kết quả request exact identifier: API `404`, 0 token; model không khả dụng qua Claude Code account hiện tại.
- Review thực tế: Claude Code alias `opus`, `modelUsage.canonicalModel = claude-opus-5`.
- Chế độ: read-only dossier, không tool, không sửa file.
- Dossier: `E:\AI.WORK\itemguard-paper-smoke\opus-review-dossier.txt`.
- Lưu ý: dossier phản ánh source trước central mutation-gate fix và trước full build 125/125.

## Kết luận reviewer

Reviewer giữ release gate đóng và nêu hai BLOCKER:

1. `B1` — first-tag SQLite transaction chạy đồng bộ từ Paper main thread; `future.get()` không timeout; chưa có benchmark/WAL/busy timeout.
2. `B2` — tagged clone bị bỏ ở sibling mutation paths, có nguy cơ orphan identity và ghi DB lặp.

Reviewer đồng thời nêu các HIGH: WorldGuard reflection/API chưa verified, gate không bao phủ sibling paths, ownership bị rewrite theo holder gần nhất, thiếu index history/owner, migration claim cần đối chiếu, `/matdo` chưa có cooldown/anti-replay semantic, code collision chưa retry, thiếu Bukkit-facing tests.

Reviewer xác nhận không tìm thấy đường issuance: không `PREPARED/COMMITTED`, không add/drop/delete/deserialize cấp item; durable-first ordering và identity rebind protection đúng hướng.

## Trạng thái sau review

- `B2`: đã sửa sau dossier. Central `tagItem()` gate yêu cầu world context; mọi caller chỉ writeback khi identity `COMPLETE`; drag không persist clone bị bỏ; block/container scan write exact slot; craft/check/pickup/spawn fail-closed khi tagging bị deny.
- `H3` sibling WorldGuard/world gate: đã sửa cùng central mutation boundary.
- Regression policy test RED→GREEN: `TrackingMutationAccessPolicyTest`.
- Full verification sau fix: `./mvnw.cmd clean test package --no-transfer-progress` — 125/125 GREEN.
- `B1`: vẫn OPEN. Chưa có benchmark Paper workload, timeout design hoặc bằng chứng performance; release gate tiếp tục `NOT RELEASE READY`.
- WorldGuard API runtime, external adapters, code collision retry, ownership semantics và cooldown vẫn OPEN.

## Evidence level

- Unit/build: verified 125 tests sau B2/H3 fix.
- Controlled Paper post-fix: artifact SHA `b84fa2…3f5d` đạt `Done (18.103s)`; run `df1e75e3-...` PASS 7/7 và DB chỉ có 1 row+snapshot sau nhiều scan; run `407db28a-...` PASS 5/5 exact presence denial, zero `PREPARED/COMMITTED`.
- Production: không deploy/không verified.

## Focused re-review GPT-5.5

- Batch: `deleg_29eb2f22`; read-only, không sửa/deploy production.
- Baseline reviewer đọc: artifact SHA `5c1435…8739`, 122 tests; vì vậy mọi finding đã được đối chiếu lại với source/artifact post-fix `b84fa2…3f5d`, 125 tests.
- Xác nhận còn hiệu lực:
  - durable ordering đúng: snapshot capture trước atomic identity+snapshot transaction; exception trả item gốc;
  - `/finditem` đưa DB work khỏi Bukkit main thread và chỉ render message sau main-thread handoff;
  - `PRAGMA foreign_keys=ON` bật trước schema/mọi operation trên single owner connection;
  - WorldGuard mặc định disabled và unavailable/query error deny, nhưng reflection contract chưa verified nên IG-R009 vẫn HIGH mở.
- Finding caller set kết quả `tagItem()` mà không kiểm identity đã lỗi thời sau central mutation-boundary fix: pickup/spawn/container/craft/check và bounded scan hiện chỉ writeback khi identity `COMPLETE`. Controlled Paper post-fix run `df1e75e3-...` tạo đúng 1 row+snapshot; run `407db28a-...` chứng minh exact PDC hiện diện trên item vật lý.
- `tagItem()` trả chính item khi identity đã `COMPLETE` là idempotent existing-identity path, không phải nhánh phát hành identity mới. Các caller tạo identity mới chỉ coi thành công khi `hasCode/COMPLETE`; không nâng thành release finding.
