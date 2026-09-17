# Review đối kháng — ItemGuard, các thay đổi 2026-09-17 (read-only)

> **Lưu ý về tính thời điểm:** `src/main/java/com/itemguard/ItemGuard.java` đã **thay đổi ngay trong phiên này** (lần đọc đầu còn `loadManagers()`, lần sau đã tách `loadConfigManagers()` / `loadRuntimeManagers()`). Mọi kết luận ở mục 1 là với bản **sau** thay đổi (guard nằm giữa hai nửa, DB chưa mở). Các file khác tôi đã đọc lại/grep lại để xác nhận anchor trước khi viết.

---

## 1. Tóm tắt

| Mức | Số lượng |
|---|---|
| CRITICAL | 0 |
| HIGH | 5 |
| MEDIUM | 8 |
| LOW | 5 |

Không chạy build/test/server/git (ràng buộc read-only), nên mọi kết luận là **đọc source + hợp đồng JVMS/Bukkit**, không phải đo đạc. Chỗ yếu nhất không nằm ở 7 thay đổi Java mà ở **hai cổng mới**: cổng quét `.class` dừng phân tích constant pool ở tag đầu tiên nó không biết (tag 15 — có mặt trong gần như mọi class của repo này), và cơ chế "khai báo trung thực" của nó không nhìn thấy `import com.itemguard.commands.*` nên cả package `commands/` (7 class, đầy literal tiếng Việt) chưa từng bị hỏi tới. Mục 1 (`general.enabled`) và mục 4 (`DoubleChest`) đứng vững trước mọi đòn tôi thử.

---

## 2. Phát hiện

### HIGH

#### H-1. `class_strings` không xử lý `CONSTANT_MethodHandle` (tag 15) → cổng jar chỉ đọc phần **đầu** constant pool của mỗi class, và im lặng

`scripts/check_no_hardcoded_vietnamese.py:160-167`
```python
TAG_UTF8 = 1; TAG_INTEGER = 3; TAG_FLOAT = 4; TAG_LONG = 5; TAG_DOUBLE = 6
TWO_BYTE  = {7, 8, 16, 19, 20}
FOUR_BYTE = {9, 10, 11, 12, 17, 18}
```
`scripts/check_no_hardcoded_vietnamese.py:202-203`
```python
else:
    return strings  # unknown tag: stop rather than decode garbage as strings
```
Tập tag hợp lệ của JVMS là {1,3,4,5,6,7,8,9,10,11,12,**15**,16,17,18,19,20}. **15 (`CONSTANT_MethodHandle`, 3 byte) là tag hợp lệ duy nhất bị thiếu.** Tag 15 được javac phát cho mọi entry `BootstrapMethods`, tức mọi lambda, mọi method reference, và (Java 9+) **mọi phép nối chuỗi không phải hằng số**. `ItemGuard.java` có cả ba: lambda ở `:147-149`, `:173`, method ref `System::currentTimeMillis` ở `:174`, nối chuỗi runtime ở `:96` và `:101`.

**Hậu quả:** với mọi class như vậy, `class_strings` trả về **chỉ các Utf8 nằm trước entry tag-15 đầu tiên** rồi dừng — không exception, không cảnh báo. `scan_jar` vẫn `checked += 1` cho class đó, nên báo cáo "0 findings over 361 class entries" (`docs/release/LITE_RELEASE_GATES.md:33`) không phân biệt được với "parser bỏ chạy ở mọi class". Đây đúng là lỗ hổng mà cổng được viết ra để bịt (C-2): nếu `"ItemGuard da tat!"` ở `ItemGuard.java:135` quay lại, nó nằm **sau** phép nối chuỗi ở `:96` trong thứ tự pool và cổng sẽ không thấy.

**Tái hiện:** không tái hiện được ở đây (cần chạy python / mở jar). Đòn quyết định: in `len(class_strings(archive.read("com/itemguard/ItemGuard.class")))` và so với số Utf8 thật.
**Mức kiểm chứng:** việc **tag 15 không được xử lý và hàm trả về sớm** — verified từ source. Việc nó nằm giữa pool của class cụ thể — suy luận từ JVMS + hành vi javac, chưa đo.
**Sửa tối thiểu:** thêm `elif tag == 15: offset += 3` và đổi `else: return strings` thành một tín hiệu lỗi (raise, hoặc trả thêm cờ `truncated`) để `scan_jar` đếm được "class không đọc trọn".

---

#### H-2. Cờ ngôn ngữ miễn trừ **cả file** / **cả class**, nên LITE-facing surface lớn nhất không bị quét gì cả

`scripts/check_no_hardcoded_vietnamese.py:66`
```python
LANGUAGE_FLAG = ("isVietnamese()", "vietnamese ?", "vietnamese)")
```
`scripts/check_no_hardcoded_vietnamese.py:152-155`
```python
flagged = scan_file(path)
if not flagged: continue
if any(flag in text for flag in LANGUAGE_FLAG):
    continue      # <- bỏ TOÀN BỘ file, không phải từng literal
```
`scripts/check_no_hardcoded_vietnamese.py:223-224` (jar mode) `if name in BILINGUAL_CLASSES: continue` — bỏ nguyên class.

