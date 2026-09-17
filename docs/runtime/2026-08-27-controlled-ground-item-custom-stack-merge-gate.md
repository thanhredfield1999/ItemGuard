# Controlled ground-item custom-stack merge gate

Ngày: 2026-08-27

## Kết luận runtime

`VERIFIED CONTROLLED PAPER + FINAL EXACT OPUS 4.8 PASS`.

Authoritative run token: `371e9cc0-2bb7-4c7c-ad89-bab2ce25063f`.

Production chưa deploy/restart/verify. Project vẫn `NOT RELEASE READY`.

Final reviewer `cc/claude-opus-4-8`: `PASS`, blockers/fixes rỗng, production change required `NO`, release ready `NO`.

Final evidence archive: `E:\AI.WORK\backups\itemguard-ground-merge-371e9cc0-final-20260827-031108.tar.gz`, SHA-256 `5e7765f802c02e7520ac725b3e2aedcc28d3627f2cd92e0f9e6956567199a1be` (`34` files, read-back verified).

## Contract

Gate dùng hai cặp `MINECART x1` có `MAX_STACK_SIZE=2`:

- control không có ItemGuard identity phải merge thành đúng một entity `x2`;
- protected pair có cùng complete ItemGuard code/UUID phải không merge;
- LOWEST telemetry phải cho thấy protected event ban đầu chưa bị cancel, sau ItemGuard phải bị cancel;
- control event không bị cancel;
- exact survivor entity UUID phải giữ qua clean restart;
- DB, artifact và cleanup phải giữ invariants.

## Artifact

- runtime candidate: `5fc2512f57fb4c5d35e6fbac3a889b3263945340d790f9ccb0d26d05133b8e3c`;
- corrected probe: `0fd75b88368dd66f0b0294312a52404f5cd7ee340400da4f18b3ecf7ce495e03`;
- clean rebuild: `c558b38704b173c86991bac1e1bb5954943b3636fef62794f7ea6e9a66e6d2a7`;
- runtime/rebuild `343/343` non-manifest entries byte-identical, zero differing entries;
- `target/ItemGuard-1.0.0.jar` đã được khôi phục về exact runtime bytes.

## Failed fixture attempt và RCA

Token không-authoritative: `f03281a4-9711-4fa1-956d-9dae24fcce50`.

Pre-runtime exact Opus 4.8 đã PASS fixture cũ nhưng bỏ sót ba Paper sentinel. Runtime prepare RED bằng `ITEMGUARD_GROUND_MERGE_REJECTED merge-state`; zero production merge-event evidence nên không được dùng làm product proof.

Exact Paper 1.21.11 server bytecode chứng minh:

- `CraftItem.setCanPlayerPickup(false)` ghi `pickupDelay=32767`;
- `setUnlimitedLifetime(true)` và `setWillAge(false)` ghi `age=-32768`;
- `ItemEntity.isMergable()` từ chối `pickupDelay=32767` hoặc `age=-32768` trước `ItemMergeEvent`.

Attempt RED được seal `HARNESS_ONLY_MERGEABILITY_SENTINEL_PRECONDITION`, DB logical SHA không đổi, production handler chưa được exercise. Archive SHA-256: `be5684c48a3bace680a87fee283b1aa7da070ec7ef5747e5f282595b7fe9bc08`.

TDD correction làm contract RED bằng cách cấm cả ba sentinel và bắt precondition runtime `pickupDelay==100`, `canPlayerPickup==true`, `willAge==true`; sau correction contract GREEN và probe compile PASS. Exact correction Opus 4.8 PASS, blockers/fixes rỗng. World entity regions được restore riêng từ immutable pre-runtime backup SHA-256 `e0d25d51da4b64d2215b35afe710a40dd27dafa6c8742957cee00182f1533d54`; failed token/markers không được reuse.

## Prepare/event evidence

Control:

- event: `1`;
- initially cancelled: `0`;
- final cancelled: `0`;
- entities: `2 → 1`;
- survivor amount/max stack: `2/2`;
- survivor UUID `aa405958-dcad-4645-b8fd-98140200e6a8`, là một trong hai UUID spawn ban đầu.

Protected:

- events: `8`;
- initially cancelled: `0`;
- final cancelled: `8`;
- entities giữ `2` với amount `1/1`;
- exact UUID pair `6a011022-4152-499b-9ee9-488bc4a9cae6` và `c3f05264-c04f-4b60-97e3-dec98face734` giữ nguyên.

Control merge thật là oracle non-vacuous chứng minh cùng fixture/tick/window có mergeability và Paper merge-event path hoạt động.

## Restart

Sau clean stop/start:

- control survivor giữ exact UUID, amount `2`, max stack `2`;
- protected pair giữ exact hai UUID và amount `1/1`;
- không có merge event mới trong restart verification window;
- ItemGuard disable log có `Database connection closed`; Paper save hoàn tất `All dimensions are saved`.

## DB oracle

Baseline, prepare, precleanup và postcleanup đều:

- integrity `ok`;
- logical SHA-256 `944413c7d472f2a3af42ef42e998449ba18c0dc080529295de5dd9b13682f3e0`;
- file SHA-256 `86bf625f9eca20b9803971d8828f709c8c7b55304ec9a6ef0b2e688075de7eee`;
- exact zero delta cho tracked, snapshot, publication states, history, observation, finding, duplicate stat, claim và trigger.

## Cleanup

- removed entities: `3`;
- removed item count: `4`;
- location marker removed: `true`;
- postcleanup offline NBT sweep parse `4` entity-region files / `34` entity chunks;
- zero hit cho cả bốn spawned entity UUID, protected code và protected item UUID;
- ports không listen, clone session lock exclusive, DB đóng sạch.

## Verification

- fixture contract: RED trước correction, GREEN sau correction;
- probe Java 21 clean package: PASS;
- focused neighbouring suite: `24/24` PASS;
- full Java 21: `237/237` PASS, `BUILD SUCCESS`;
- `git diff --check`: PASS;
- prepare, precleanup và postcleanup offline seals: PASS;
- cleanup NBT absence: PASS;
- static added-line secret scan: no finding.

## Giới hạn

Gate chứng minh đúng custom-stack `MINECART` với identical components và `MAX_STACK_SIZE=2`. Nó không suy rộng sang native max-stack-1 identity item (Paper không coi hai `x1/max1` là mergeable), nhiều nguồn đồng thời, item-component mismatch, hopper/container merge semantics, multi-server/writer, crash/scale hoặc production. DB zero-delta được seal tại các điểm offline, không phải giám sát liên tục trong mọi tick.
