# Đối chiếu review độc lập 2026-09-17 — phát hiện nào là thật

Nguồn: `docs/reviews/2026-09-17-independent-review-of-the-fixes.md` (Claude Opus qua CLI, read-only,
brief tại `2026-09-17-fixes-review-brief.md`). Báo cáo đó là **ý kiến**, không phải bằng chứng. Mỗi
mục dưới đây là kết luận của tôi sau khi tự mở source đúng dòng nó chỉ.

Quy ước: **THẬT (sửa ngay)** = lỗi, không có lựa chọn sản phẩm nào biện minh được · **THẬT — cần
Thanh quyết** = lỗi có thật nhưng cách sửa đổi thứ người mua/người chơi nhận, thuộc quyền sản phẩm ·
**BÁC** = không đúng.

## THẬT — sửa ngay trong đợt này

**H-5 `general.enabled` là công tắc giả.** `ConfigManager.isEnabled()` (`ConfigManager.java:37-39`)
không có nơi gọi nào trong `src/main/java`; `config.yml:9` vẫn hứa "Enable/disable the entire
plugin". Admin gặp sự cố, làm đúng điều config bảo, restart, và không có gì thay đổi. Kiểm lại bằng
grep toàn repo: chỉ có `DiscordWebhook.isEnabled()` (khác lớp) xuất hiện. Sửa: đọc nó trong
`onEnable()` trước khi đăng ký listener, có log nói rõ plugin đang tắt theo config.

**H-3 im lặng khi hủy thao tác.** `ItemListener` hủy ở 5 chỗ (`:163-165` pickup, `:182-184` drop,
`:218-220` click, `:271-273` drag, `:287-289` use) mà không nói gì. `isIdentityReady`
(`ItemTrackingService.java:230-236`) trả `false` **vô điều kiện** ở lần chạm đầu sau restart, kể cả
khi reconcile đã xong trong cùng lời gọi, vì `IdentityReadinessCoordinator.reconcile` chỉ
`markReady` trong callback (`:65-79`) rồi `return false` (`:80`). Đây cũng là kết luận độc lập của
tôi trước khi đọc báo cáo. Sửa: nhắn người chơi, có throttle theo người chơi, qua `MessageManager`.

**M-1 sàn cooldown bằng đúng một chu kỳ thì vẫn vô tác dụng.** `AntiDupeSettings.java:44-46` sàn
`Math.max(configured, cycleMillis)`; phép so là `previous.created_at > createdAt - cooldown`
(`ItemSqliteRepository.java:1191-1193, 1229-1233`). Hai lần audit luôn cách nhau **≥** một chu kỳ,
nên `Δ ≥ cooldown` làm phép so thành sai ⇒ không chặn được gì. Sàn phải là **2 chu kỳ** mới có tác
dụng, và test `AntiDupeSettingsTest` phải assert `>` chứ không `>=`.

**M-2 rương đôi rơi khỏi mọi cooldown.** `ContainerListener.cooldownKey` (`:126-138`) chỉ nhận
`BlockState` hoặc `Entity`; holder của rương đôi là `org.bukkit.block.DoubleChest`, một
`InventoryHolder` **không** phải `BlockState` ⇒ rơi vào nhánh `identityHashCode` ⇒ key đổi mỗi lần
lấy holder ⇒ cooldown không bao giờ khớp cho loại rương phổ biến nhất. Sửa: nhánh riêng theo toạ độ
(`DoubleChest.getLocation()`), thêm case test.

**M-4 hàng ADOPTED tự nhận `created_at` là lúc adopt.** `ItemSqliteRepository.java:1544-1553` ghi
`created_at = updatedAt`, và `/ig check` in thẳng nó (`CheckCommand.java:84`). Khôi phục DB từ backup
rồi tra một thanh kiếm hai năm tuổi sẽ hiện "Created: hôm nay" — con số staff dùng để buộc tội.
Sửa: khi `last_action = 'ADOPTED'`, `/ig check` in "không rõ (adopted)" thay vì một mốc thời gian
sai. Không đổi schema, không đổi thứ tự sắp xếp.