**Hậu quả:** `src/main/java/com/itemguard/lite/LiteCommand.java:35` có `isVietnamese()`, nên **mọi** literal trong file đó được miễn trừ ở source mode, và `com/itemguard/lite/LiteCommand.class` được miễn trừ ở jar mode. `LiteCommand` là *bề mặt lệnh duy nhất của LITE* (`ItemGuardLite.java:12-18`). Một `sender.sendMessage("Khong tim thay")` cứng thêm vào đó đi qua cả hai cổng. Điều tương tự với 4 class còn lại trong `BILINGUAL_CLASSES` (`:73-79`).
Ngoài ra `"vietnamese)"` khớp cả `foo(vietnamese)` lẫn một comment `(vietnamese)`, nên cờ có thể được "cấp" bằng một dòng không phải là cơ chế song ngữ.

**Mức kiểm chứng:** verified.
**Sửa tối thiểu:** áp cờ theo **dòng/biểu thức** thay vì theo file — chỉ bỏ literal nằm trong cùng statement với `isVietnamese()` / tham số `vietnamese`; với jar mode, không có cách nào làm điều đó, nên `BILINGUAL_CLASSES` phải đi kèm một assert ở source mode rằng file đó **không có** literal tiếng Việt ngoài các cặp `text(english, vietnamese)`.

---

#### H-3. `referenced_from_outside` bỏ sót wildcard import và tên trần → cả package `commands/` chưa từng bị luật khai báo hỏi tới

`scripts/check_no_hardcoded_vietnamese.py:128-129`
```python
references |= set(re.findall(r"com\.itemguard\.[\w.$]+", text))
references |= set(re.findall(r"^import\s+(?:static\s+)?([\w.]+);", text, re.M))
```
`src/main/java/com/itemguard/ItemGuard.java:6` `import com.itemguard.commands.*;` — `[\w.]+` **không khớp `*`**, nên regex import trượt; regex còn lại chỉ thu được chuỗi `"com.itemguard.commands."`, không khớp bất kỳ nhánh nào ở `:134-135`. Và `ItemGuard.registerCommands()` (`:219-238`) dùng **tên trần** (`new MainCommand(this)`, `new CheckCommand(this)`, …) — script không thu tên trần.

**Hậu quả:** `MainCommand`, `CheckCommand`, `HistoryCommand`, `SearchCommand`, `StatsCommand`, `FindItemCommand`, `MatDoCommand`, `FindItemCommandTask` đều nằm trong thư mục exempt, đều được tham chiếu từ ngoài, và **không có mục nào trong `SCANNED_INSIDE_EXEMPT` lẫn `SKIPPED_INSIDE_EXEMPT`**. Luật "an undeclared reference is a finding" (`:39-45`) — thứ mà docstring gọi là "what makes the exemption honest instead of a directory-sized hole" — chưa từng kích hoạt cho thư mục lớn nhất. Các file đó có tiếng Việt cứng ngay hôm nay: `commands/CheckCommand.java:58,66,92,96,98`, `commands/MatDoCommand.java:101,147,148,152,154,201,229`, `commands/FindItemCommandTask.java:71,73,81`, `commands/FindItemCommand.java:63`.

Hôm nay LITE **không** chạm tới chúng (`ItemGuardLite.registerCommands()` override `ItemGuard.registerCommands()`) — nhưng đó là điều cổng lẽ ra phải **chứng minh**, và nó chưa hề hỏi. `registerCommands()` là `protected`.
`scripts/test_check_no_hardcoded_vietnamese.py:177-183` (`test_every_referenced_file_in_the_real_tree_is_declared`) xanh vì cùng một lỗ hổng.

**Mức kiểm chứng:** verified (grep toàn `src/main/java` cho `com.itemguard.commands.` chỉ ra đúng hai hit: wildcard ở `ItemGuard.java:6` và `com.itemguard.commands.ItemCodeInput` ở `LiteCommand.java:4`).
**Sửa tối thiểu:** trong `referenced_from_outside`, thêm `re.findall(r"^import\s+([\w.]+)\.\*;", text, re.M)` → coi mọi file trong package đó là "referenced", và thu thêm tên trần bằng `\b(ClassName)\b` cho từng file inside.

---

#### H-4. `/ig check`: `last_action = 'ADOPTED'` bị ghi đè ngay lần tương tác đầu, nên mốc "Created" sai quay lại

`src/main/java/com/itemguard/commands/CheckCommand.java:81-82`
```java
boolean adopted = "ADOPTED".equalsIgnoreCase(data.getLastAction());
String timeStr = adopted ? "unknown (adopted)" : formatTime(data.getCreatedAt());
```
`src/main/java/com/itemguard/persistence/ItemSqliteRepository.java:163-168`
```sql
UPDATE tracked_items
SET last_seen_at = ?, last_action = ?, last_location = ?, owner_name = ?, owner_uuid = ?
WHERE code = ?
```
`last_action` là **trạng thái hiện tại**, không phải nguồn gốc của hàng. Mọi `PICKUP` / `DROP` / `USE` / `INVENTORY_MOVE` / `CONTAINER_TAKE` đi qua `DatabaseManager.updateItemLastAction` (`data/DatabaseManager.java:163-178`) và ghi đè `'ADOPTED'`.

