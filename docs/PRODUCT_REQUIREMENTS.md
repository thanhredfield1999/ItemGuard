# ItemGuard — Product Requirements

Nguồn mô tả: yêu cầu người dùng cung cấp ngày 2026-08-23.
Trạng thái: `PROPOSED`; source hiện tại chưa đáp ứng đầy đủ.

## Mục tiêu

Theo dõi các vật phẩm được bảo vệ bằng identity riêng; cung cấp audit/history, tìm kiếm/tịch thu, anti-dupe fail-closed và quy trình hỗ trợ lấy lại đồ không tạo bản sao mới.

## Identity và tagging

- Vật phẩm đủ điều kiện phải được gắn identity trong tối đa 1 giây theo budget cấu hình.
- Identity canonical là UUID 128-bit trong PDC; mã hiển thị ngắn dùng cho command/search và phải unique.
- Không tái sử dụng identity theo material/name/lore giống nhau.
- MVP ưu tiên non-stackable item. Stackable item là release gate riêng vì split/merge/count semantics.
- Placeholder hiển thị ID phải dùng namespace rõ ràng; `%id%` nguyên bản có nguy cơ xung đột và cần adapter PlaceholderAPI được xác minh.

## Anti-dupe

- Kiểm tra liên tục bằng event observations + bounded periodic reconciliation, không scan toàn world/chunk.
- Một duplicate `CONFIRMED` cần ít nhất hai physical observations đồng thời của cùng identity, khác provenance/container/slot/entity và còn hợp lệ sau revalidation.
- Trạng thái: `CLEAN`, `SUSPECTED`, `CONFIRMED`, `QUARANTINED`, `RESOLVED`.
- Mặc định chỉ cảnh báo/quarantine. Xóa/phạt tự động là release gate; không được dựa vào history count.
- Cảnh báo admin in-game và optional Discord webhook; notification phải có acknowledgement (`readdupe`) và chống spam.
- Mọi destructive action phải có audit record: actor, reason, evidence, target, timestamp, result.

## Lịch sử và reclaim

- Theo dõi create/pickup/drop/use/craft/transfer/container/death và canonical owner/location khi có thể.
- Player xem lịch sử trong retention window cấu hình, mặc định 20 ngày.
- `/matdo check`: danh sách vật phẩm đủ điều kiện hỗ trợ lấy lại.
- `/matdo sos <id>`: request reclaim có cooldown, giới hạn số lần, idempotency và approval/state machine.
- Trước khi cấp, kiểm tra player inventories/ender chest và mọi adapter được hỗ trợ như PlayerVaults/zAuctionHouse. Adapter unavailable/timeout phải deny, không coi là absent.
- Reclaim phải cấp full-fidelity ItemStack snapshot; không dựng lại chỉ từ material/name/lore.

## Hạn chế chuyển vật phẩm

Yêu cầu gốc: item có ID không được drop hoặc bỏ chest, chỉ được trade để tránh scam.

Trạng thái: cần contract UX chi tiết trước khi enable. Thiết kế phải bao phủ death drops, hopper, shulker, ender chest, anvil/smithing, crafting, admin bypass, plugin inventories và một trade flow được xác minh; không chặn item nếu chưa có đường chuyển giao hợp lệ.

## Admin commands `/finditem`

- `startfinding <id> <time>`: theo dõi tìm item trong thời hạn.
- `starttaking <id> <time>`: tìm và quarantine/tịch thu khi có evidence.
- `stopfinding <id>`.
- `listfinding <page>`.
- `removefinding <id>`.
- `clearfinding` có confirmation/audit.
- `reload`: chỉ reload-safe config; setting khác báo restart-required.
- `givefoundid <id>`: cấp item đã quarantine/found theo transaction an toàn.
- `givenewid <id>`: clone snapshot với identity mới, admin-only và audit rõ lý do.
- `giveoldid <id>`: chỉ được phép khi canonical absence đã chứng minh; nếu không phải deny.
- `readfinding`, `readdupe`.
- `addbackitem <player> <id>`, `removebackitem <player> <id>`, `showbackitem <player>`.
- `checktps`: metrics scan queue/budget/duration, không chỉ TPS server.
- `infoplayer <player>`, `infoitem <id>`, `infodupe <id>`.

Tất cả command destructive/cấp đồ cần permission riêng, sender validation, confirmation khi phù hợp và audit.

## GUI và thông báo

- GUI admin hiển thị item snapshot, identity, trạng thái, owner/history, observations, duplicate evidence và action audit.
- GUI player hiển thị reclaim eligibility/cooldown/quota và lý do bị từ chối.
- Pagination có giới hạn; DB query không block main thread; Bukkit inventory creation/mutation chạy main thread.

## Persistence

- Durable schema version + migration + future-version rejection + corrupt-data handling.
- Các bảng logical: item identity/snapshot, observations, history, search jobs, duplicate cases, reclaim grants/claims, notification acknowledgements, audit log, plugin metadata.
- Snapshot phải versioned, có size limit/checksum và bảo toàn dữ liệu cần thiết.
- Không một JDBC Connection dùng đồng thời nhiều thread.
- Shutdown phải ngừng nhận work mới, drain/flush bounded, rồi close; restart tests bắt buộc.

## Tích hợp

- PlayerInventory/ender chest: built-in adapter.
- PlayerVaults và zAuctionHouse: adapter theo public API/version thực tế; capability/readiness rõ ràng.
- PlaceholderAPI: optional adapter với placeholder namespace không xung đột.
- WorldGuard/Vault chỉ giữ nếu có use case cụ thể và API đúng version; không dùng reflection đoán contract cho quyết định destructive.

## Non-functional

- Không unbounded scan trên server-thread tick.
- Scan có budget/cursor, metrics p50/p95/max và queue/backlog.
- Không force-load chunk để tìm item.
- Không log secret, private payload hoặc full item data nhạy cảm.
- Không claim runtime/production verified từ unit/build.

## Acceptance test tầng cao

1. Một item hợp lệ đi qua pickup/use/drop/pickup nhiều lần không thành dupe.
2. Hai physical copies cùng identity trong hai slot/container đồng thời tạo case `CONFIRMED` sau revalidation.
3. Adapter PV/AH unavailable chặn reclaim.
4. Hai request reclaim concurrent chỉ một request có thể commit.
5. Restart bảo toàn identity/snapshot/history/case/reclaim; corrupt/future schema không bị overwrite.
6. Inventory scan tag đúng slot và không ghi đè main hand/off-hand/armor.
7. Workload kiểm soát không full-world scan và nằm trong budget đã định.
8. Destructive mode không thể enable nếu release gate/evidence chưa đạt.
