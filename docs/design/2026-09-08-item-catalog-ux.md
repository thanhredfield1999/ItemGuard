# ItemGuard — Kho tra cứu vật phẩm và GUI điều tra

Trạng thái: PROPOSED — chờ duyệt thiết kế; không đổi Java/schema/runtime.
Ngày khảo sát: 2026-09-08. Repo `E:/AI.WORK/ItemGuard`, branch `main`, HEAD `1b7d055`; working tree có thay đổi từ các slice trước.

## 1. Yêu cầu của Thanh

Có kho tra cứu các item trên hệ thống, biết plugin nguồn, phân loại giáp/kiếm/các loại khác; hỗ trợ HavenBags và nơi chứa; lịch sử có thời gian chi tiết, những người từng cầm và người giữ gần nhất. GUI Minecraft dễ click, không đòi nhớ cú pháp hoặc thao tác nâng cao.

Kho ở đây là chỉ mục chỉ đọc, KHÔNG phải kho cấp lại đồ. Nhấn icon không nhận một bản sao item. Yêu cầu này chưa được triển khai đầy đủ trong plugin hiện tại.

## 2. Khảo sát exact-current — OBSERVED

- `src/main/java/com/itemguard/gui/PlayerBrowserGUI.java:45–61,165–199`: browser theo người chơi; lọc substring tên Bukkit material, icon có lore dài và click mở history. Chưa có bộ lọc plugin/nhóm/nơi chứa trong luồng này.
- `src/main/java/com/itemguard/gui/HistoryGUI.java:142–167,337–375`: history có hành động, người chơi, vị trí, thời gian tuyệt đối/tương đối, màn chi tiết và quay lại.
- `src/main/java/com/itemguard/data/ItemData.java:16–27`: code/UUID, owner, material/name/lore, created/last-seen/action/location. Không có trường plugin nguồn hoặc phân loại nghiệp vụ.
- `src/main/java/com/itemguard/data/ItemHistory.java:13–34`: một actor và location mỗi event; không có cặp from-holder/to-holder chuẩn hóa. Không thể coi mọi actor trong log là người sở hữu.
- `src/main/java/com/itemguard/persistence/ItemSqliteRepository.java:150–199`: latest action/location có thể cập nhật owner. Trường này chưa đủ để chứng minh chuỗi chuyển tay hoặc người đang cầm tại thời điểm truy vấn.
- `ItemSqliteRepository.java:749–843`: history theo code có cap 1.000; theo player cap 500 qua current owner; item theo owner cap 200. Đây không phải toàn bộ item từng qua tay người đó.
- `ItemSqliteRepository.java:847–891`: search owner name/code/item name cap 100. `SearchCommand.java:146–155` báo số lượng list, không phải tổng toàn hệ thống. Catalog mới phải phân trang DB thực, không chỉ chia trang trên một list đã bị cắt.
- `src/main/resources/plugin.yml:8`: softdepend hiện chỉ WorldGuard. Tìm source/tests chưa thấy HavenBags, MMOItems, ItemsAdder, Nexo, Oraxen hoặc catalog category/source-plugin integration.
- `src/main/resources/config.yml:23–32`: identity riêng chỉ cho non-stackable; stackable fail-closed. Không thể quảng bá theo dõi từng viên/nguyên liệu stackable ở hiện trạng này.
- `CURRENT_STATE.md:94`, `docs/RISK_REGISTER.md` IG-R011/015: external storage/full coverage còn mở. Evidence GUI cũ không áp dụng cho thiết kế mới.

## 3. Hợp đồng dữ liệu cần nói đúng

### Phạm vi catalog

Màn hình mặc định ghi `Kho tra cứu` + `Vật phẩm đã ghi nhận`, không ghi `Tất cả vật phẩm đang tồn tại` khi coverage chưa đủ. Tách:

1. Bản ghi identity: một item UUID/code đã publish; có thể nay ở nơi chưa kiểm tra hoặc đã bị hủy.
2. Quan sát vật lý: một hoặc nhiều bản sao/vị trí quan sát được, có thời điểm và nguồn.
3. Tình trạng: `Đã thấy lúc…`, `Dữ liệu cũ`, `Chưa rõ`, `Nhiều bản quan sát`, `Đã hủy có bằng chứng`.

Item ngoài phạm vi/stackable hiện ghi `Chưa theo dõi từng đơn vị`. Không quét/force-load cả world để làm con số đẹp. Item chưa từng quan sát không tự xuất hiện trong chỉ mục. Backfill offline nếu muốn là scope riêng, có dry-run và ngân sách I/O.

### Nguồn plugin ≠ nơi lưu ≠ plugin từng sửa item

