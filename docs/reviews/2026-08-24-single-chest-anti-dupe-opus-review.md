# Independent single-chest anti-dupe review

Ngày: 2026-08-24

## Reviewer và candidate

- Model exact: `cc/claude-opus-4-8` qua 9router trực tiếp.
- Candidate cuối: `0bb8d23b47eea7b10cb40c8db02a392922a924ef660e20b4154b3f2972f8c525`.
- Java 21 full: `53` suites / `181/181` PASS.
- Review không thay thế controlled Paper evidence.

## Vòng 1 — BLOCK

Reviewer yêu cầu đóng các blocker evidence:

1. Evidence bundle chưa chứa call site scheduling, nên chưa chứng minh Bukkit inventory/world access chạy sync main thread.
2. Source-text ordering test chỉ dùng `indexOf`, không chứng minh player→container contiguous; nếu có suspend/event xen giữa, một physical item move có thể tạo false-positive two rows.
3. Runtime oracle phải assert exact two rows, không chỉ finding locations/stats.
4. Baseline phải khóa zero target finding trong cooldown và clone gate phải bật true/NOTIFY trong test window.

## Follow-up

- `InventoryScanScheduler` pure seam chỉ expose `scheduleSync`; production adapter exact dùng Bukkit `runTaskTimer`, không async, và giữ cancellation handle.
- `ObservationEpochScanner` behavioral seam/test chứng minh với mọi source: player scan → container scan contiguous, rồi finalize sau tất cả source.
- `InventoryScanTask` dùng coordinator trên `Bukkit.getOnlinePlayers()`.
- Source-text ordering test brittle đã được thay bằng behavioral scheduler/coordinator tests; config gate source contract còn lại chỉ khóa guard-before-Bukkit-access.
- Runtime baseline khóa schema/cardinality, target findings 0, stats S0=0, issuance false.
- Runtime watcher bắt buộc đúng two rows only: exact PLAYER holder/slot 8 và exact CONTAINER block holder/slot 3, cùng epoch/code/UUID/complete.
- Probe hard-guard isolated single chest, non-stackable amount 1, exact open inventory và no player actions giữa scans.

## Vòng 2 — PASS

Verdict: `PASS`, không blocker.

Reviewer xác nhận trong scope:

- synchronous server-thread scheduling và contiguous player→container→finalize;
- stable single-block holder ID;
- virtual/no-location/non-block holder fail-closed;
- config gate trước Bukkit access;
- unique physical key dedup khi nhiều players cùng mở một chest;
- mixed PLAYER+CONTAINER audit/idempotency/cooldown;
- canonical exact code+UUID join;
- bundled anti-dupe false, issuance false, NOTIFY-only gate.

## LOW / boundary

- Double chest chưa fail-closed trong production code; holder/slot stability qua split/reconfigure chưa verified. Scope bắt buộc single chest.
- Stackable identity split sang two slots có thể bị xem là duplicate; default `track-stackable=false`, runtime dùng diamond sword amount 1.
- First encounter sau restart có thể miss một epoch vì `isIdentityReady` warm-up fail-closed; runtime oracle chờ finding epoch thực, không giả định epoch đầu.
- Container scan cost tuyến tính online players nhưng bounded per top inventory, không force-load/full-world scan.
- Config guard test còn source-text; không phải blocker, nhưng có thể tách pure container eligibility policy về sau.

## Runtime follow-through

Controlled runtime sau review PASS:

- atomic exact PLAYER slot 8 + single-chest slot 3 same epoch;
- one `CONFIRMED/NOTIFY`, stats +1;
- same serialized physical bytes sau 75 giây;
- stop/restart giữ both copies và durable cooldown;
- no publication/reclaim/issuance/quarantine mutation;
- clone gate trả false và server offline.

Runtime report: `docs/runtime/2026-08-24-controlled-player-single-chest-multi-copy-anti-dupe.md`.

Overall vẫn `NOT RELEASE READY`.
