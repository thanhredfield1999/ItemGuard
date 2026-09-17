# Controlled natural container break — packet boundary inconclusive

Ngày: 2026-08-30

## Kết luận

`NOT_ISSUED / INCONCLUSIVE_START_TRANSPORT_OR_EARLY_SERVER_GUARD`.

Controlled `attempt-10` chứng minh exact `START_DESTROY_BLOCK` và
`STOP_DESTROY_BLOCK` đã rời serializer của `minecraft-protocol`, và client nhận
cumulative ACK đúng sequence STOP. Tuy nhiên server-side Bukkit observer không
nhận `PlayerInteractEvent` hoặc `BlockDamageEvent`. Evidence chưa chứng minh lỗi
ItemGuard, WorldGuard hay dependency cancellation.

## Phạm vi và integrity

- Controlled clone: `E:\AI.WORK\itemguard-paper-smoke`.
- Paper: `1.21.11-131`, Java 21.
- ItemGuard candidate SHA-256:
  `2e1fb7d8717a02b316bef9c017d09d67b8705c694519433276bd6e316d5adc5e`.
- Probe SHA-256:
  `2496e30723eb7bf528d216f137d81599428b2e3066ef20442e0f39153fb11fd7`.
- Immutable bundle: `review-bundle-attempt-14`.
- Manifest SHA-256:
  `79c889d08e801bd9a50118054695f0e01b107a4a610fff5475bcd224730dcd7f`.
- Exact set: `116 payload + manifest = 117 files`.
- Independent reviewer `deleg_d23d4707`: `PASS_FOR_CONTROLLED_PAPER`, chỉ
  authorize namespace `attempt-10`; exact-set trước/sau giữ `117/117`, không
  tạo pycache.
- Review receipt: `review-attempt-14-pass.json`.
- Runtime token: `93e7b819-f10e-4ac5-94a9-fca93c3f226a`.

## Verification trước runtime

- Contracts PASS: `18/18`, `29/29`, `20/20`, `23/23`, `11/11`, `10/10`,
  `15/15`, review gate `20/20`, packet telemetry `27/27`.
- Static: `172/172` PASS.
- Dynamic packet sequence: `20/20` PASS.
- Node syntax PASS; undeclared extra-file bị từ chối.
- Stable preflight static `172/172` ba lần; ports đóng; namespace absent.
- Stale receipt attempt 10 bị review gate từ chối trước mutation.

## OBSERVED runtime evidence

Probe receipts:

1. `PREPARE_ARMED`.
2. `RESERVE_CONFIRMED`.
3. `ACTOR_READY`: server-side player `SURVIVAL`, on-ground, empty hand, exact
   target chest `(328,-56,-8)` đã qua `getTargetBlockExact(6)`.

Bot receipts:

1. `CLIENT_READY` exact chest, survival, on-ground, empty hand.
2. `DIG_STARTED`.
3. `DIG_PACKET_SERIALIZED START_DESTROY_BLOCK`:
   - sequence `864550997`;
   - event time `1788032171366`;
   - packet SHA-256
     `b9bf11b753b2c35f9363d9f122dd4e662bc2a646a83eae6931c1ff6f32f8e8d2`.
4. `DIG_PACKET_SERIALIZED STOP_DESTROY_BLOCK`:
   - sequence `864550998`;
   - event time `1788032175131`;
   - packet SHA-256
     `6922749bb7a21743933e2e28919240c2ef3423c3a5dbed88a15c29b7f938369f`.
5. `DIG_PACKET_ACK` sequence `864550998` at `1788032175145`.
6. `DIG_CLIENT_TERMINAL LOCAL_RESOLVED`.

Không có receipt:

- `INTERACT_EVENT` / `INTERACT_BLOCKED`;
- `DAMAGE_EVENT` / `DAMAGE_CANCELLED`;
- `BREAK_EVENT` / product success tail.

START và STOP cách nhau `3,765ms`. Không có ACK sequence START trong khoảng đó;
ACK duy nhất là sequence STOP sau STOP khoảng `14ms`.

## Exact Paper call-path interpretation

`ServerGamePacketListenerImpl.handlePlayerAction` có các boundary khác nhau:

1. `player.isImmobile()` có thể return trước ACK.
2. `hasClientLoaded()` có thể bỏ xử lý trước action path.
3. Với player-action branch, nếu chunk chưa loaded hoặc
   `isWithinBlockInteractionRange(pos, 1.0)` false, server gọi
   `ackBlockChangesUpTo(sequence)` rồi return trước
   `handleBlockBreakAction`.
4. Nếu guard pass, server gọi
   `ServerPlayerGameMode.handleBlockBreakAction(...)`, rồi mới
   `ackBlockChangesUpTo(sequence)`.
5. `handleBlockBreakAction(START_DESTROY_BLOCK)` gọi
   `PlayerInteractEvent LEFT_CLICK_BLOCK` trước `BlockDamageEvent`.

Vì ACK STOP có thể thuộc nhánh pre-handler hoặc post-handler, ACK không tự chứng
minh Bukkit handler đã chạy. Việc START không có ACK riêng trong 3,765ms trong
khi STOP có ACK là evidence mới quan trọng: seam có thể nằm ở START transport
sau serializer hoặc early server guard (`isImmobile` / `hasClientLoaded`) trước
ACK; STOP sau đó có thể chỉ được ACK khi không còn active START.

## Classification

- Operation: `FAIL` do prepare marker timeout.
- Restore: `PASS`.
- Product verdict: `NOT_ISSUED`.
- Classification sealed:
  `INCONCLUSIVE_SERVER_PLAYER_ACTION_GUARD_OR_HANDLER_SEAM`.
- Refined timeline interpretation:
  `INCONCLUSIVE_START_TRANSPORT_OR_EARLY_SERVER_GUARD`.
- `worldGuardAttribution=false`.
- `dependencyCancellationProven=false`.
- `itemGuardDefectProven=false`.

Evidence:
`E:\AI.WORK\evidence\itemguard-natural-container-break-telemetry-20260829\attempt-10`.

## Restore

- `restored=true`.
- `portsClosed=true`.
- controlled triggers `0`.
- Operational ItemGuard/probe/DB byte and logical hashes restored to baseline.
- Production was not touched.

## Next required evidence

Không rerun bundle attempt 14 hoặc reuse receipt của nó. Trước controlled Paper
mới cần TDD + immutable bundle + independent review cho factual START boundary:

1. transport/framer/socket completion receipt after serializer for exact START;
2. server-side receipt distinguishing `isImmobile`, `hasClientLoaded`,
   chunk-loaded and interaction-range guards from entry into
   `handleBlockBreakAction`;
3. fail-closed classification when START lacks ACK before STOP.

Không sửa ItemGuard/WorldGuard khi chưa có RED product defect.