- `Nguồn định nghĩa`: ví dụ MMOItems + template ID, hoặc HavenBags cho chính chiếc túi.
- `Plugin đã tác động`: ví dụ BastionForge nâng cấp item MMOItems, không thay nguồn gốc bằng last writer.
- `Nơi chứa`: Player, chest, ender chest, HavenBags, vault, auction, ground; mỗi capability có trạng thái riêng.
- Ưu tiên adapter API/identifier đúng version; PDC namespace chỉ là bằng chứng gợi ý, không chứng minh creator. Không suy nguồn từ display name/lore.
- `Chưa xác định` khác `Vanilla`. Thiếu adapter không tự gán Vanilla. Nhiều nguồn xung đột phải hiển thị, không chọn ngẫu nhiên.

### Phân loại

Các nhóm click: Vũ khí (kiếm/rìu chiến/cung/nỏ/đinh ba…), Giáp (mũ/áo/quần/giày), Công cụ, Phụ kiện (khi adapter biết), Túi & hộp, Vật phẩm khác, Chưa phân loại.

Adapter định nghĩa loại ưu tiên hơn material fallback; một custom PAPER có thể là vũ khí. Rìu mặc định công cụ nếu không có thông tin custom; cho phép nhãn phụ, không tạo hai bản ghi. Gán nhóm là phân loại UI, không thay policy identity/stackable.

### Người chơi và thời gian

- `Người thao tác`: actor của event; mở rương không nghĩa là cầm mọi item trong rương.
- `Người giữ được ghi nhận gần nhất`: UUID + tên tại thời điểm event + thời gian; không suy quyền sở hữu hợp pháp. Card chỉ dùng một nhãn người `Giữ gần nhất: <tên>`; `Thao tác: <tên> · <hành động>` chỉ nằm trong timeline. Không đặt ba nhãn người cạnh nhau trên card.
- `Người đang giữ`: chỉ có khi một probe exact identity chứng minh tại thời điểm ghi rõ; data stale vẫn phải hiện timestamp.
- `Chủ túi được cấu hình` khác `người cầm túi`, khác `người thao tác trong túi`.
- Timeline giữ sự kiện raw, sequence/event ID tie-break, event time + recorded time nếu khác, timezone. Dùng `Bắt đầu theo dõi` nếu không biết thời điểm item thật sự được tạo.
- View `Qua tay ai` xây từ bằng chứng custody/pickup/transfer có đối chiếu, cho phép A→B→A và khoảng trống. Không ghép A→B chỉ vì hai scan cách xa; ghi `Chuyển tiếp chưa rõ`.
- Không dùng retained history như lifetime hoàn chỉnh: hiển thị mốc bắt đầu, phần đã hết retention, gaps/offline/unavailable.

## 4. HavenBags — OBSERVED API, integration chưa VERIFIED

Nguồn chính thức đã đọc:
- https://github.com/Valorless/HavenBags — main đang trỏ `ed33cc849191ca0ece6bac6ce53081bdb4c2911d` trong lần khảo sát; không phải version đang cài của Thanh.
- https://valorless.github.io/HavenBags/valorless/havenbags/api/HavenBagsAPI.html — Javadocs 1.43.0.
- https://valorless.github.io/HavenBags/valorless/havenbags/events/BagCloseEvent.html

API được công bố: `isBag(ItemStack)`, `getBagUUID(ItemStack)`, `getBag(String)`, `getBagData(ItemStack)`, `getPlayerBags(String)`, `getBagsOnPlayer(Player)`, `getOpenBagsUUIDs()`, `getBagOwner(String)`, `bagOpenBy(String)`; event open/close/create/delete. BagCloseEvent có player/inventory/bag item/bag data; đây không phải cam kết transaction đã durable.

Thiết kế adapter đề xuất:
- Hai đối tượng riêng: chiếc túi vật lý và storage bag ID. Item con giữ identity/nguồn riêng, liên kết tới bag ID + slot tại thời điểm quan sát.
- GUI `Nơi lưu` ghi: HavenBags → tên túi → bag ID rút gọn → slot → thời gian. Chi tiết có ID đầy đủ và loại bằng chứng.
- Không gọi GUI mở túi thật để preview; chỉ render bản chụp read-only. Không sửa trực tiếp DB/cache hoặc force-close túi của player.
- Cần pin version cài thật rồi kiểm chứng source semantics: thread affinity, live cache so với durable storage, ownership vs possession, unbound/shared/ethereal bags, event ordering, pagination/bounds, lỗi/missing plugin, close/save/restart.
- API có `getPlayerBags` KHÔNG chứng minh nó liệt kê mọi túi đang cầm hoặc mọi túi public/offline. Không có chứng cứ absence toàn server chỉ từ kết quả rỗng.
- Version chưa hỗ trợ/error/timeout → `Chưa kiểm tra được`; tuyệt đối không suy đã mất hoặc cho reclaim. Adapter đọc và adapter sửa là hai capability khác nhau.