**H-4 lời tuyên bố của C2 sai sự thật.** Comment ở `ItemTrackingService.java:227-229` nói đường
`unresolvedHolder` "cannot mint anything", nhưng `reconcileTagPublication`
(`ItemSqliteRepository.java:1445-1470`) gọi `adoptIdentity` khi journal chưa từng biết identity —
tức nó **tạo được** hàng canonical. Hành vi là đúng ý đồ C3; sai là câu văn mô tả nó. Sửa comment,
và ghi rõ ở đây để lần sau ai siết C3 phải biết đường ender-chest cũng mint.

**C-2 jar "English-only" vẫn in tiếng Việt.** Xác nhận bằng chính chuỗi trong source:
`ItemGuard.java:75` (`"  ItemGuard v… da kich hoat!"`) và `:114` (`"ItemGuard da tat!"`) — console,
mỗi lần start/stop; `CraftListener.java:41` — chat người chơi; `ItemTrackingService.java` có 4 dòng
log tiếng Việt mức SEVERE. `ItemGuardLite` không override `onEnable`/`registerListeners`, nên tất cả
chạy trong LITE. `package_lite.py` chỉ allowlist **tên file** ở gốc jar, không đọc nội dung `.class`
— đúng lỗ hổng mà H5 tưởng đã bịt. Sửa: dịch các literal này sang tiếng Anh (chúng không có key
trong `messages_*.yml`), và thêm một cổng quét literal tiếng Việt trong các lớp LITE chạm tới.

## THẬT — cần Thanh quyết (không tự đổi)

**C-1 (nặng nhất) mặc định chặn mọi craft công cụ/vũ khí/giáp.** Xác nhận: `CraftListener.java:32-42`
hủy khi policy trả `CANCEL_UNTAGGED_ELIGIBLE`; `CraftOutputPolicy.java:11` trả action đó khi output
chưa có identity mà **eligible**; `TrackingWorthinessPolicy` coi mọi `*_PICKAXE/_SWORD/_AXE/_HELMET…`
là eligible; `lite/config.yml` không set `tracking.*` nên mặc định `enabled=true`,
`track-non-stackable=true`. Kết quả trên server LITE mới cài: chế tạo cuốc đá bị hủy, kèm một câu
**tiếng Việt**. Đây là hành vi cố ý (fail-closed) và có controlled runtime gate cũ xác nhận
"event/cancel exact 1/1".
  - **Nhưng trang Spigot KHÔNG nói.** grep "craft" trong `release/spigot-upload/description.bbcode.txt`
    và `PREVIEW.html`: không có dòng nào. Mục "Straight about the limits" chỉ nói không xoá/không
    hoàn tác, chỉ track đồ không xếp chồng, bỏ qua ender chest, không đọc minecart/donkey bag.
    Nơi duy nhất công bố là `docs/release/ItemGuard-LITE-1.0.0-README.txt:171-172` — file **không**
    được gửi cho người mua (bài đăng tải jar trần, không archive).
  - Hai đường, Thanh chọn: **(a)** giữ fail-closed, thêm một dòng vào "Straight about the limits" +
    dịch câu từ chối; **(b)** cho craft chạy khi output chưa có identity và để scan/pickup gắn tag
    sau — rủi ro đã biết là cửa sổ item chưa được theo dõi.
