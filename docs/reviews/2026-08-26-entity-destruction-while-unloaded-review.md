# Entity destruction while unloaded — independent review

Ngày: 2026-08-26

## Verdict

- Pre-runtime reviewer: exact `cc/claude-opus-4-8`.
- Initial verdict: `BLOCK` vì chưa có offline mutator, DB seal và exact cardinality oracle.
- Correction: `ACCEPTED`, `VERDICT: PASS`, blockers none.
- Final `cc/claude-opus-4-8`: HTTP 429 trên toàn bộ active accounts sau bounded retries.
- Approved fallback final reviewer: `ag/claude-opus-4-6-thinking`.
- Final verdict: `PASS`.
- Findings: none.
- Production change required: `NO`.
- Evidence gaps: none.

## Blockers đã đóng

1. Offline mutator được cung cấp và review: exact pre-cardinality, region backup, sector-fit, header/outside-byte preservation, atomic replace, full reparse, non-target fingerprint/hash invariants và DB-hash preservation.
2. Restart oracle không còn tautology “chunk empty thì PASS”; runtime target entity count phải bằng receipt `targetEntityCountAfter`, còn receipt bắt count `before=after+1` và exact hits `1→0`.
3. Phase-1 DB seal chứng minh exact publication `PUBLISHED`, canonical/snapshot cùng payload bytes/SHA; final seal yêu cầu toàn logical DB state bằng baseline.
4. Commit-before-ready được trace: SQLite transaction commit trước future completion; readiness chỉ latch trong completion callback. Phase-1 DB seal vẫn là durable anchor độc lập.
5. Polling-induced reconciliation nằm trước phase-1 post-publication baseline; destruction/restart delta đo từ baseline đó.
6. Final verifier hiện bắt buộc restart receipt bằng `assert R.exists()`.

## Final review reasoning

Fallback reviewer xác nhận:

- exact mutation không có counterexample remove sai/nhiều entity mà vẫn PASS trong reviewed evidence chain;
- raw region/SQLite file-hash drift sau excluded clean startup không phá narrow semantic claim vì retry precondition dùng exact parsed target backup-minus-one, all-world identity absence và exact logical DB equality;
- valid restart exact event/UUID/PDC/count evidence đóng resurrection/duplication;
- giữ `PUBLISHED` canonical/snapshot sau physical destruction phù hợp point-in-time durable-ledger semantics;
- không có production defect hoặc production change được chứng minh.

## Scope limits

- Một non-stackable ground entity, một remote chunk, một Paper server và SQLite owner.
- Offline region edit là controlled external-destruction simulation, không phải natural in-game damage/despawn cause.
- Không cover ItemGuard destruction observation/tombstone, canonical deletion, reclaim issuance, destructive anti-dupe, merge/stackable, multi-player/writer, crash hoặc production.

## Raw reviews

- `C:\Users\thanh\AppData\Local\Temp\itemguard_entity_unloaded_destruction_fixture_review_result.txt`
- `C:\Users\thanh\AppData\Local\Temp\itemguard_entity_unloaded_destruction_correction_review_result.txt`
- `C:\Users\thanh\AppData\Local\Temp\itemguard_entity_unloaded_destruction_final_review_result.txt`

Runtime: `docs/runtime/2026-08-26-controlled-entity-destruction-while-unloaded.md`.