## 5. GUI Minecraft — hai phương án

Chỉ dùng chest inventory 54 slots (9×6), không phụ thuộc resource pack. HTML là mô phỏng luồng, không phải web admin cần triển khai.

### A — Tra cứu trực tiếp (khuyến nghị)

Entry staff `/ig browser` giữ tương thích; catalog là màn chính mới sau approval. Từ catalog: click item → hồ sơ; click Lịch sử/Qua tay ai/Nơi lưu → chi tiết. Trường hợp thông thường không cần command phụ.

Slot contract (zero-based):
- 0: phạm vi/coverage (không điều hướng); 1: tìm kiếm; 2: loại; 3: plugin nguồn; 4: người chơi; 5: nơi lưu; 6: trạng thái; 7: sắp xếp; 8: xóa lọc (chỉ có khi đang lọc).
- 9–44: 36 mục/trang. Không nhồi hết lore gốc vào mọi icon.
- 45: quay lại duy nhất; 47: trang trước; 49: trang/phạm vi kết quả; 51: trang sau; 53: đóng. 46/48/50/52 trung tính làm vùng đệm, tránh lật trang nhầm thành đóng/quay lại. ID/code giữ nguyên khi thay màn.
- Click bộ lọc mở menu lựa chọn có nhãn; không dùng right-click để cycle giá trị vô hình. Chọn xong tự quay lại danh sách. Lọc người: phân biệt `giữ gần nhất`/`từng giữ` trong submenu, không nhập UUID bắt buộc.
- Chỉ text query/tên player cần nhập; Minecraft đóng GUI, dùng chat prompt có Hủy/timeout/nhắc filter đang chọn rồi trả về đúng trang/filter/sort. Player thoát hoặc mở GUI khác thì hủy im lặng, không mở lại màn cũ. HTML dùng ô nhập thay thế để demo; không hứa ô text native trong chest.
- `Từng giữ` phải có query keyset-paginated theo custody actor/holder UUID, không tái dùng current-owner join/cap 500. Chưa có query thì submenu ghi `Chưa hỗ trợ`, không trả kết quả thiếu như đầy đủ. Bản mẫu hiện lọc `Giữ gần nhất`, lịch sử minh họa nằm trong hồ sơ.
- Giữ query/filter/sort/page/selection khi quay lại; filter thay đổi reset page. Tránh async response cũ mở lại GUI đã đóng.

### B — Chọn việc trước

Trang đầu chỉ có `Tra vật phẩm`, `Theo người chơi`, `Theo nơi lưu` và gợi ý ngắn. Sau lựa chọn dùng chung catalog/hồ sơ của A. Dễ học hơn cho staff hiếm dùng, nhưng thêm một click cho mọi lần duyệt thông thường. Không nhân bản ba engine query.

### Hồ sơ item

Header: icon bản chụp + tên + code. Hiện ngay nhóm/nguồn, người giữ gần nhất, nơi thấy gần nhất, thời điểm và trạng thái bằng chứng.

Ba nút chính: `Lịch sử`, `Qua tay ai`, `Nơi lưu`; `Thông tin kỹ thuật` riêng khi cần UUID/provider/version/evidence. Nút `Quay lại` ở cùng vị trí mọi màn. Click tên người lọc item liên quan trong phạm vi quyền; click storage chỉ mở hồ sơ chỉ đọc.

Card/lore catalog tối đa khoảng 6 dòng hữu ích: code; loại · nguồn; người giữ gần nhất; nơi thấy; thời gian/trạng thái; hướng dẫn click. Lore gốc đầy đủ ở hồ sơ, bọc dòng và giới hạn độ dài. Không truyền PDC điều khiển GUI từ item snapshot; icon được dựng an toàn theo session binding.

Màu + chữ: xanh lục `Đã thấy`, vàng `Dữ liệu cũ`, xám `Chưa rõ`, đỏ `Cần kiểm tra`; màu không là tín hiệu duy nhất. Dùng icon Bukkit phù hợp: compass/search, sword/type, book/source, player head/person, chest/storage, clock/history, barrier/close. Tiếng Việt giữ dấu; literal code/UUID/command không biến đổi typography.

## 6. Safety và error states