**H-2 hopper + minecart chứa đồ: item kẹt vĩnh viễn, im lặng.** `HopperTransferPolicy.java:16-18`
hủy khi một trong hai đầu không hỗ trợ; `supports()` chỉ nhận holder là `Container` block state, nên
chest/hopper minecart và inventory ảo đều không hỗ trợ; `ContainerListener.java:74-77` hủy mà không
gửi gì. Trang chỉ nói không **đọc** minecart, không nói là **chặn**. Đường cho phép khi
`hasIdentity && identityReady` (đổi chỗ chứ không tạo bản sao) là hợp lý, nhưng đó là quyết định
sản phẩm.
**C-4 tag CORRUPT: không nhặt được ⇒ item rơi mất.** `PickupIdentityPolicy.java:9` +
`ItemListener.java:171-173` hủy pickup; `isIdentityReady` cũng trả false trước cả đường reconcile
(`ItemTrackingService.java:214-216`). Review không tìm được nguồn sinh CORRUPT **trong repo này**
(pipeline ghi cả hai key trong một `setItemMeta`), nên đây là phòng thủ cho dữ liệu ngoài (NBT
editor, plugin khác, DB cũ). Fail-closed là chủ ý; cái không biện minh được là **im lặng** — sẽ được
xử lý chung với H-3.
**C-3 cửa sổ crash giữa ghi tag và publish.** Nếu item đã mang tag, journal có hàng `PREPARED`, nhưng
canonical chưa kịp ghi, và item đã đổi ô/rời rương trước lần reconcile sau ⇒ `source_key` không khớp
⇒ nhánh "journal biết code" từ chối **vĩnh viễn** ⇒ mọi click/drop/use/pickup bị hủy, không một
thông báo. Cách sửa (so digest với hàng PREPARED rồi publish, không khớp thì abort để nhánh adopt xử
lý) là cùng loại quyết định với C3 mà Thanh đã chốt.
**H-1 luật hai epoch vẫn có false positive.** Xác nhận bằng SQL: `prior.scan_epoch < observations.scan_epoch`
không đòi epoch **liền kề**, và mỗi epoch vẫn trải dài thực tế, nên item được cầm-rồi-cất hai chu kỳ
liên tiếp vẫn cho `COUNT(*) >= 2` ở cả hai epoch ⇒ CONFIRMED sai cho một người vô tội. Siết theo
giao của hai tập vị trí là đúng, nhưng làm giảm thêm khả năng phát hiện dupe thật.

**C-3 cửa sổ crash giữa ghi tag và publish — BÁC đề xuất, hành vi là cố ý.** Báo cáo đề nghị: khi
không có canonical row nhưng có hàng `PREPARED` cùng `(code, item_uuid)` ở `source_key` khác, so
digest rồi publish ("đổi nguồn"). Đọc test của chính repo thì việc đó **đã bị chặn có chủ ý**:
`TagPublicationRelocationRepositoryTest.sameTaggedBytesAtDifferentPhysicalSourceCannotPublishPreparedIdentity`
(assertFalse, publication vẫn `PREPARED`, không có canonical row) và ba case anh em quanh nó. Lịch sử
trong `CURRENT_STATE.md` ghi rõ: independent source-loss review vòng 1–3 `BLOCK` dẫn tới
"**xóa unsafe relocation authority**" ngày 24/08. Lý do vẫn đúng: tag trên item **copy được** (NBT
editor, plugin khác, creative), nên "cùng bytes ở nguồn khác" chính là ca dupe, không phải ca khôi
phục. Đề xuất của báo cáo chính là tái lập quyền mà một review trước đã dỡ bỏ.
  - Hệ quả có thật và được giữ nguyên: crash đúng cửa sổ đó + item bị di chuyển ⇒ item không dùng
    được cho tới khi quay lại đúng nguồn cũ, hoặc staff can thiệp. Trước đây im lặng hoàn toàn; nay
    người chơi được báo (H-3). Đây là quyết định fail-closed nhất quán với C3 mà Thanh đã chốt.
**H-1 — có thật, nhưng KHÔNG sửa được bằng luật quan sát, và đây là lý do.** Tôi thử cả hai cách
báo cáo đề nghị:
  - "buộc prior là epoch liền kề": `twoLocationsInTwoConsecutiveEpochsAreConfirmed` (test hiện có)
    dùng đúng hai vị trí giống nhau ở hai epoch, nên siết kiểu này **không** loại được ca di chuyển —
    người chơi lặp lại y hệt thao tác lấy-rồi-cất vẫn cho hai vị trí giống nhau ở cả hai epoch.
  - "giao của hai tập vị trí ≥ 2": ca dupe thật (hai rương cố định) và ca lặp-thao-tác giống nhau đều
    cho giao bằng 2. Không phân biệt được.
  Kết luận: ở mức observation, "item di chuyển hai lần" và "hai bản sao" là **cùng một dữ liệu**. Muốn
  tách phải dùng thứ khác: `item_history` đã ghi `CONTAINER_TAKE/PUT`, `INVENTORY_MOVE`, `DROP/PICKUP`
  cho chính identity đó, nên luật đúng là "có sự kiện di chuyển giữa hai lần quan sát thì đừng kết
  luận". Đó là thay đổi spec (ảnh hưởng cả dupe thật), thuộc quyền Thanh — không tự làm trước khi
  đăng. Hiện giữ luật hai epoch, và ghi lại: cảnh báo là NOTIFY, không xoá/không tịch thu, staff có
  `/ig history` để thấy các lần di chuyển trước khi kết luận.
