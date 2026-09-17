# Controlled natural container break — client-load guard isolated

Ngày: 2026-08-30

## Kết luận

`NOT_ISSUED / HARNESS_CLIENT_LOAD_HANDSHAKE_GAP`.

Controlled `attempt-11` chứng minh exact `START_DESTROY_BLOCK` tới
`ServerGamePacketListenerImpl.handlePlayerAction` trên `Server thread`, qua
`player.isImmobile()` rồi bị Paper return tại `hasClientLoaded()==false`.
Packet không tới chunk/range guard, `ServerPlayerGameMode.handleBlockBreakAction`
hoặc Bukkit interact/damage/break handlers. Đây là harness/client protocol
handshake gap, không phải evidence lỗi ItemGuard, WorldGuard hay dependency.

## Phạm vi và integrity

- Controlled clone: `E:\AI.WORK\itemguard-paper-smoke`.
- Paper: `1.21.11-131`, Java 21.
- ItemGuard candidate SHA-256:
  `2e1fb7d8717a02b316bef9c017d09d67b8705c694519433276bd6e316d5adc5e`.
- Probe SHA-256:
  `2496e30723eb7bf528d216f137d81599428b2e3066ef20442e0f39153fb11fd7`.
- Immutable bundle: `review-bundle-attempt-15`.
- Manifest SHA-256:
  `c9fd5aabede33d23c612d36bad4f8d89c8cb0d18112bff46ff37c79b951484d0`.
- Exact set: `124 payload + manifest = 125 files`.
- Independent reviewer `deleg_a1634e20`: `PASS_FOR_CONTROLLED_PAPER`, chỉ
  authorize namespace `attempt-11`; exact set trước/sau `125/125`, không tạo
  pycache/class artifact.
- Review receipt: `review-attempt-15-pass.json`.
- Runtime token: `60e5de7b-ffda-47bd-850d-d34d5ed386fd`.

## Verification trước runtime

- Contracts PASS: `18/18`, `38/38`, `20/20`, `23/23`, `11/11`, `10/10`,
  `15/15`, review gate `20/20`, packet telemetry `29/29`, JDI monitor `22/22`,
  server branch verifier `12/12`.
- Static: `177/177` PASS.
- Dynamic packet sequence: `20/20` PASS.
- Python/Node/Java syntax PASS; undeclared extra-file bị từ chối.
- Stable exact-set snapshots ba lần; ports đóng; namespace absent.
- Attempt-14 stale receipt bị từ chối trước namespace mutation.

## OBSERVED runtime evidence

Probe receipts:

1. `PREPARE_ARMED`.
2. `RESERVE_CONFIRMED`.
3. `ACTOR_READY`: server-side player `SURVIVAL`, on-ground, empty hand, exact
   chest `(328,-56,-8)`.

Bot receipts:

1. `CLIENT_READY` exact chest, survival, on-ground, empty hand.
2. `DIG_STARTED`.
3. `DIG_PACKET_SERIALIZED START_DESTROY_BLOCK`, sequence `806203873`.
4. `DIG_PACKET_SERIALIZED STOP_DESTROY_BLOCK`, sequence `806203874`.
5. `DIG_PACKET_ACK`, sequence `806203874`.
6. `DIG_CLIENT_TERMINAL LOCAL_RESOLVED`.

Timing:

- `ACTOR_READY → START`: `52ms`.
- `START → STOP`: `3,770ms`.
- `STOP → ACK`: `37ms`.

JDI branch receipts cho exact actor/action/sequence/position:

1. `PLAYER_ACTION_ENTRY`.
2. `IMMOBILE_PASS`.
3. `CLIENT_NOT_LOADED_RETURN`.

Strict branch verifier trả:
`PASS_SERVER_JDI_BRANCH / REJECTED_CLIENT_NOT_LOADED`.

Không có receipt:

- `INTERACT_EVENT` / `INTERACT_BLOCKED`;
- `DAMAGE_EVENT` / `DAMAGE_CANCELLED`;
- `BREAK_EVENT` / product success tail.

## Exact protocol/Paper interpretation

- Paper `hasClientLoaded()` chỉ true khi `waitingForRespawn == false` và
  `clientLoadedTimeoutTimer <= 0`.
- `handleAcceptPlayerLoad(ServerboundPlayerLoadedPacket)` gọi
  `markClientLoaded(false)`.
- `minecraft-data` protocol `1.21.11` định nghĩa serverbound `player_loaded`
  packet ID `0x2b`.
- Search exact pinned `mineflayer 4.37.1` và `minecraft-protocol 1.66.2`
  implementation không tìm thấy code gửi `player_loaded`.
- Paper có fallback timer; STOP sau `3,770ms` được cumulative ACK, phù hợp với
  gate mở muộn hơn. Đây là corroboration, không thay thế JDI branch proof.

## Classification

- Operation: `FAIL` do marker timeout sau prepare.
- Restore: `PASS`.
- Product verdict: `NOT_ISSUED`.
- Classification sealed: `HARNESS_CLIENT_LOAD_HANDSHAKE_GAP`.
- `exactStartReachedServerPlayerActionHandler=true`.
- `immobileGuardPassed=true`.
- `clientLoadedGuardPassed=false`.
- `handleBlockBreakActionReached=false`.
- `worldGuardAttribution=false`.
- `dependencyCancellationProven=false`.
- `itemGuardDefectProven=false`.

Evidence:
`E:\AI.WORK\evidence\itemguard-natural-container-break-telemetry-20260829\attempt-11`.

## Restore

- `restored=true`.
- `portsClosed=true`.
- controlled triggers `0`.
- Operational ItemGuard/probe/DB byte and logical hashes match baseline.
- Production was not touched.

## Next required evidence

Không rerun attempt 11 hoặc reuse receipt attempt 15. Successor harness phải TDD:

1. gửi protocol-defined `player_loaded` ở đúng lifecycle, hoặc chờ factual
   `PlayerClientLoadedWorldEvent` / server `hasClientLoaded` pass trước START;
2. giữ exact sequence/position/JDI correlation và mọi fail-closed cleanup;
3. seal immutable successor, independent review, namespace mới.

Chỉ sau khi client-load gate pass mới tiếp tục phân biệt chunk/range,
`handleBlockBreakAction` và Bukkit product path. Không sửa ItemGuard/WorldGuard
khi chưa có RED product defect.