**Hậu quả:** đúng kịch bản M-4 mô tả — khôi phục DB từ backup, item hai năm tuổi được adopt, `created_at = updatedAt` (`ItemSqliteRepository.java:1544-1553`). Nếu staff tra **ngay**, họ thấy "unknown (adopted)" (đúng). Nếu người chơi nhặt/thả/dùng nó **một lần** trước khi staff tra — tình huống thường gặp hơn — `last_action` thành `PICKUP`, `adopted` thành `false`, và `/ig check` in lại **đúng con số sai** mà fix này sinh ra để loại bỏ. Đây là fix chỉ đúng trong một cửa sổ ngắn.
Test đúng đã tồn tại sẵn: `ItemSqliteRepository.java:1557` ghi một hàng `item_history` `'ADOPTED'` bất biến.

**Tái hiện:** adopt một identity, gọi `/ig check` → "unknown (adopted)"; nhặt item lên; gọi lại → mốc thời gian.
**Mức kiểm chứng:** verified (SQL + đường gọi).
**Sửa tối thiểu:** đổi điều kiện sang "tồn tại hàng `item_history` với `action='ADOPTED'` cho code này" thay vì đọc `tracked_items.last_action`.

---

#### H-5. Sàn "hai chu kỳ" giả định các lần audit cách nhau đúng một chu kỳ; sweep tràn chu kỳ làm giả định đó sai và cooldown trở lại vô tác dụng

`src/main/java/com/itemguard/config/AntiDupeSettings.java:48-57`
```java
long intervalTicks = Math.max(1L, config.getLong("performance.inventory-scan-interval", 600L));
long cycleMillis = intervalTicks > Long.MAX_VALUE / 100L ? Long.MAX_VALUE : intervalTicks * 50L;
long floor = cycleMillis > Long.MAX_VALUE / 2 ? Long.MAX_VALUE : cycleMillis * 2;
return Math.max(configured, floor);
```
Phần số học **đúng** (xem mục 3). Cái sai là tiền đề ở comment `:39-43` — "two audits are always at least one cycle apart (never less)". Đúng là "không ít hơn", nhưng sàn chỉ có tác dụng khi khoảng cách **nhỏ hơn** 2 chu kỳ, và hai điều kiện phá vỡ nó:

1. **Sweep tràn chu kỳ.** `src/main/java/com/itemguard/tasks/InventoryScanTask.java:76-82` bỏ hẳn tick khi `sweepCursor.isPassInFlight()`, và audit chỉ xảy ra khi pass kết thúc (`:123-125` → `finalizeEpoch`). Nếu sweep kéo dài 3 chu kỳ, hai audit liên tiếp cách nhau ~3–4 chu kỳ ⇒ `delta ≥ floor` ⇒ phép so `previous.created_at > createdAt - cooldown` (`ItemSqliteRepository.java:1191-1193`) lại thành sai ⇒ **không chặn được gì**, đúng nguyên trạng lỗi M-1. Với mặc định `anti-dupe.sweep.chunks-per-tick: 8` và `inventory-scan-interval: 600`, một pass phủ ~4 800 chunk/chu kỳ; server nhiều người/nhiều world vượt con số đó dễ dàng — và đó chính là loại server có dupe.
2. **TPS < 20.** `cycleMillis` quy đổi tick → ms bằng hằng số 50, nhưng `created_at` là `System.currentTimeMillis()`. Ở 10 TPS, một chu kỳ thực là 2× `cycleMillis` = đúng bằng sàn ⇒ `delta ≥ cooldown` ⇒ hết tác dụng. Dưới 10 TPS thì tệ hơn.

**Hậu quả:** admin hạ `detection-cooldown-ms` xuống sàn, tin rằng đã có throttle, và vẫn bị spam `ITEMGUARD_DUPLICATE_CONFIRMED` + tin nhắn staff mỗi lần audit, kèm một hàng `duplicate_findings` mỗi lần. Mặc định 300 000 ms (10 chu kỳ) che được đa số ca.
**Mức kiểm chứng:** verified về đường mã (skip tick, finalize-on-pass-complete, phép so SQL); tần suất tràn sweep là suy luận.
**Sửa tối thiểu:** tính sàn từ **khoảng cách thực giữa hai lần `finalizeEpoch` gần nhất** (đã có `getMaximumPersistedObservationEpoch`), hoặc đơn giản là so `previous.created_at` với **epoch trước đó** thay vì với một cửa sổ wall-clock.

---

### MEDIUM

#### M-1. `craft-refused` nói sai sự thật ở hai nhánh tagged, và nói "yet" cho một từ chối vĩnh viễn

`src/main/java/com/itemguard/MessageManager.java:75`
```java
DEFAULT_MESSAGES.put("craft-refused", "&cCraft cancelled: ItemGuard cannot create a tracked identity for the crafted result yet.");
```
`src/main/java/com/itemguard/listeners/CraftListener.java:40-49` gửi **cùng một key** cho cả ba nhánh CANCEL, trong đó `CraftOutputPolicy.java:28-31` trả `CANCEL_TAGGED_READY_COPY` khi kết quả **đã có** identity.

Hai chỗ sai:
- Nhánh `CANCEL_TAGGED_READY_COPY`: lý do từ chối là "kết quả đã mang một identity, sao chép nó là dupe" — câu thông báo lại nói *không tạo được* identity, tức nói ngược.
- Nhánh `CANCEL_UNTAGGED_ELIGIBLE` (mặc định, `lite/config.yml:34` = `true`): từ chối này **vĩnh viễn** cho tới khi admin sửa config. Chữ "yet" bảo người chơi chờ. Họ sẽ craft lại, mãi mãi, và không có gì trong câu đó chỉ cho admin tới `tracking.cancel-untracked-craft-output`.

