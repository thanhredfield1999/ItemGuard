# Đối chiếu review #2 (các thay đổi trong ngày) — 2026-09-17

Nguồn: `docs/reviews/2026-09-17-review-of-the-post-review-changes.md` (Claude Opus, read-only, brief:
`docs/reviews/2026-09-17-review-brief-changes-after-review.md`). Báo cáo là **ý kiến**; mỗi mục dưới
đây tôi tự mở source/đo lại trước khi sửa. Kết quả: 5 HIGH, 8 MEDIUM, 5 LOW — **không bác mục nào**;
ba mục HIGH nhắm đúng vào hai cổng tôi vừa viết, và một trong đó nặng hơn báo cáo nói.

## HIGH

**H-1 — parser `.class` bỏ sót `CONSTANT_MethodHandle` (tag 15).** Đo lại trên jar thật:
`class_strings(ItemGuard.class)` trả **82** hằng số Utf8 trong khi `javap -v` có **338** — hàm dừng ở
tag 15 sau 134/712 entry, rồi `return strings` im lặng, còn `scan_jar` vẫn đếm class đó là "đã kiểm".
Nặng hơn báo cáo (nó chỉ suy luận từ JVMS). **Đã sửa:** xử lý tag 15, và tag lạ giờ **raise**
`UnparsedClass` → `scan_jar` báo "unparsed class" như một finding. Thêm 3 test: tag 15 không che chuỗi
phía sau, class không đọc được phải bị báo, và `ItemGuard.class` thật phải cho > 300 chuỗi.

**H-2 — cờ ngôn ngữ miễn trừ cả file.** Đúng, và tệ nhất ở `LiteCommand.java` (bề mặt lệnh duy nhất
của LITE). **Đã sửa:** luật theo **statement** (`;` `{` `}`), cộng ba dạng hợp lệ khác trong repo:
ternary xuống dòng (`vietnamese\n ?`), block có header mang cờ (`if (vietnamese) {`), và cặp EN+VI
trong cùng câu (`new Element("BOOK", "EN", List.of("VI"))`). Trong lúc làm tôi thử thêm `message(`/
`text(` làm dấu hiệu song ngữ và **test cũ bắt lỗi ngay**: `message("VI only")` một tham số là chuỗi
tiếng Việt trần, nên hai tên helper đó bị loại khỏi luật. Còn lại không chứng minh được: nhánh EN có
thật sự với tới được không — đã ghi rõ trong docstring thay vì tuyên bố cổng là đầy đủ.

**H-3 — `referenced_from_outside` bỏ sót wildcard import và tên trần.** Đúng: `ItemGuard.java:6`
`import com.itemguard.commands.*;` không khớp `[\w.]+`, và `registerCommands()` gọi tên trần. Hệ quả:
14 file trong `commands/` chưa từng bị hỏi. **Đã sửa:** regex wildcard + so tên trần, và 14 file đó
được **khai báo từng cái** là SKIPPED với lý do thật (`ItemGuardLite.registerCommands()` override,
chỉ cài `LiteCommand`). Từ nay thêm một class lệnh mới là cổng đỏ cho tới khi có người khai báo.

**H-4 — `last_action = 'ADOPTED'` bị ghi đè ngay lần tương tác đầu.** Đúng: fix M-4 của tôi chỉ đúng
trong cửa sổ trước lần nhặt/thả đầu tiên. **Đã sửa:** `created_at = 0` là dấu bền của hàng adopted
(không có gì sắp xếp theo cột này), `/ig check` in "unknown (adopted)" khi `created_at <= 0`, và
`TagIdentityAdoptionRepositoryTest` assert `0L`.

**H-5 — sàn "hai chu kỳ" giả định các lần audit cách nhau đúng một chu kỳ.** Đúng: sweep tràn chu kỳ
hoặc TPS < 20 làm khoảng cách thật lớn hơn cửa sổ, và cửa sổ lại vô tác dụng. **Sửa theo cách khác
đề xuất:** thêm điều kiện "identity đã được báo ở đúng epoch liền trước thì không báo lại", **chỉ bật
khi cooldown > 0**. Lý do không dùng epoch đếm thay cho mili giây, và lý do phải có cổng `> 0`: test
`repeatedDetectionsOfOneItemCountAsOneDistinctDuplicateIdentity` (viết cho C1, dùng `cooldown = 0`)
pin nghĩa đã công bố "0 = không throttle, báo mỗi epoch" — lần thử đầu của tôi phá đúng test đó và
đó là cách tôi biết ràng buộc này. Hai test mới phủ cả hai nửa: audit liền sau bị chặn (kể cả khi
cửa sổ 1 ms), và audit cách hai nhịp vẫn báo lại.

## MEDIUM

