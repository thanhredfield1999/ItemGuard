# Block-container stale inventory sau chunk unload

Ngày: 2026-08-28

## Trạng thái

`CORRECTED + CONTROLLED PAPER VERIFIED`. Không có bằng chứng defect đã chạy production; production không được truy cập, deploy hoặc restart trong task.

## Symptom

Block-container first-publication capture một Bukkit `Inventory`, physical `CraftItemStack` handle và digest trước khi reserve bất đồng bộ. Khi reserve hoàn tất, code cũ chỉ đọc/ghi lại captured inventory object.

Nếu source chunk unload hoặc block state bị thay trong cửa sổ đó, captured tile inventory không còn là authoritative live block container. Paper `TileStateInventoryHolder.getInventory()` ghi rõ inventory trả về có thể không còn hợp lệ nếu block đã thay đổi.

Tác động tiềm năng: publication có thể ghi tagged bytes vào stale inventory object rồi canonicalize một receipt không còn gắn với live physical source. Đây là fail-open authority boundary; chưa có bằng chứng duplicate/corruption production thực tế.

## Root cause

`requestInventorySlotTag(...)` chỉ revalidate:

- captured physical NMS item handle;
- serialized source digest;
- captured inventory slot.

Nó không re-resolve exact world/block/local slot, không yêu cầu source chunk vẫn loaded, và generic public method cho phép block inventory caller bỏ qua physical-authority check.

## Correction

- thêm `BlockContainerPhysicalSlotResolver.resolveLoaded(Location, localSlot)`;
- từ chối ngay nếu world/chunk không loaded, không force-load;
- đọc live state bằng `block.getState(false)`;
- chest dùng `Chest.getBlockInventory()` để giữ exact physical half/local slot;
- `requestBlockContainerSlotTag(...)` chỉ tạo source key sau loaded-authority resolution;
- re-resolve live slot và so handle+digest trong cả `matches()` lẫn `write()`;
- write vào current live inventory, không captured object;
- generic inventory helper đổi thành private player-only helper, đóng block-container bypass.

Bukkit world/block/inventory access vẫn chạy trên Paper main thread; DB reserve/publish tiếp tục bất đồng bộ.

## Regression evidence

Ba RED contracts:

1. matching handle+digest vẫn phải reject khi source không còn authoritative;
2. block publication phải có loaded-only resolver và re-resolve trước match/write;
3. generic inventory publication không được public để block caller bypass authority.

Sau correction:

- focused `28/28` PASS;
- full Java 21 clean test/package `292/292` PASS;
- `git diff --check` PASS;
- exact Opus 4.8 review: `PASS_FOR_CONTROLLED_PAPER`, blockers `[]`.

## Controlled Paper evidence

Candidate `2e1fb7d8717a02b316bef9c017d09d67b8705c694519433276bd6e316d5adc5e`, Paper `1.21.11-131`:

- real hopper fresh eligible attempt bị cancel trước publication;
- exact source chunk unload thật trong delayed reserve window;
- first publication `3VZ5IG` kết thúc `ABORTED/SOURCE_CHANGED` sau unload;
- offline NBT giữ đúng một untagged `DIAMOND_SWORD` tại chest slot `3`, hopper rỗng;
- reload thật + retry cùng source key tạo distinct identity `NABXUS`, `PUBLISHED`;
- exact tagged bytes giữ qua restart;
- cleanup, stopped DB invariants và operational restore PASS;
- independent verifier `56/56`: `PASS_AUTHORITATIVE_CONTAINER_PREWRITE_CHUNK_UNLOAD`.

Sibling same-location replacement gate cũng PASS trên cùng candidate:

- exact `CHEST → BARREL → CHEST` trong delayed reserve;
- source key và serialized digest giữ nguyên nhưng physical tile/item object được dựng lại;
- first proposal `MN9NS9` kết thúc `ABORTED/SOURCE_CHANGED`;
- offline NBT giữ exact untagged item, hopper rỗng;
- retry tạo distinct `KJOMHK`, `PUBLISHED`;
- restart/cleanup/restore PASS;
- exact `cc/claude-opus-4-8` independent verifier `199/199` PASS:
  `PASS_AUTHORITATIVE_CONTAINER_BLOCK_REPLACEMENT`.

Logical double-chest chunk-border gate cũng PASS trên cùng candidate:

- real `DoubleChestInventory` event từ exact initiating hopper ánh xạ đúng physical source half/local slot `3`;
- cả source và sibling chunks nhận real unload events và đều unloaded trong reserve;
- first proposal `9VGRD4` kết thúc `ABORTED/SOURCE_CHANGED`;
- stopped NBT giữ exact untagged source item, sibling/hopper rỗng;
- reload cả hai chunks nhận real load events, retry fresh `H0FBX9` `PUBLISHED`;
- restart/cleanup/operational restore PASS;
- independent stopped-evidence verifier `143/143`: `PASS_AUTHORITATIVE_DOUBLE_CHEST_CHUNK_BORDER`;
- Claude provider không có verdict vì HTTP 400 quota; independent fallback review attempt 7 `PASS_FOR_CONTROLLED_PAPER`.

Chi tiết: `docs/runtime/2026-08-28-controlled-block-container-prewrite-chunk-unload.md`.
Replacement matrix: `docs/runtime/2026-08-28-controlled-block-container-replacement.md`.
Double-chest border matrix: `docs/runtime/2026-08-29-controlled-double-chest-chunk-border.md`.

## Audit chain

Attempt 1 đã PASS runtime prepare/DB abort nhưng offline NBT parser bị gọi bằng CPython 3.14 với native bundle CPython 3.11. Attempt này được giữ `NON_AUTHORITATIVE_HARNESS_TOOLCHAIN_FAILURE`; restore PASS và không dùng làm final evidence.

Attempt 2 dùng exact CPython 3.11.15 cho NBT parser; production candidate, probe và Opus review không đổi. Đây là authoritative run.

## Residual risk

Không suy gate này sang:

- post-write receipt race (gate riêng đã có);
- natural player break/place và physics-event semantics;
- hơn hai hoppers/density/performance;
- non-chest/custom inventories và throwing accessors;
- Folia/regionized threading;
- multi-server/multi-writer/shared filesystem;
- production deployment/verification.