**Mức kiểm chứng:** verified.
**Sửa tối thiểu:** hai key: một cho nhánh untagged (nói rõ là do chính sách server, nêu tên khoá config), một cho nhánh tagged (nói rõ là chống sao chép ID). `CraftListener` chọn theo `action`.

---

#### M-2. `ItemListener.java:268-295` là code chết, và mất đường gắn tag cho item chưa tag click trong rương

`src/main/java/com/itemguard/listeners/ItemListener.java:231-251`
```java
if (!tracking.hasCodeOrUuid(item)) {
    ...
    return;                       // :249 — return vô điều kiện
}
if (tracking.hasCodeOrUuid(item)) {   // :251 — luôn true
```
Vì nhánh `!hasCodeOrUuid` kết thúc bằng `return`, điều kiện ở `:251` luôn đúng và toàn bộ `else if (tracking.shouldTrack(item))` ở `:268-295` **không bao giờ chạy**.

**Hậu quả:** nhánh chết đó là đường duy nhất trong class gọi `requestBlockContainerSlotTag` từ một cú click (`:289-293`). Đường thay thế ở `:240-241` dùng `clickedSlots.playerSlotOf(clicked == player.getInventory(), slot)`, trả `-1` khi click vào rương ⇒ một item chưa tag, đáng track, nằm trong rương, bị click: **không bị hủy, không được gắn tag**, phải chờ sweep. Đồng thời `event.setCancelled(true)` ở `:269` là một refusal không báo gì cho người chơi — vô hại vì chết, nhưng người đọc file sẽ tin nó chạy.
**Mức kiểm chứng:** verified về tính không thể tới (không có mutation nào giữa `:231` và `:251`). Việc đây có phải thay đổi hôm nay hay không thì **chưa kiểm được** (không được chạy git).
**Sửa tối thiểu:** xóa `:268-295`, hoặc gộp nó vào trong khối `:231-249` trước `return`.

---

#### M-3. Test "cancellation is never silent" không kiểm điều nó tuyên bố; một refusal im lặng vẫn qua cổng

`src/test/java/com/itemguard/listeners/ItemListenerNoticeContractTest.java:38-44`
```java
int refusals = count(source, Pattern.compile("event\\.setCancelled\\(true\\);"));
...
assertEquals(guards + corruptRefusals, notices, "every refusal must be announced: ...");
```
`refusals` chỉ xuất hiện trong **chuỗi thông báo lỗi**, không có assert nào dùng nó. Bài test chỉ kiểm một đẳng thức đếm giữa hai pattern khác.

**Hậu quả:** `ItemListener.java:200-202`
```java
} else if (action == PickupIdentityAction.TAG_SOURCE && tracking.shouldTrack(item)) {
    event.setCancelled(true);
    tracking.requestEntityTag(event.getItem(), player);
}
```
hủy nhặt và **không nói gì**. Với người chơi thì trải nghiệm y hệt H-3 gốc: cúi xuống nhặt kiếm, không có gì xảy ra. Nếu `requestEntityTag` không thành công (item không đủ điều kiện, publication hỏng), vòng này lặp mỗi lần thử nhặt. Hàng `Cancellation is never silent` ở `docs/release/LITE_RELEASE_GATES.md:38` được chống đỡ bởi bài test này.
**Mức kiểm chứng:** verified về test và về nhánh im lặng; "lặp mãi" là suy luận (chưa đọc hết `requestEntityTag`).
**Sửa tối thiểu:** `assertEquals(refusals, notices + <số nhánh không có người chơi>)`, hoặc liệt kê rõ các `setCancelled` được phép im lặng bằng tên nhánh.

---

#### M-4. `check_listing_copies.py` không thấy hash rút gọn — dạng hash mà chính các doc trong phạm vi quét đang dùng

`scripts/check_listing_copies.py:47` `SHA = re.compile(r"\b[0-9a-f]{64}\b")`
`docs/release/LITE_RELEASE_GATES.md:57` — `sha256 `28aed104…`` (8 ký tự + ellipsis)

Cổng chỉ so 64 ký tự hex thường. Mọi trích dẫn rút gọn (`28aed104…`, `a952d161…`) — đúng thói quen viết của chính cây doc này, và chính là dạng mà `:20-23` kể là đã lừa được phiên bản trước (`"Current candidate ...: a952d161…"`) — nằm ngoài tầm nhìn. Sau một lần rebuild, `LITE_RELEASE_GATES.md` vẫn nói `28aed104…` và cổng vẫn báo **0 findings**. Hex chữ hoa cũng không khớp.

**Mức kiểm chứng:** verified (regex + dòng doc thật trong phạm vi `SCAN`).
**Sửa tối thiểu:** thêm một pattern thứ hai `\b[0-9a-fA-F]{8,63}\b` và coi là finding khi nó **là prefix của một hash cũ đã biết** hoặc **không phải prefix của `digest`**.

---

#### M-5. Bản gửi thật — `release/ItemGuard-LITE-1.0.0-Spigot.zip` — nằm ngoài cổng listing