**M-1 — một câu cho hai lời từ chối ngược nhau.** Đúng. **Đã sửa:** `craft-refused` (chính sách
server, nêu tên khoá config) và `craft-refused-tagged` (sao chép identity), `CraftListener` chọn theo
`action`. Đồng thời bỏ chữ "yet" — từ chối này là vĩnh viễn cho tới khi admin đổi config.
**M-2 — nhánh chết `ItemListener:268-295`.** Đúng (điều kiện `:251` luôn đúng sau `return` ở `:249`).
**Đã xoá**, kèm hai field/import không còn ai dùng. Hệ quả thật được ghi lại: item chưa tag, đáng
track, bị click trong rương thì **không** được gắn tag ngay — sweep làm việc đó. Và test
`DoubleChestPhysicalSourceWiringContractTest.clickPublicationAndScheduledObservationUseSamePhysicalResolver`
đã **pin một bất biến mà code chết không bao giờ chạy**: nó đỏ khi tôi xoá, nên tôi viết lại thành
`theContainerSweepIsTheOnlyPathThatPublishesAContainerSlot` và nói rõ vì sao.
**M-3 — test "cancellation is never silent" không kiểm điều nó tuyên bố.** Đúng, và còn một chỗ hủy
im lặng thật (`TAG_SOURCE` khi nhặt). **Đã sửa:** nhánh đó nay báo `pickup-tagging`; test đếm cả ba
wrapper thông báo và so với 5 guard + 1 corrupt + 1 tag-source.
**M-4 — cổng listing không thấy hash rút gọn.** Đúng, và đó chính là dạng đã lừa bản trước. **Đã
sửa:** nhận token 8–63 hex nếu nó là tiền tố của một digest đầy đủ có trong phạm vi quét; tên fixture
(`itemguard-lite-isolated-8ca467a3aef8`) không phải tiền tố của digest nào nên không bị báo oan. Thêm
luật "được nhắc hash cũ nếu **cùng dòng** nói nó đã bị thay" để lịch sử rebinding vẫn viết được.
**M-5 — `release/*.zip` nằm ngoài cổng.** Đúng. **Đã sửa:** quét cả thư mục `release/`, đọc entry
text trong archive, **và băm cả `.jar` bên trong archive** (đúng lỗi "jar cũ trong gói bàn giao" đã
xảy ra một lần); zip cũ đổi tên thành `2026-09-15-…` để thành bản lưu có ngày.
**M-6 — `enabled: false` để Bukkit vẫn coi plugin là enabled trong khi `getApi()` null.** Đúng.
**Đã sửa:** `disablePlugin(this)` trước `return` (onDisable null-safe cho plugin chưa mở gì).
**M-7 — dòng disable mới trùng chữ với dòng shutdown.** Đúng; token của `reload.py` yếu đi. **Đã
sửa:** câu guard đổi thành "ItemGuard will not start" nên `"ItemGuard is disabled."` chỉ còn do
`onDisable` phát ra.
**M-8 — `identity-not-ready` bảo "thử lại" cho một từ chối vĩnh viễn.** Đúng. **Đã sửa:** key riêng
`identity-corrupt`, nói thẳng tag hỏng và cần staff.

## LOW

**L-1** `CatalogText` chạy trên LITE (`SqliteConnectionOwner` gọi không phân edition) → chuyển sang
`SCANNED_INSIDE_EXEMPT`. **L-2** trần mềm của map thông báo → nay bỏ nửa cũ nhất khi vẫn chạm trần
(đúng chiến lược của `ContainerListener`). **L-3** chỉ `messages_en.yml` được ghim → thêm test ba
file phải cùng tập key. **L-4** marker `RECORD` quá rộng → đòi `RECORD:`. **L-5** comment về sàn đọc
như "đã chặn được" → viết lại cùng với H-5.

## Lỗi của chính tôi mà quá trình này phát hiện

1. **Guard `general.enabled` bản đầu đặt sau `loadManagers()`** — nghĩa là DB đã mở, lock đã lấy,
   `recoverPendingReclaimClaims` đã chạy, trong khi câu log tôi viết nói "no database work runs".
   Tách `loadConfigManagers`/`loadRuntimeManagers` + test `StartupEnableGuardContractTest`.
2. **Test tràn số của tôi** (`anAbsurdScanIntervalDoesNotOverflowIntoANegativeFloor`) đỏ ngay lần
   chạy đầu → lộ ra clamp phải đặt **trước** phép nhân.
3. **Luật `message(`/`text(`** của cổng tiếng Việt bị chính test cũ bắt là quá rộng.
4. **Test harness đỏ vì pin chuỗi cũ** hai lần (Việt→Anh, rồi đổi câu craft) — cả hai lần harness
   từ chối pass cho tới khi pin khớp, đúng như thiết kế.

## Trạng thái sau đối chiếu

Candidate `80fc610b1e1f3be4c3abb7a163d899fce5d285c23578449dff6b27e08cd167ab`, **851/851 test Java**.
Các cổng offline và runtime được chạy lại trên candidate này; chi tiết trong
`docs/release/LITE_RELEASE_GATES.md` và `CURRENT_STATE.md`.
