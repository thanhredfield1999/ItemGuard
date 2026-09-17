# Ground-item custom-stack merge — final review

Ngày: 2026-08-27

## Kết luận

Exact reviewer: `cc/claude-opus-4-8`.

- verdict: `PASS`;
- blockers: `[]`;
- required fixes: `[]`;
- production change required: `NO`;
- release ready: `NO`.

Reviewer chỉ phê duyệt exact controlled gate `MINECART x1 + x1`, identical components, `MAX_STACK_SIZE=2` trên Paper `1.21.11`; không suy rộng sang release hoặc production.

## Evidence được review

Authoritative run token: `371e9cc0-2bb7-4c7c-ad89-bab2ce25063f`.

- control: event `1`, initially cancelled `0`, final cancelled `0`, hai entity merge thành một `x2/max2`;
- protected: event `8`, initially cancelled `0`, final cancelled `8`, exact hai entity `x1` không merge;
- control survivor UUID và protected UUID pair giữ nguyên qua clean restart;
- DB logical/file SHA và toàn bộ domain counts giữ exact baseline qua prepare/restart/cleanup;
- cleanup removed `3` entities / item count `4`; offline NBT sweep `4` region files / `34` entity chunks có zero exact UUID/code/item UUID hit;
- Java 21 focused `24/24`, full `237/237`, `git diff --check` PASS;
- runtime/rebuild có `343/343` non-manifest JAR entries byte-identical.

## Audit chain

Reviewer nhận cả attempt đầu không-authoritative `f03281a4-...`:

- pre-runtime review cũ đã bỏ sót fixture sentinel;
- runtime RED `merge-state`, zero product event evidence;
- Paper bytecode RCA chứng minh `pickupDelay=32767` / `age=-32768` làm `isMergable()` trả false trước event;
- attempt được seal `HARNESS_ONLY_MERGEABILITY_SENTINEL_PRECONDITION` và archive riêng;
- TDD contract RED cấm ba sentinel, correction GREEN;
- exact correction review PASS;
- world entity regions được restore từ immutable pre-runtime backup, marker/token cũ không được reuse.

Final review xác nhận không cần thay đổi production candidate cho gate này.

## Residual limits

- custom `MINECART` max stack `2`, không phải native max-stack-1 item;
- số attempt protected không có multiplicity contract ngoài việc mọi attempt quan sát được đều bị cancel;
- DB zero-delta được seal tại các điểm offline, không giám sát liên tục từng tick;
- Paper source/target event semantics được lấy từ server chạy thật, không re-derive toàn bộ final bytecode bundle;
- finite pickup delay dựa thêm vào fixed offset và exact UUID/amount assertions để chặn false-GREEN;
- multi-source/concurrent, component mismatch, hopper/container, multi-writer, crash/scale, external và production vẫn chưa verified.