**C-1 — đã thêm công tắc, KHÔNG đổi mặc định.** `tracking.cancel-untracked-craft-output` (mặc định
`true` = hành vi cũ) cho admin tự tắt, và trang Spigot nay nói thẳng việc chặn craft + tên khoá
config. Lý do không tự đổi mặc định trước khi đăng: hành vi này có controlled runtime gate cũ xác
nhận và có README công bố, nên đảo nó là quyết định sản phẩm — Thanh chưa trả lời form nên tôi để
nguyên và mở đường đảo bằng 1 dòng. **Đề xuất của tôi: đặt `false`** trong `lite/config.yml` trước khi
đăng, vì chặn mọi cuốc/kiếm/giáp ở server mới cài là điều người chơi phát hiện trong phút đầu, và
plugin vốn đã chấp nhận gắn ID muộn trên Spigot; đổi 1 dòng trong 1 file, không cần build lại.
**H-2 — không đổi hành vi, đã công bố.** Hopper/minecart vẫn fail-closed. Sửa theo hướng "cho phép khi
item đã có identity" sẽ đảo một thuộc tính fail-closed đã được runtime-gate xác nhận, nên cần Thanh
chốt; trong lúc đó trang nói rõ: không đọc được minecart **và cũng không cho item đã track đi vào/ra**.
**C-4 — giữ fail-closed, nay có thông báo.** Tag `CORRUPT` vẫn bị từ chối (nguồn sinh corrupt không
tồn tại trong repo này), nhưng người chơi không còn bị nuốt im lặng.

## BÁC

Bác hai đề xuất **sửa** (không phải bác phát hiện): C-3 (đòi đổi nguồn publication — chính là quyền đã
bị gỡ sau review 24/08, có test chặn) và H-1 (hai luật siết được đề nghị đều không tách được ca di
chuyển khỏi ca dupe, đã thử cả hai). Chi tiết ở mục "THẬT — cần Thanh quyết" phía trên.

Ba phát hiện tôi tự kiểm trước khi đọc báo cáo (im lặng khi hủy, C2 không mint, LITE English-only theo
đường MessageManager) thì báo cáo trùng hai, và mục thứ ba — "LITE English-only" của tôi — **sai**,
chính là C-2: tôi chỉ kiểm đường `MessageManager` + `MessageLanguagePolicy` mà bỏ qua literal biên
dịch. Ghi lại vì đó là lỗi của tôi, không phải của báo cáo.

## Phạm vi chưa đụng trong đợt này

M-3 (`language: zh` im lặng thành tiếng Việt), M-5 (`plugin.yml` FULL hứa `itemguard.teleport`/
`itemguard.restore` không có command nào dùng; `api-version: 1.21.11` cao hơn API biên dịch 1.21.4),
M-6 (literal tiếng Việt trong `CheckCommand`/`FilterChatListener` của FULL), L-1 (`DuplicateDetector`
còn giữ luật trước-C1, hiện chỉ test gọi) — đều **FULL-only**, không ảnh hưởng jar LITE đang chờ
đăng. Đã ghi nhận, xử lý ở đợt FULL.

## Việc đã làm sau khi đối chiếu

Xem `CURRENT_STATE.md` mục `2026-09-17 — review of the fixes` để biết cái nào đã sửa kèm test, cái
nào chờ Thanh quyết, và candidate nào thay thế.
