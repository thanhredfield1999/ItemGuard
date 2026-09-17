# Repeated concurrent publication stress — Opus 4.8 review

Ngày: 2026-08-26

## Final verdict

- Reviewer: exact `cc/claude-opus-4-8`.
- `VERDICT: PASS`.
- `FINDINGS: none`.
- `PRODUCTION CHANGE REQUIRED: NO`.

## Review history

Pre-runtime review ban đầu `INCONCLUSIVE` vì chưa thấy commit-before-readiness proof, independent DB anchor, overlap telemetry và exact attribution.

Correction review đối chiếu exact source và fixture cập nhật:

- `SqliteConnectionOwner.runTransaction` commit trước future completion;
- cả `markPublished→markReady` và reconciliation route chỉ latch readiness sau durable success;
- controller không ghi DB; offline SQLite là anchor độc lập;
- private `inFlightSources` được bind vào exact candidate classloader và hard-fail nếu mismatch;
- mỗi wave bắt buộc max in-flight `>=50`;
- run-token attribution và exact global deltas bắt loss/extra/retry;
- entity remove chỉ sau 100/100 durable-ready và atomic manifest write.

Correction: `ACCEPTED`, pre-runtime `PASS`, blockers none.

## Final findings

Reviewer kiểm tra lại source và evidence:

- all readiness routes are post-commit;
- `inFlightSources=100` ở cả năm wave chứng minh reservation/publication-window overlap;
- exact DB delta `+500/+500/+500`, zero delta ở PREPARED/ABORTED và các bảng ngoài scope;
- exact 500 attributed rows/canonical/snapshot đóng loss/extra/orphan;
- cleanup `alreadyAbsent=500` đúng semantics vì exact wave entities đã bị remove post-commit;
- manifest/DB SHA anchors độc lập và restart/NBT absence đóng cleanup persistence;
- hai sealer correction chỉ sửa oracle expectation/shape, không thay runtime evidence.

Không tìm thấy counterexample phá claim hẹp.

## Scope limits

- One Paper server, one serial DB owner, one player/wave-owner, one naturally-loaded chunk.
- Overlap evidence là reservation/publication-window overlap, không phải simultaneous commit contention.
- Không cover multi-writer/player, unload/crash, merge/stackable hoặc production scale.
- Durability claim dừng ở SQLite `connection.commit()`, không mở rộng sang alternate store/WAL/OS fsync.

## Nonblocking note

NBT parser quét 4 entity-region files/31 chunks trong ba standard dimensions và tìm zero hits; evidence không ghi riêng spawn chunk coordinate vào NBT report. Điều này không chặn exact all-dimension UUID/code/item-UUID absence claim.

## Raw review

- `C:\Users\thanh\AppData\Local\Temp\itemguard_publication_stress_fixture_review_result.txt`
- `C:\Users\thanh\AppData\Local\Temp\itemguard_publication_stress_review_correction_result.txt`
- `C:\Users\thanh\AppData\Local\Temp\itemguard_publication_stress_final_review_result.txt`

Runtime: `docs/runtime/2026-08-26-controlled-repeated-concurrent-publication-stress.md`.
