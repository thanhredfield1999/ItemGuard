# Double-chest physical-source independent review

Ngày: 2026-08-26

## Review chain

1. Pre-runtime exact `cc/claude-opus-4-8`: `BLOCK`.
   - scanner có thể publish trước click;
   - thiếu server-side proof hai click events;
   - readiness marker một mình không chứng minh DB attribution.
2. Correction:
   - click phase bắt buộc scanner-disabled từ plugin startup;
   - LOWEST server listener ghi click khi item còn untagged;
   - exact count `1/1`;
   - ready-before-click và click lặp fail-closed;
   - click publication và observation scanner được tách thành hai startup.
3. Correction review exact `cc/claude-opus-4-8`: `PASS`, blockers none.
4. Final review exact `cc/claude-opus-4-8`: `PASS`, required fixes none, production change required `NO` cho narrow gate.

Initial BLOCK được giữ nguyên, không reclassify thành model/platform failure.

## Final reviewer conclusions

- raw3 → left/local3; raw30 → right/local3 đúng.
- resolver dùng chung cho click, `scanContainer(Block)`, InventoryOpen first-publication và scheduled observation.
- click attribution không thể được scanner thay thế trong click phase.
- exact DB deltas/SHA/source/timestamps và phase separation hợp lệ.
- atomic completed epoch có đúng hai physical `CONTAINER` holders, no finding đúng semantics.
- restart/cleanup/config restoration hợp lệ.
- không có required production fix thêm cho scope hẹp.

## Advisory đã đóng

Reviewer đề nghị pin source-level equivalence giữa fixture gate và production config key. `DoubleChestPhysicalSourceWiringContractTest.containerScanGateUsesControlledRuntimeConfigurationKey` khóa `ConfigManager.isContainerScanEnabled()` vào `performance.container-scan-enabled`.

## Residual limits

- same exact identity trong một double chest;
- hopper transfer;
- chunk unload mid-scan;
- multi-writer/server;
- scale và production.

Project vẫn `NOT RELEASE READY`; production `NOT DEPLOYED / NOT VERIFIED`.