- Catalog staff-only; giữ luồng player/self history riêng. Không cho player biết tọa độ/chi tiết người khác nhờ đoán code. Check permission cả query lẫn navigation, không chỉ nút vào.
- MVP catalog không chứa lấy đồ/teleport/xóa/thu hồi. Những action destructive về sau ở màn quản trị riêng, permission + evidence + confirmation và audit.
- Cancel inventory click/drag/shift/number-key/double-click/collect cả top/bottom khi GUI mở; bao gồm middle-click/creative clone và InventoryCreativeEvent (cần kiểm chứng fixture). Không để preview item lọt vào túi hoặc trở thành authority tag.
- Query server-side async có giới hạn; paging ổn định với tie-break unique ID. Snapshot session/cursor tránh trùng/nhảy trang khi có event mới; refresh rõ ràng. Không materialize toàn DB lên main thread.
- Empty do filter: `Không có kết quả phù hợp` + xóa lọc. Query failure: `Không tải được` + thử lại, KHÔNG báo rỗng. Unavailable storage/retention gap/stale/conflicting holders mỗi loại có nhãn. Trang có adapter lỗi/coverage gap ghi `Kết quả chưa đầy đủ — <nguồn> chưa kiểm tra được` ở subtitle/coverage và chỉ báo trang; không biến lỗi riêng adapter thành database failure toàn trang. Không dùng kết quả thiếu để khẳng định vắng mặt.
- Exact count chỉ ghi khi query count đã chạy cùng phạm vi dữ liệu; nếu chưa có tổng, ghi `36 kết quả trên trang này · còn trang sau`, không giả tổng.

## 7. Acceptance trước implementation/runtime

- Catalog phân biệt nguồn/loại/nơi lưu và identity count/physical observations.
- Tìm code/name/player, lọc kết hợp, phân trang vượt các cap cũ, clear/empty/error/back/closed state hoạt động.
- Timeline có timezone, thứ tự ổn định, gaps/retention; chain A→B→A không mất lần quay lại; actor open-container không thành owner.
- Duplicate observations không chọn một holder duy nhất hoặc tự xóa đồ.
- HavenBags: định danh túi vs nội dung, unbound/borrowed/ethereal/offline/unavailable, stale cache/close-save/restart cần test đúng version.
- Pure presenter/navigation/query contracts + SQLite migration tests trước Paper. Native GUI click/drag/shift/number-key và user visual acceptance phải trên fixture mới được duyệt; HTML không thay thế.

## 8. Quyết định cần duyệt

Đề xuất A làm mặc định, B là lựa chọn entry thay thế; scope triển khai đầu là catalog read-only cho item đã ghi nhận, không cấp/xóa đồ. Chốt schema/capability contract sau khi duyệt hướng này. HavenBags exact installed version và danh sách custom-item plugins cần inventory read-only khi bắt đầu adapters; chưa tự đoán plugin đã cài.

Thiết kế này không sửa thứ tự runtime safety gates trong roadmap, không hoàn tất P0/P1/P2, không tái dùng attempt/receipt. Bản mẫu dùng dữ liệu MINH HỌA, không lấy dữ liệu người chơi hay server thật.

## 9. State details và phạm vi bản mẫu

Parent clarification sau review R2 (không phải thay schema):
- Catalog có logical parent là trang chọn việc; slot45 về trang đó. Ở chính trang chọn việc, slot45 disabled; không tạo vòng back catalog ↔ home.
- Quay lại từ submenu không thay giá trị. Chỉ click lựa chọn/submit mới áp dụng filter.
- Chat timeout khi player online và còn giữ đúng session request: trả lại trang/filter cũ và báo hết thời gian. Nếu player thoát/chuyển GUI thì hủy im lặng; không tự mở lại.
- Empty không filter: `Chưa có vật phẩm được ghi nhận`, chỉ giải thích phạm vi hoặc cho refresh; không có nút xóa lọc vô nghĩa. Có filter mới dùng empty-filter.
- `Qua tay ai` bản mẫu có scenario synthetic để duyệt UX, không phải adapter/query đã chạy. Native feature chưa có custody query thì disabled `Chưa hỗ trợ`.

Bản mẫu A/B dùng chung read-only data model MINH HỌA, khác điểm vào. Có thể tìm mã `K7Q2MX` để xem scenario An → Minh → An → Lan, khoảng trống event, túi của Minh do Lan cầm và item nguồn MMOItems. Không có tên/UUID/dữ liệu server thực.

Hai file: `sketches/catalog-direct/index.html`, `sketches/catalog-guided/index.html`. HTML dùng nút chữ/icon luôn hiện và lore panel bên cạnh để giải thích; Minecraft thật dùng item icon/hover lore và không có panel HTML.

Review R2: `docs/reviews/2026-09-08-catalog-ux-r2.json` ghi `PROPOSED_DESIGN_OK` cho văn bản snapshot trước các clarification mục này, không phê duyệt runtime/schema. Verification prototype xem `sketches/verification.json`; tầng `OFFLINE_DOM_ONLY`. Browser bị chặn ở quyền Chrome remote debugging, chưa đo layout/pixel và chưa có user visual acceptance.
