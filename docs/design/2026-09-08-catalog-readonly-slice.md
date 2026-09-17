# Catalog read-only — slice triển khai sau user “tiep tuc di”

Chọn mặc định A theo khuyến nghị trước đó; chưa có user visual acceptance. Scope bounded sử dụng schema tracked_items/item_history hiện có. Không tạo schema/custody/provider facts mới, không runtime hay grant/delete.

## Contract
- `/ig browser` vào catalog; `/ig browser <player>` cũ giữ nguyên. Catalog yêu cầu itemguard.gui + itemguard.search mỗi query/navigation; timeline thêm itemguard.history.others. Không tái dùng history hiện có như chain custody.
- Query catalog async trên SqliteConnectionOwner, chỉ SELECT. DTO immutable chứa scalar code/itemUuid/material/name/ownerName/location/lastSeen. Không deserialize snapshot/PDC/ghi dữ liệu.
- 36 rows +1 lookahead, keyset theo code ASC (không theo lastSeen mutable). Filter text literal name/code/material, exact ownerName tìm người liên quan trong bản ghi; category material fallback có nhãn suy từ material, custom classification UNKNOWN. Nguồn/HavenBags/custody chưa có adapter thì hiện rõ chưa hỗ trợ.
- Pagination không tuyên bố snapshot toàn DB: concurrent inserts trước cursor xuất hiện khi làm mới về trang đầu; sau cursor có thể ở trang sau. Không tự ghi tổng khi chưa count. Session previous cursors bounded 128 pages, tới cap yêu cầu thu hẹp bộ lọc, không truncate ngầm.
- Last owner không đủ chứng minh last physical holder: GUI dùng `Người liên quan (bản ghi)` + `Chưa xác minh người đang giữ`. Nơi thấy là last_location kèm thời gian, không tự suy storage type/absence. Chỉ source chưa xác định, không tự gán Vanilla.
- Hồ sơ giữ selected immutable row. History newest 100 existing query: ghi rõ giới hạn và retention, actor không phải holder; chi tiết từng event + back về đúng catalog state. Qua tay ai khóa Chưa hỗ trợ.
- Native GUI holder riêng, slot→action trên server không đọc PDC icon. Preview chỉ material + escaped/capped text, không copy original item PDC. Cancel click/drag/creative trước ItemListener HIGH; handle chỉ LEFT trên top inventory trong session đúng viewer. Scheduled navigation next tick kiểm tra active inventory, online, permission; late async không reopen closed/replaced GUI.
- Chat input: server captures one immutable pending request/token, timeout task 600 ticks (không hứa 30s dưới lag), quit/disable/open other inventory cancels. Hủy trả GUI cũ khi còn đúng session. Invalid input message và return, không query wildcard. Close before prompt; inventory-open do session hoàn tất phải remove pending trước.
- Không Bukkit access trên SQLite/async chat worker ngoài việc lấy actor từ event và dispatch main thread; UI main thread. Appended logs không log payload/player data.

## Verification
- Behavioral RED→GREEN SQLite page/filters/escaped wildcard; pure navigation/session policy; direct Bukkit boundary tests qua dynamic proxy khi khả thi; source wiring tests supplemental.
- Full Maven Java21 package. Review exact scoped source/tests với Opus5, parent verifies. Paper/client visuals chưa được duyệt/run.
- Design/model thể hiện tính năng thiếu, không đánh dấu full catalog/HavenBags/lifetime custody DONE.
