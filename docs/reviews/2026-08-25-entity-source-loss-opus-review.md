# Entity source-loss before physical write — independent review

Ngày: 2026-08-25

## Reviewer

- Primary route: `cc/claude-opus-4-8` không trả usable verdict qua local router.
- Accepted fallback: `ag/claude-opus-4-6-thinking`.
- Review mode: adversarial/counterexample-seeking cho persistence + anti-dupe evidence.

## Verdict

- `VERDICT: PASS`
- `PRODUCTION CHANGE REQUIRED: NO`

Reviewer không tìm thấy counterexample cho exact tokenized negative hoặc positive control.

## Claims được chấp nhận

### Exact negative isolation

Carryover entity `d01440c3-...` không invalidate exact negative vì:

- carryover source key: `ENTITY:d01440c3-...`;
- carryover name không có run token;
- carryover identity: `E9NZIZ/a9d7b7ae-...`;
- exact negative source key: `ENTITY:cd891b82-...`;
- exact negative name: `ItemGuard Entity Loss Negative c3591a04`;
- exact negative identity: `TWTDOH/81fe6c42-...`.

Carryover làm aggregate tăng `+1`, nhưng không match exact trigger/name/source/identity và được ghi riêng trong evidence.

### Negative ordering và state

Reviewer đối chiếu:

```text
spawnedAt        1787648789591
journalObserved  1787648789856
removedAt        1787648789900
```

Physical marker xác nhận trước removal:

- không có `itemguard:code`;
- không có `itemguard:item_uuid`;
- sau removal entity invalid và không resolve được bằng exact UUID.

Source path trong `AsyncTagPublicationCoordinator.publishOnMainThread()` là:

```text
matches() false -> abort(SOURCE_CHANGED) -> không write, không publish
matches() true  -> write() -> publish()
```

Exact durable row là `ABORTED/SOURCE_CHANGED`; exact canonical/snapshot `0/0`.

### Positive control

Reviewer chấp nhận positive entity độc lập:

- entity UUID `69d85269-...` khác negative `cd891b82-...`;
- publication `PUBLISHED`;
- canonical identity đúng;
- snapshot `230` bytes, SHA-256 `9f511605...` match physical marker;
- exact entity/PDC sống qua graceful restart.

Positive control không được trình bày là recovery của removed entity locator.

## Findings

Không có blocker.

Reviewer ghi nhận:

1. carryover entity được isolate đúng khỏi exact oracle;
2. tổng `aborted=9` có residual audit từ prior attempts, nhưng chỉ exact tokenized row được dùng làm primary proof;
3. main-thread synchronous ordering là cơ sở mạnh hơn journal presence;
4. marker `hadCode=false/hadUuid=false` là direct physical evidence chưa có PDC write.

## Scope limits / evidence gaps

Review giữ các giới hạn:

- một loaded ground-item entity mỗi polarity;
- không repeated negative trials;
- chỉ source removed trước physical tag write;
- positive dùng entity UUID mới;
- không entity post-receipt;
- không unload/chunk-unload boundary;
- không merge/split/stackable;
- không concurrency/scale/production;
- không reconciliation negative từ một source khác presenting matching tagged digest;
- provenance của toàn bộ non-tokenized historical abort rows không được phân loại riêng.

Reviewer nêu hypothetical removal giữa `matches()` và `write()`. Source hiện gọi hai thao tác đồng bộ trong cùng server-thread callback và không thấy explicit yield/callback seam giữa chúng; tuy nhiên controlled runtime này không tự mở rộng claim sang merge/unload hoặc third-party behavior.

## Evidence reviewed

- Runtime report:
  `docs/runtime/2026-08-25-controlled-entity-source-loss.md`
- Final evidence SHA-256:
  `696080ec1a63dbf4cb4f7c3e9e49dcbb38fddd4ab54e5fa498843c858b54910f`
- Negative evidence SHA-256:
  `de6f9e462ae5dbeca40f0b025430ced9d96e1508fce9a2ad4103b58415512be0`
- Positive evidence SHA-256:
  `118bba7132d0c59d26eb4df7bbb1a187ca61e4081f96bd30964e511bc8cdb1c7`
- Candidate:
  `189022b7b0e954d5e830896cf7ead09419fade85895d412073230df2d3767e10`
- Java 21 focused `24/24` and full `201/201` PASS.

## Release decision

- Không sửa production source theo gate này.
- Không deploy/restart production.
- Overall status vẫn `NOT RELEASE READY`.
