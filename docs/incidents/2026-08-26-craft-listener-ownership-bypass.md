# Craft output listener ownership bypass

Ngày: 2026-08-26

## Trạng thái

`FIX VERIFIED CONTROLLED PAPER + FINAL OPUS PASS`. Production `NOT DEPLOYED / NOT VERIFIED`.

## Triệu chứng

Controlled Paper candidate `7ab18e4d…c1d` nhận một real normal-click craft output:

- probe LOWEST thấy `CraftItemEvent` chưa bị cancel;
- MONITOR thấy event đã bị cancel;
- matrix giữ nguyên một `IRON_NUGGET`, player nhận zero sword;
- DB exact baseline;
- nhưng client nhận zero denial message từ `CraftListener`.

## Root cause

Paper API `1.21.11` xác nhận `CraftItemEvent extends InventoryClickEvent`.

`ItemListener.onInventoryClick` và `CraftListener.onCraft` cùng priority `HIGH`; `ItemListener` được register trước. Generic inventory handler cancel eligible untagged result, sau đó `CraftListener(ignoreCancelled=true)` bị skip. Hành vi vẫn fail-closed nhưng `CraftOutputPolicy` không sở hữu event.

## Regression

`CraftOutputWiringContractTest.genericInventoryClickDefersCraftEventsToCraftListener` RED trước fix: generic handler chưa defer subtype craft.

## Fix tối thiểu

`ItemListener.onInventoryClick` return ngay khi event là `CraftItemEvent`, trước `getWhoClicked`, `getCurrentItem` và mọi cancel/tag/publication path. `CraftListener` trở thành owner duy nhất cho output craft.

## Verification

- focused craft/hopper/stackable: `19/19` PASS;
- full Java 21: `66` suites / `232/232` PASS;
- runtime candidate: `763ec6c78996b3c18a898e2f46af7d444341890eb77fff29149d5e016253a667`;
- final rebuild: `d8a0919f655d4452f8a6269ef848e5636ddf16dc7f3bfc6a202e65ed819b5cec`;
- runtime/rebuild: `382/382` non-META-INF entries byte-identical;
- authoritative controlled run: `33c4beab-e610-40ae-9f06-03550a84a87f`.

Runtime sau fix bắt exact normal/shift event-cancel `1/1`, hai denial messages, zero output/consumption/DB delta, restart và cleanup PASS.

## Audit chain

- giả thuyết client-message race bị falsify và được RCA production ở trên supersede;
- post-fix shift timeout là harness command/window race; bot được sửa chờ exact command ACK;
- authoritative rerun PASS;
- interim focused source review PASS;
- các lần quota 429 được giữ là transport failures, không phải verdict;
- retry sau quota reset: exact `cc/claude-opus-4-8` `PASS`, blockers/fixes rỗng;
- clarification: controlled gate approved, không cần candidate change; live chỉ nhận fix sau deployment được phê duyệt riêng.

Không claim project release-ready hoặc production verified.