`scripts/check_listing_copies.py:39-45`
```python
SCAN = [REPO/"release"/"spigot-upload", REPO/"release"/"upload",
        REPO/"release"/"SHA256SUMS.txt", REPO/"docs"/"release"]
SUFFIXES = {".txt", ".md", ".html", ".bbcode"}
```
Thư mục `release/` gốc không nằm trong `SCAN`, và `.zip` không nằm trong `SUFFIXES`; script không mở archive. Nhưng `release/ItemGuard-LITE-1.0.0-Spigot.zip` tồn tại và, theo tên, là gói bàn giao. Docstring của chính file này (`:3-7`) nói vấn đề là "seven copies of this listing lived in the tree and every one of them drifted" — cái zip là bản sao thứ tám, chưa từng được mở ra để đối chiếu.
**Mức kiểm chứng:** verified về phạm vi cổng và về sự tồn tại của file; **chưa kiểm được** bên trong zip (binary, không giải nén).
**Sửa tối thiểu:** thêm `release` vào `SCAN` với một nhánh `zipfile` đọc các entry có suffix trong `SUFFIXES`.

---

#### M-6. Khi `general.enabled: false`, Bukkit vẫn báo plugin **enabled** trong khi `getApi()` / `getDB()` / `getTrackingService()` là `null`

`src/main/java/com/itemguard/ItemGuard.java:78-84`
```java
if (!configManager.isEnabled()) {
    getLogger().info("general.enabled is false in config.yml: ItemGuard is disabled. ...");
    return;
}
```
`return` khỏi `onEnable()` **không** làm Bukkit disable plugin: `Bukkit.getPluginManager().isPluginEnabled("ItemGuard")` vẫn `true`, `ItemGuard.getInstance()` vẫn non-null (`:62`), nhưng `loadRuntimeManagers()` (`:168-177`) chưa chạy nên `api`, `databaseManager`, `itemTrackingService`, `findItemService` đều `null`.

**Hậu quả:** một plugin khác làm đúng bài — kiểm `isPluginEnabled` rồi gọi `ItemGuard.getInstance().getApi()` — nhận `NullPointerException`, và stack trace chỉ vào plugin của họ chứ không vào ItemGuard. `ItemGuardAPI` là bề mặt công khai đã công bố.
**Tái hiện:** đặt `enabled: false`, cài bất kỳ plugin nào gọi `getApi()` khi enable.
**Mức kiểm chứng:** verified về source; hành vi `isPluginEnabled` là hợp đồng Bukkit (`JavaPluginLoader` đặt enabled = true trước khi gọi `onEnable`, và chỉ `disablePlugin` khi `onEnable` **ném**).
**Sửa tối thiểu:** `getServer().getPluginManager().disablePlugin(this); return;` — Bukkit sẽ gọi `onDisable()` (an toàn: mọi field đều null-checked ở `:106-135`) và các plugin khác thấy đúng trạng thái.

---

#### M-7. Dòng disable mới trùng chữ với dòng shutdown, nên `reload.py` chờ một tín hiệu yếu hơn trước

`src/main/java/com/itemguard/ItemGuard.java:80` — `"general.enabled is false in config.yml: ItemGuard is disabled. No listener, ..."`
`src/main/java/com/itemguard/ItemGuard.java:135` — `getLogger().info("ItemGuard is disabled.");`
`tools/lite-runtime/reload.py:82,88`
```python
disables = server.count('ItemGuard is disabled.')
server.send('bukkit:reload confirm')
server.has_more('ItemGuard is disabled.', disables, 90)
```
Chuỗi cũ mà harness chờ (`"ItemGuard da tat!"`) chỉ do `onDisable` phát. Chuỗi mới là **substring của cả hai** dòng, phát ra bởi hai sự kiện ngược nhau: "đã tắt xong" và "từ chối bật". `has_more` chỉ đòi một lần xuất hiện **mới**, nên một fixture vô tình có `enabled: false` sẽ thỏa assertion này bằng dòng từ chối bật — trong khi không listener/command nào được đăng ký.

Điều assertion vẫn còn chứng minh: nếu message biến mất hẳn thì `has_more` timeout và **fail** — điểm này không yếu đi. Cái yếu đi là *tính đơn nghĩa*. Các assertion về sau (`identity-readback` ở `:102-108`) sẽ bắt được ca đó một cách gián tiếp, nhưng với thông báo lỗi sai chỗ.
**Mức kiểm chứng:** verified.
**Sửa tối thiểu:** đổi dòng guard thành một câu không chứa "ItemGuard is disabled." (ví dụ `"general.enabled is false in config.yml: ItemGuard will not start."`), hoặc cho `reload.py` chờ một token riêng của `onDisable`.

---

#### M-8. `identity-not-ready` bảo "thử lại sau một lát" cho một từ chối vĩnh viễn

`src/main/java/com/itemguard/MessageManager.java:74` — `"...Try again in a moment; if it keeps happening, ask staff."`
`src/main/java/com/itemguard/listeners/ItemListener.java:203-206`
```java
} else if (action == PickupIdentityAction.IGNORE_CORRUPT) {
    event.setCancelled(true);
    tellIdentityNotReady(player);
}
```
Tag `CORRUPT` là trạng thái **cố định của item** (`PickupIdentityPolicy.java:9`); không lần thử nào sau đó đổi kết quả. Cùng một câu cũng được dùng cho cửa sổ C-3 (item bị "gạch" vĩnh viễn cho tới khi staff can thiệp). Mệnh đề "if it keeps happening, ask staff" cứu vãn được phần nào, nhưng người chơi vẫn được chỉ dẫn lặp lại một hành động không bao giờ thành công, và staff không có từ khóa nào để tra.
**Mức kiểm chứng:** verified.
**Sửa tối thiểu:** key thứ hai `identity-corrupt` cho nhánh `IGNORE_CORRUPT`, nói thẳng là tag hỏng và cần staff.

