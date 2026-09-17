# Controlled natural container break — factual client load PASS, fresh identity inconclusive

Ngày: 2026-08-30

## Kết luận

`NOT_ISSUED / CLIENT_LOAD_AND_NATURAL_BREAK_PATH_VERIFIED_FRESH_IDENTITY_SEAM_INCONCLUSIVE`.

Controlled `attempt-12` đã đóng factual client-load/Paper packet seam của
`attempt-11`: exact `player_loaded` được gửi, Paper phát
`PlayerClientLoadedWorldEvent(timeout=false)`, JDI đi qua `CLIENT_LOADED_PASS`
và `ServerPlayerGameMode.handleBlockBreakAction`. Bukkit tiếp tục phát factual
`PlayerInteractEvent`, `BlockDamageEvent`, `BlockBreakEvent`, drop và
`EntityAddToWorldEvent` cho đúng dropped content entity.

Full journey vẫn FAIL: entity được thêm vào world ở trạng thái untagged nhưng
fixture không quan sát được fresh identity sau 59.889 giây. Evidence hiện tại
không phân biệt được missed listener/publication, entity loss sau add, hay một
post-add seam khác. Vì chưa có terminal entity validity, ItemListener invocation,
`requestEntityTag` outcome, publication state/detail hoặc stopped DB/NBT seal,
không phát hành verdict lỗi ItemGuard.

## Review và immutable successor

- Candidate ItemGuard: `2e1fb7d8717a02b316bef9c017d09d67b8705c694519433276bd6e316d5adc5e`.
- Probe: `3f1fc8f8ed5f4bf149eae4bb42ee4fe852c4933c517ea0278c8f87bf93e487c5`.
- Bundle: `review-bundle-attempt-18`.
- Manifest: `59923469cf9ea589d777001152fc8a93f4b5d1b2182b82f27dbbbfa9fe6fe611`.
- Runtime tree: `513726e210c707d17dca1e92fe4e0fa52cdd0d93f623ed03c44d8c82cd5dfa93`.
- Exact set: `177 payload + manifest = 178`, zero symlink/bytecode residue,
  toàn bộ file read-only.
- Independent Hermes reviewer `deleg_3b86d32b`:
  `PASS_FOR_CONTROLLED_PAPER`, chỉ authorize `attempt-12`.
- Receipt mới: `review-attempt-18-pass.json`, SHA-256
  `68e72fefdf6d585fd27feb7d2154abf089ab360080fd8d2e3e81a1e250386341`.
- Review thực thi thật: handshake `57`, precision `4`, event-spec `2`, verifier
  `28`, static `240`, 11 contract group lân cận, Node syntax, packet sequence
  `20`, Javac JDI, serializer `2b/1 byte/a318c242…d3b`, negative exact-set và
  integrity trước/sau đều PASS; blocker/logic/security rỗng.

Các Claude review Attempt 16/17 là BLOCK do sandbox không chạy được process;
không được dùng làm authorization. Finding `LOCAL_FAILED` false-full-PASS từ
review Attempt 17 đã được TDD RED→GREEN trước khi seal Attempt 18.

## OBSERVED runtime

Runtime token: `67af1b39-33ce-49c6-9189-c7471cefd6ca`.
Actor: `IGNaturalBreak / 12f642e8-c8b3-3fb6-8e01-1987dfceda65`.
Target: `(328,-56,-8)`.

### Factual client-loaded gate

- Bot gửi `player_loaded`, packet ID `0x2b`, đúng `1` byte, SHA-256
  `a318c24216defe206feeb73ef5be00033fa9c4a74d0b967f6532a26ca5906d3b`.
- Probe ghi `CLIENT_LOADED` sau arm `107ms`.
- `timeout=false`, `clientLoadedEventsBeforeArm=0`.
- Confirmation bind exact actor, lifecycle `WAITING_FOR_CLIENT_LOADED`, probe
  sequence `2` và receipt hash `a23493cb…c947`.

