# Same-identity double-chest independent review

Ngày: 2026-08-26

## Review chain

1. Initial pre-runtime exact `cc/claude-opus-4-8`: `BLOCK`.
   - fixture bind overload không tồn tại;
   - direct request path bypass resolver;
   - source key/oracle chưa executable;
   - cooldown proof chưa positive.
2. Correction:
   - xóa direct request/reflection;
   - dùng real `InventoryOpenEvent` production path;
   - tách publication và detection thành hai startup;
   - thêm baseline, publication seal, atomic watcher, positive restart cooldown watcher và final sealer.
3. Một correction-review response thiếu required output schema; helper `AssertionError`; không phải reviewer verdict.
4. Valid correction review qua approved `ag/claude-opus-4-6-thinking`: `PASS`, blockers none, required fixes none, production change required `NO`.
5. Final review qua approved `ag/claude-opus-4-6-thinking` fallback: `PASS`, required fixes none, production change `NO`.
   - exact Opus 4.8 attempts không trả usable required schema; không được tính là reviewer verdict;
   - reviewer đối chiếu exact DB deltas, left-only publication attribution, two physical observation keys, finding cardinality, positive cooldown epoch, restart/cleanup và config restore;
   - không tìm thấy false attribution hoặc required production fix trong scope hẹp.

Initial BLOCK được giữ nguyên trong audit chain, không reclassify thành product failure. Schema/response harness failures cũng không được gọi là `BLOCK` hoặc `PASS`.

## Evidence classification

- Source/resolver fix: đã verified ở gate double-chest physical-source trước.
- Current gate: controlled runtime anti-dupe semantics cho same identity/two physical chest halves.
- Production: chưa deploy/restart/verify.

## Residual limits

- hopper transfer;
- unopened block-level scanner journey;
- chunk unload mid-scan;
- multi-writer/server;
- scale/destructive/production.

Project vẫn `NOT RELEASE READY`.