---

### LOW

#### L-1. Lý do SKIPPED của `catalog/CatalogText.java` là một khẳng định chưa được kiểm, không phải chứng minh không-với-tới

`scripts/check_no_hardcoded_vietnamese.py:55-56`
```python
"com/itemguard/catalog/CatalogText.java":
    "registers a SQL fold function; contains no player text",
```
Bốn mục SKIPPED còn lại đều là lý do **cấu trúc** ("registered only when !isLiteEdition") và tôi xác nhận chúng đúng: `ItemGuard.java:186-194` chỉ dựng `CatalogUi`/`GUIListener`/`FilterChatListener` khi `!isLiteEdition()`, và `getCatalog()` có đúng một caller là `catalog/CatalogUi.java:42`. Riêng `CatalogText` thì **chạy trên LITE**: `persistence/SqliteConnectionOwner.java:259` gọi `com.itemguard.catalog.CatalogText.registerIndexFunction(connection)` không phân biệt edition. Lý do được ghi là "không chứa player text" — đúng hôm nay, nhưng vì file bị skip nên **không có gì giữ cho nó đúng ngày mai**. Đây phải là `SCANNED_INSIDE_EXEMPT`.
**Sửa tối thiểu:** chuyển `CatalogText.java` sang `SCANNED_INSIDE_EXEMPT`.

#### L-2. `identityNoticeAt` không được dọn khi người chơi thoát, và lần prune duy nhất có thể không dọn gì

`src/main/java/com/itemguard/listeners/ItemListener.java:74-77`
```java
if (identityNoticeAt.size() >= NOTICE_MAX_PLAYERS) {
    identityNoticeAt.values().removeIf(at -> now - at >= NOTICE_WINDOW_MS);
}
identityNoticeAt.put(player.getUniqueId(), now);
```
`values().removeIf(...)` trên `ConcurrentHashMap` **hoạt động đúng** như code giả định (view ghi ngược về map) — đòn đó không phá được. Cái thủng là: prune chỉ chạy khi đã chạm trần, và chỉ xóa entry **đã hết hạn**; nếu 4 096 entry đều dưới 5 s thì `removeIf` xóa 0 và `put` vẫn thêm entry thứ 4 097 — trần là mềm, không phải cứng. Không có handler `PlayerQuitEvent` nào xóa entry, nên entry của người đã thoát nằm lại tới lần chạm trần kế tiếp. Với 4 096 UUID+Long thì đây là vấn đề sạch sẽ, không phải vấn đề bộ nhớ.
**Sửa tối thiểu:** nếu sau `removeIf` mà `size() >= NOTICE_MAX_PLAYERS` thì bỏ nửa cũ nhất, đúng chiến lược `ContainerListener.pruneExpiredCooldowns` (`:159-172`) mà comment ở `:62` viện dẫn.

#### L-3. Chỉ `messages_en.yml` được ghim; `messages.yml` và `messages_zh.yml` không có test nào

`src/test/java/com/itemguard/MessageManagerFallbackTest.java:34-46` so `DEFAULT_MESSAGES` ↔ `messages_en.yml` hai chiều. Hôm nay ba file đều đủ 45 key giống nhau (tôi đã đối chiếu `messages_en.yml`, `messages.yml`, `messages_zh.yml` dòng 1-45) — **không có mismatch**. Nhưng không có gì giữ điều đó: xóa một key khỏi `messages.yml` thì server FULL `language: vi` im lặng rơi về tiếng Anh cho key đó. Ngoài ra `messages_zh.yml` **không thể chọn được**: `config/MessageLanguagePolicy.java:29-32` map mọi giá trị không phải `en` thành `vi` (M-3, đã ghi nhận là ngoài phạm vi), nên việc mirror hai key mới sang `zh` là công không dùng tới.
**Sửa tối thiểu:** thêm một assert "ba file có cùng tập key" vào chính test đó.

#### L-4. `record_marker` miễn trừ một file sống nếu 20 dòng đầu có dòng bắt đầu bằng "Record"

`scripts/check_listing_copies.py:49` `MARKER = re.compile(r"^(?:SUPERSEDED|HISTORICAL RECORD|RECORD)\b", re.IGNORECASE)`
`re.IGNORECASE` + `\b` nghĩa là một tiêu đề tự nhiên như `## Record of handovers` hay `Record: see below` ở đầu file sẽ miễn trừ **toàn bộ** file khỏi kiểm hash. `RECORDS`/`RECORDED` thì không khớp (`\b` chặn đúng) — phần đó ổn.
**Sửa tối thiểu:** đòi `RECORD:` có dấu hai chấm, như chính docstring viết ở `:16`.

#### L-5. Ở đúng mức sàn, cooldown không "suppress a repeat" mà chỉ giảm nhịp còn một nửa

