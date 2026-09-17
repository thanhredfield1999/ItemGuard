# Entity chunk-unload/reload — independent review

Ngày: 2026-08-26

## Final verdict

- Reviewer: `cc/claude-opus-4-8`.
- `CORRECTION: ACCEPTED`.
- `VERDICT: PASS`.
- `PRODUCTION CHANGE REQUIRED: NO`.
- Overall: `NOT RELEASE READY`; production untouched.

## Review history

Pre-runtime review ban đầu `BLOCK` vì fixture chưa bắt buộc unloaded marker, chưa có offline NBT oracle và dùng generic journal attribution. Các blocker được đóng trước exact PASS run:

- `verify` bắt buộc validate exact unloaded marker và phase barrier;
- marker ghi atomic move;
- holder được recheck sau spawn và trước unload;
- exact offline NBT oracle parse toàn entity-region;
- exact delayed trigger chỉ match one publication;
- separate diagnostic chứng minh `unloadChunkRequest` là Paper boundary đúng.

Final review vòng đầu trả PASS nhưng diễn giải nhầm final `triggers=0` thành phase 2 không có trigger. Correction review đối chiếu artifact timeline:

1. exact trigger được cài offline sau phase 1;
2. phase-2 seal vẫn thấy đúng trigger và exact publication PUBLISHED;
3. NBT seal xong mới drop trigger;
4. final triggers=0 chỉ là resting state.

Correction được chấp nhận và giữ PASS.

## Findings

- Exact trigger chạy `AFTER UPDATE PREPARED→PUBLISHED`, chỉ match publication `32661d99-...`, recursive 30M trước statement return/commit.
- One `SqliteConnectionOwner` serialize one connection; không có controlled long trigger khác. Reviewer không tìm được alternative transaction tạo journal ổn định >250 ms đồng thời thỏa exact trigger match và final PUBLISHED.
- Canonical short-circuit ở verify không che reload: exact unloaded marker, initially-unloaded chunk, `requireExactLoaded` và `EntitiesLoadEvent` exact UUID là postconditions độc lập.
- Offline NBT UUID conversion/cardinality chặt: đúng một exact entity UUID, một code hit, một item UUID hit trong exact entity Item components.
- Low residual: giữ bắt buộc cả `requireExactLoaded` và exact `EntitiesLoadEvent`; không suy rộng khỏi scope.

## Evidence gaps/scope

Không chứng minh atomic Bukkit+SQLite, concurrent/crash/scale, entity merge/stackable hoặc production. Đây là controlled point-in-time receipt transaction survival cho một remote loaded non-stackable ground item.

## Raw results

- Pre-review: `C:\Users\thanh\AppData\Local\Temp\itemguard_entity_chunk_unload_fixture_review_result.txt`.
- Final review: `C:\Users\thanh\AppData\Local\Temp\itemguard_entity_chunk_unload_final_review_result.txt`.
- Correction: `C:\Users\thanh\AppData\Local\Temp\itemguard_entity_chunk_unload_review_correction_result.txt`.

Runtime report: `docs/runtime/2026-08-26-controlled-entity-chunk-unload-reload.md`.