### Packet/JDI/Paper path

- START sequence `577338485`; STOP `577338486`; ACK factual cho cả hai.
- `DIG_CLIENT_TERMINAL=LOCAL_RESOLVED`.
- JDI labels exact actor/action/sequence/position trên `Server thread`:
  `PLAYER_ACTION_ENTRY → IMMOBILE_PASS → CLIENT_LOADED_PASS →
  BLOCK_ACTION_BRANCH → CHUNK_LOADED → PRE_HANDLER_GUARD_PASS →
  HANDLE_BLOCK_BREAK_ACTION_ENTRY → GAME_MODE_RANGE_PASS`.
- Strict verifier: `PASS_SERVER_JDI_BRANCH /
  ENTERED_POST_GAME_MODE_RANGE_GUARD`.

### Bukkit/drop path

Probe receipt chain:

`PREPARE_ARMED → CLIENT_LOADED → RESERVE_CONFIRMED → ACTOR_READY →
INTERACT_EVENT → DAMAGE_EVENT → BREAK_EVENT → DROP_EVENT →
CONTENT_ENTITY_ADD → FAILURE(fresh-identity-timeout)`.

- Interact/damage/break đều `cancelled=false`.
- Exact content drops `1`, unexpected drops `0`.
- Dropped entity UUID `40320b5e-7804-434c-ba25-6b5ecbc84a88`.
- Entity-add untagged hash `68b5a5ed…e289` khớp initial fixture hash.
- `CONTENT_ENTITY_ADD → FAILURE`: `59.889s`.
- Partial verifier: `PASS_PARTIAL_TELEMETRY`; full verifier không PASS.

## Không được suy rộng

- Không chứng minh fresh-identity/product defect.
- Không chứng minh WorldGuard hoặc dependency cancellation.
- Không có restart/verify/cleanup journey vì prepare fail-closed.
- Không deploy/restart production; production không bị chạm.
- Không rerun `attempt-12` hoặc reuse receipt Attempt 18.

## Restore defect và recovery

Automatic restore FAIL vì pipeline dùng `shutil.copy2` từ sealed read-only bundle,
làm hai JAR controlled clone nhận mode `0444`; `restore.py` gọi `unlink()` và bị
`WinError 5`.

Sau khi Paper/JDI đã exit và controlled ports không còn listener, recovery chỉ:

1. bỏ read-only đúng `plugins/ItemGuard.jar` và
   `plugins/ItemGuardSmokeProbe.jar` trong controlled clone;
2. chạy sealed `restore.py` một lần;
3. không chạy runtime lại.

Recovery restore PASS:

- ItemGuard/probe/DB byte hash và DB logical hash khớp baseline;
- DB integrity `ok`, journal mode `delete`;
- trigger/lock/sidecar `0`;
- ports `57485/36104/57486` đóng.

## Evidence

- Namespace: `E:\AI.WORK\evidence\itemguard-natural-container-break-telemetry-20260829\attempt-12`.
- Classification: `attempt-12/classification.json`.
- Runtime original verdict: `attempt-12/runtime-verdict.json` (operation FAIL,
  automatic restore FAIL, giữ nguyên lịch sử).
- Recovery: `attempt-12/restore-receipt.json`.
- Pipeline audit: `attempt-12/pipeline-resume-audit.json`.

## Evidence tiếp theo bắt buộc

Trước controlled namespace mới phải TDD và independent review một successor ghi:

1. ItemListener entity-add invocation;
2. deferred stable-entity resolution;
3. `requestEntityTag` return/outcome;
4. publication `PREPARED/PUBLISHED/ABORTED` state/detail;
5. physical entity validity/hash/identity theo bounded intervals;
6. stopped DB/NBT state;
7. deployment/restore không truyền thuộc tính read-only.

Không sửa product khi chưa có RED product defect đủ evidence.