`src/main/java/com/itemguard/config/AntiDupeSettings.java:55-57` + `src/main/java/com/itemguard/persistence/ItemSqliteRepository.java:1191-1193`. Với `cooldown = 2·cycle` và audit đều đặn mỗi `cycle`: lần lặp đầu bị chặn (`delta = cycle < 2·cycle`), nhưng lần sau `delta = 2·cycle` ⇒ `previous.created_at > createdAt - cooldown` sai ⇒ cảnh báo lại. Kết quả là **một cảnh báo mỗi hai chu kỳ**, không phải im lặng. Đây là cải thiện thật so với "mỗi chu kỳ", nhưng câu trong comment `:43` ("One cycle of headroom is what makes the floor mean anything") dễ đọc thành "đã chặn được".
**Sửa tối thiểu:** sửa comment, hoặc để sàn là `cycleMillis * 3`.

---

## 3. Đã tấn công mà không phá được

**1. `general.enabled` guard.** Đòn mạnh nhất tôi có — "cái gì vẫn chạy sau guard?" — bị chặn bởi chính thay đổi giữa phiên: `loadConfigManagers()` (`ItemGuard.java:162-165`) chỉ dựng `ConfigManager` + `MessageManager`; `DatabaseManager` sang `loadRuntimeManagers()` (`:168-177`), phía sau guard, nên file SQLite, sidecar lock và `recoverPendingReclaimClaims` (`data/DatabaseManager.java:70-73`, một phép **ghi**) đều không chạy — và câu log ở `:80-82` nói đúng từng mệnh đề nó liệt kê. `onDisable()` (`:105-135`) null-check từng field nên đường disable-chưa-từng-enable sạch (`ItemGuardLite.onDisable` cũng vậy, `:20-23`). `/reload` server sau một lần khởi động bị tắt: không scheduler task, không listener, không DB lock nào sót ⇒ **không tệ hơn restart**. Khôi phục thì có: `/itemguard reload` **không** dùng được (không có executor ⇒ Bukkit in `usage` từ `plugin.yml`, và LITE thì `LiteCommand.java:49` còn không có subcommand `reload`), nhưng chính dòng log đã bảo "set it to true and restart", nên hướng dẫn khớp với thực tế. Chỉ còn M-6 đứng lại.

**2. Throttle của `tellIdentityNotReady`.** Thử "khóa theo Player object khác nhau" — không: khóa là `player.getUniqueId()` (`ItemListener.java:70,77`), ổn định qua mọi instance `Player` và qua relog. Thử "`values().removeIf` trên `ConcurrentHashMap` có ghi ngược không" — có, view của CHM hỗ trợ remove và `Collection.removeIf` đi qua iterator của view; giả định của code đúng. Thử "throttle nuốt mất thông báo người chơi cần" — trong 5 s thì có, nhưng mọi refusal trong cửa sổ đó là **cùng một nguyên nhân** cho cùng một người, nên bỏ 19/20 là đúng ý đồ; chỉ khi hai nguyên nhân khác nhau (CORRUPT rồi not-ready) trùng cửa sổ thì mới mất thông tin, và cả hai hiện dùng chung một câu (M-8) nên không mất gì thêm. `ItemListenerNoticeContractTest` khớp đếm 5+1=6 với source hiện tại.

**3. `CraftOutputPolicy` — "chỉ nhánh untagged-eligible dịch chuyển".** Đọc `:28-38`: `hasIdentity` được xét **trước** và trả `CANCEL_TAGGED_*` không phụ thuộc cờ; `!eligible` trả `ALLOW_UNTRACKED` cũng không phụ thuộc cờ; cờ chỉ quyết nhánh thứ ba. `CraftListener.java:40-42` cho qua đúng hai giá trị ALLOW. Overload không tham số (`:10-12`) giữ `true`. Claim đúng. Tôi cũng thử tìm đường để `hasIdentity` thành `true` trên một craft output: trong `CraftItemEvent`, `getCurrentItem()` là ô kết quả do recipe sinh, người chơi không đặt item vào đó được; đường duy nhất là một recipe **sao chép component** (transmute nhuộm shulker, recipe của plugin khác). Tôi **không chứng minh được** đường đó từ repo này, nên không tính là phát hiện — nhưng nếu nó tồn tại thì M-1 là thứ người chơi đọc được. Tương tác với `TrackingWorthinessPolicy` cũng không mở thêm gì: `ItemTrackingService.shouldTrack` (`:138-162`) đòi `isWorthTracking` **và** `maxStackSize==1 && amount==1`, nên tập bị chặn đúng là gear/valuables craft ra từng cái một.

**4. Nhánh `DoubleChest`.** Đòn 1, "`getLocation()` có ổn định giữa các lần mở không": `DoubleChest.getLocation()` đi qua `CraftInventoryDoubleChest.getLocation()`, là **trung điểm** của hai nửa, tính từ toạ độ block chứ không từ instance ⇒ cùng giá trị mỗi lần và **giống nhau cho cả hai nửa** (không có "nửa trái/nửa phải" riêng). Đòn 2, "trung điểm có phân số .5 thì `getBlockX()` floor có va chạm không": hai double chest khác nhau không thể cho cùng bộ ba block sau khi floor, vì mọi cặp cho ra cùng trung điểm đều phải dùng chung một rương. Đòn 3, "thứ tự nhánh": `DoubleChest` không phải `BlockState` cũng không phải `Entity`, nên đặt nó giữa hai nhánh kia (`ContainerListener.java:133-148`) an toàn. Đòn 4, "cooldown có thực sự được đặt cho rương đôi không, hay `requested` luôn = 0": `BlockContainerPhysicalSlotResolver` có nhánh `DoubleChestInventory` riêng (`:18-21`, `:29-37`) phân giải từng nửa qua holder `Container`, nên `scanContainerInventory` (`ItemTrackingService.java:958-985`) trả `> 0` và `ContainerListener.java:99-101` đặt cooldown. Fix có tác dụng thật. Đòn 5, "còn gì rơi vào nhánh identity-hash": ender chest và túi người chơi đều cho holder là `HumanEntity` ⇒ cùng key `ENTITY:<uuid>`, nhưng không đường nào đặt cooldown cho túi người chơi nên va chạm không hiện thực hoá; inventory ảo của plugin khác thường có holder `null` ⇒ `isCooldownActive`/`setCooldown` return sớm (`:104-115`); GUI của chính ItemGuard vào được `onInventoryOpen` nhưng `scanContainerInventory` bỏ qua mọi slot không phân giải được nên `requested == 0` và không có entry nào được ghi.

