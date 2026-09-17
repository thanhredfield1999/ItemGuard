# Controlled player-slot source-loss reconciliation

Ngày: 2026-08-24

## Kết luận

`VERIFIED controlled Paper` cho exact player-slot scope:

1. Physical tagged source bị xóa và được persist vào playerdata trước crash.
2. Sau force-kill/restart, source không còn tồn tại nên PREPARED không materialize
   canonical/snapshot.
3. Detached/reclaim path vẫn deny, không issuance.
4. Positive control restore exact bytes vào cùng player UUID/slot 8; anchored scan
   reconcile đúng một canonical + snapshot và chứng minh tagged-byte SHA ổn định.

Overall project vẫn `NOT RELEASE READY`; production untouched.

## Environment và artifacts

- Controlled clone: `E:\AI.WORK\itemguard-paper-smoke`.
- Paper: `1.21.11-131`; Java `21.0.4`.
- Minecraft port `57484`; query port `36103`.
- ItemGuard SHA-256:
  `189022b7b0e954d5e830896cf7ead09419fade85895d412073230df2d3767e10`.
- Probe SHA-256:
  `c55dd18b42a9533ed4447219d1c8076702f7fca50a072eeb865abc005217c357`.
- Full Java 21 before runtime: `58` suites / `201/201` PASS.
- Exact `cc/claude-opus-4-8` vòng 4: `VERDICT: PASS`.
- Backup clone:
  `E:\AI.WORK\backups\itemguard-paper-smoke-source-loss-20260824-204716.tar.gz`;
  SHA-256 `974e318af1789b014f0b93145a3969afd80211d762bdedf9b528a0af79de9c4c`.
- Crash log:
  `E:\AI.WORK\backups\itemguard-source-loss-crash-20260824-205200.log`;
  SHA-256 `64bde8ce91c100b9425dc1f3a4c29e49c9ab78ac41528e882d46037387fd809b`.
- Final log:
  `E:\AI.WORK\backups\itemguard-source-loss-final-20260824-210026.log`;
  SHA-256 `a40fc15776588b50adc7743aca185bfe58dcabdc3a3552d8044d151a9ac06808`.

## Baseline

Offline baseline trước deploy/run:

- schema `7`, `PRAGMA integrity_check=ok`;
- tracked/snapshot `503/503`;
- tag publications `342`, PREPARED `0`;
- reclaim claims `9`, active `0`;
- controlled trigger `0`;
- `anti-dupe.enabled=false`;
- `reclaim.issuance-enabled=false`;
- ports `57484/36103` closed.

Clone-only trigger chỉ abort production transition `PREPARED -> PUBLISHED`. Trigger
không insert/update identity rows và không manufacture PREPARED/canonical/snapshot.

## Fixture identity

- Dedicated controlled player UUID:
  `71fcf6be-e1ee-3c92-9a42-893cbe44818c`.
- Slot: `8`.
- Code: `BQ8GI8`.
- Item UUID: `6d875bec-61c0-447a-b814-2402069d919b`.
- Stable source key:
  `PLAYER_SLOT:71fcf6be-e1ee-3c92-9a42-893cbe44818c:8`.
- Exact tagged serialized SHA-256:
  `0de6df99c41acc80c70b3e605c4aa2273acc6feebe0015cf2330d07b6f825a07`.

## Negative source-loss journey

1. Paper started exact candidate/probe; both ports opened and startup snapshot
   round-trip logged PASS.
2. Probe installed one untagged sword at slot 8. Production first-tag path wrote
   code/UUID and created PREPARED; trigger blocked publish.
3. Probe saved exact tagged bytes, removed slot 8, called `Player.saveData()`, read
   slot back empty, then wrote marker.
4. Read-only watcher sealed:
   - exactly one PREPARED row;
   - exact source key;
   - tagged SHA length 32;
   - canonical/snapshot `0/0`;
   - active claims `0`;
   - integrity `ok`, schema `7`.
5. NBT read-back of exact playerdata file before crash:
   - inventory entries `0`;
   - slot 8 entries `0`;
   - code/UUID absent from persisted inventory.
6. Fresh PID ownership check required exactly one port `57484` listener, JDK 21
   `java.exe`, `-jar paper.jar` and
   `-Ditemguard.controlledRoot=E:/AI.WORK/itemguard-paper-smoke`.
7. Force-killed exact Java child PID `54800`. Crash log has no graceful shutdown
   markers after source removal.
8. Offline trigger removal read-back:
   PREPARED `1`, canonical/snapshot `0/0`, trigger `0`, integrity `ok`.
9. Restarted exact artifact with no trigger. Dedicated player inventory count was
   `0`; `/igsourcelossverify` confirmed source absent.
10. `/matdo sos BQ8GI8` returned exact deny `ID chua duoc theo doi.`; no issuance.
11. Final negative DB oracle before restore:
    - PREPARED `1`;
    - canonical/snapshot `0/0`;
    - totals remained tracked/snapshot `503/503`, publications `343`;
    - reclaim claims remained `9`, active `0`;
    - trigger `0`, integrity `ok`.

## Positive exact-source control

1. Probe deserialized the exact saved bytes and restored them into the same player
   UUID and slot 8.
2. Restore message reported the exact SHA-256 above.
3. Waited more than one configured 600-tick/30-second anchored scan interval.
4. Reclaim returned `PLAYER_INVENTORY` because canonical physical item existed.
5. Read-only DB oracle:
   - publication state `PUBLISHED`;
   - one canonical row and one snapshot v1;
   - tracked/snapshot totals `504/504`, publication total still `343`;
   - publication SHA, snapshot SHA and physical serialized SHA all exactly
     `0de6df99…25a07`;
   - one new reclaim claim, state denied; total `10`, active `0`;
   - trigger `0`, schema `7`, integrity `ok`.

This positive control closes the reviewer evidence gap for
`serializeAsBytes()/deserializeBytes()` determinism in this exact Paper/item case.

## Safe resting state

- Graceful `stop` logged ItemGuard disable, `Database connection closed` and all
  dimensions saved.
- Paper wrapper required cleanup after ports were already released, consistent with
  the existing Moonrise worker termination caveat; ItemGuard DB close completed.
- Ports `57484/36103` closed.
- Anti-dupe and issuance false.
- Trigger/PREPARED/active claims `0/0/0`.
- DB schema `7`, integrity `ok`.
- Exact candidate/probe remain in offline controlled clone.

## Scope boundary

`VERIFIED controlled`:

- player source absent before crash/restart/reconnect scan;
- exact playerdata persistence of source removal;
- no ghost canonical/snapshot materialization;
- detached/reclaim denial and no issuance;
- same player UUID + same slot + exact tagged bytes recovery;
- tagged physical/publication/snapshot digest equality.

`NOT VERIFIED`:

- source disappears after main-thread receipt observation but before DB commit;
- legitimate relocation to another slot/source key;
- entity or block-container source-loss crash windows;
- virtual/external storage, multi-player and stackable semantics;
- scale/backpressure for mass entity load/distinct receipt bursts;
- destructive quarantine, issuance and production.