**5. Số học của `detectionCooldown`.** `intervalTicks > Long.MAX_VALUE/100` ⇒ `cycleMillis = MAX`; ngược lại `intervalTicks*50 ≤ MAX/2` nên `*2` không tràn. Cả hai nhánh không sinh số âm, `Math.max(1L, ...)` chặn 0/âm, `Math.max(configured, floor)` chặn cooldown âm. Không có đường nào cho ra 0 nữa. (Tiền đề thì thủng — H-5.)

**6. Ba file messages.** Tập key của `messages_en.yml`, `messages.yml`, `messages_zh.yml` **trùng khớp hoàn toàn** (45 key, dòng 1-45 của mỗi file), hai key mới có mặt ở cả ba với bản dịch thật chứ không phải chép tiếng Anh. `MessageManager.getRaw` (`:138-141`) truyền default tường minh nên `MemorySection.get(path, def)` **không** tra `setDefaults`, tức một `messages_en.yml` cũ trên đĩa thiếu key mới vẫn rơi về `DEFAULT_MESSAGES` tiếng Anh chứ không lộ key thô. `MessageManagerFallbackTest` ghim hai chiều đúng như tuyên bố.

**7. Nhánh `--jar` bỏ qua inner class.** `BILINGUAL_CLASSES` so tên **chính xác** (`:223`), nên `LiteCommand$1.class`, `LiteHistoryView$Entry.class` … vẫn bị quét. Đây là chặt hơn công bố, không phải lỏng hơn.

**8. `verify.py` sau khi đổi sang chuỗi tiếng Anh.** `tools/lite-runtime/verify.py:85-87` và `:421-423` đòi **đúng 2** lần nhận `'ItemGuard cannot create a tracked identity for the crafted result yet.'`; nếu message biến mất, `sum(...) == 2` thành `0` và assertion **fail** — không pass trên tín hiệu rỗng. Chuỗi được so là substring sau khi `MessageManager.get` đã ghép prefix và `colorize` đổi `&c`→`§c`, nên vẫn khớp. Chuỗi cũ (literal tiếng Việt trong `CraftListener`) cũng phủ cả ba nhánh CANCEL, nên việc gộp message không làm assertion mất khả năng phân biệt so với trước. Tín hiệu vẫn tương đương; chỉ `reload.py` là yếu đi (M-7).

---

## 4. Những gì tôi KHÔNG kiểm được trong phiên này

1. **Mọi hành vi runtime.** Không build/test/server. Cụ thể: constant pool thật của `ItemGuard.class` và vị trí tag 15 trong đó (H-1); `DoubleChest.getLocation()` trên Paper 1.21.11 thật; craft output có thể mang PDC qua recipe transmute hay không (M-1); độ dài thực của một sweep pass (H-5).
2. **Git.** Không được chạy `git diff`, nên tôi **không phân định được** cái gì thuộc diff hôm nay và cái gì có sẵn. Cụ thể M-2 (nhánh chết `ItemListener.java:268-295`) tôi báo cáo như một khuyết tật đang tồn tại, không như một hồi quy của hôm nay.
3. **Cây làm việc đang bị sửa song song.** `ItemGuard.java` đổi giữa hai lần đọc của tôi. Tôi đã grep lại anchor cho `ItemListener`, `ContainerListener`, `CraftListener`, `CraftOutputPolicy`, `AntiDupeSettings`, `CheckCommand`, `MessageManager`, ba file `messages*.yml` và hai script, nhưng không thể bảo đảm trạng thái kể từ lúc đó.
4. **Jar đã đóng gói.** `release/spigot-upload/ItemGuard-LITE-1.0.0.jar` và `release/ItemGuard-LITE-1.0.0-Spigot.zip` là binary; tôi không giải nén, không đối chiếu SHA-256, không chạy `scan_jar` thật. Nên "cổng jar báo 0 findings" tôi chỉ phân tích được từ code của cổng, không từ kết quả.
5. **`scripts/package_lite.py`, `verify_lite_artifact.py`, `test_check_listing_copies.py`** — chưa đọc; các kết luận về cổng listing chỉ dựa trên `check_listing_copies.py` và danh sách file thật trong `release/` + `docs/release/`.
6. **Năm "record" được miễn trừ** mà `check_listing_copies.py` báo cáo: tôi không mở từng file để xác minh chúng thực sự là bản lưu lịch sử chứ không phải file sống mang marker.
