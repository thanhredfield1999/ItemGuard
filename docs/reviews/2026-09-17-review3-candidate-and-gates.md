Đã đọc xong hai bản adjudication, năm cổng mới, các test đi kèm, và toàn bộ product diff được nêu. Báo cáo:

---

# Review độc lập #3 — ItemGuard (read-only, 2026-09-17)

## 1. Tóm tắt

**3 HIGH · 7 MEDIUM · 6 LOW · 0 CRITICAL.**

Không có mục nào gây mất item hay mất dữ liệu. Ba HIGH đều đúng lớp lỗi mà brief mô tả: hai cổng mới báo PASS vì một lý do không liên quan đến điều chúng tuyên bố, và một thông điệp sản phẩm chỉ cho admin một khóa config không thể thay đổi kết quả. Độ tin cậy: mọi phát hiện dưới đây đều đọc được từ source trong repo; ba chỗ tôi đánh dấu **suy luận** (thứ tự sự kiện của Bukkit, việc bots.cjs lọc mã màu, danh sách recipe vanilla sao chép component) vì không có nguồn trong cây này để quyết.

---

## 2. HIGH

### H-1 · Cổng artifact tiếng Việt bỏ qua đúng file mà cổng source khai là LITE với tới được

`scripts/check_no_hardcoded_vietnamese.py:337-339`

```python
for name in sorted(archive.namelist()):
    if not name.endswith(".class"): continue
    if any(name.startswith(exempt + "/") for exempt in EXEMPT_DIRS): continue
    if name in BILINGUAL_CLASSES: continue
```

`scan_jar` áp dụng `EXEMPT_DIRS` một cách tuyệt đối. Nó **không** có ngoại lệ cho `SCANNED_INSIDE_EXEMPT`, trong khi cả lý do tồn tại của dict đó (`:38-45`) lẫn comment trong `src/main/java/com/itemguard/commands/ItemCodeInput.java:17-21` đều nói ngược lại:

> `ItemCodeInput.java:20-21` — *"the Vietnamese gate now scans this file for exactly that reason: it sits in commands/ but LiteCommand imports it."*

Câu đó chỉ đúng với `scan()` (source). Trong jar, `com/itemguard/commands/ItemCodeInput.class` bị bỏ qua. Và jar **có** chứa nó: `scripts/package_lite.py:92` ghi `'Full internal classes remain packaged but commands are not registered.'` — package_lite chỉ drop `lite/`, `messages.yml`, `messages_zh.yml`, giữ nguyên mọi `.class`.

Cộng thêm: 5 `BILINGUAL_CLASSES` (`:112-118`), trong đó có `com/itemguard/lite/LiteCommand.class` — **toàn bộ bề mặt lệnh của LITE** — cũng bị bỏ qua hoàn toàn trong lần quét jar.

**Hậu quả cho người mua:** hàng gate `LITE_RELEASE_GATES.md:31` nói *"**Artifact:** `… --jar` → 0 over **361 class entries**"* và trình bày nó như nửa chứng cứ độc lập với source. Nó không bao phủ file mà nửa source vừa khai báo là LITE-reachable, cũng không bao phủ lớp lệnh LITE. Một literal tiếng Việt thêm vào `ItemCodeInput` hoặc `LiteCommand` có thể lọt qua **cả hai nửa** nếu nửa source bị thả (xem M-4 bên dưới) — đúng ca C2 mà cổng này được viết để chặn.

**Tái hiện:** thêm `throw new IllegalArgumentException("Can nhap ma vat pham");` vào `ItemCodeInput.normalize`, build, chạy `--jar`. Nửa source sẽ bắt (vì file được khai SCANNED); nửa jar báo 0. Nếu literal đó nằm trong một statement có sẵn một literal khác, cả hai nửa đều báo 0.

**Kiểm chứng:** từ source (đọc `scan_jar`, `EXEMPT_DIRS`, `package_lite.py:55-72`). Không chạy được cổng.

**Sửa tối thiểu:** trong `scan_jar`, đổi dòng `:338` thành bỏ qua exempt dir *trừ khi* tên class tương ứng với một khóa trong `SCANNED_INSIDE_EXEMPT` (so sánh `name[:-6] + ".java"`), và thêm một test dựng jar giả có `com/itemguard/commands/ItemCodeInput.class` mang literal tiếng Việt, đòi `scan_jar` báo nó.

---

### H-2 · Luật hash rút gọn chỉ hoạt động khi digest đầy đủ tình cờ còn nằm trong phạm vi quét

`scripts/check_listing_copies.py:170-175`

```python
for token in TRUNCATED.findall(line):
    short = token.lower()
    if len(short) == 64 or digest.startswith(short): continue
    if any(other != digest and other.startswith(short) for other in known):
        found.add(short)
```

`known` (`:156-158`) chỉ gồm digest hiện tại cộng các chuỗi **64 ký tự** tìm thấy trong `SCAN`. Một hash rút gọn chỉ bị báo khi bản đầy đủ của nó cũng có mặt ở đâu đó trong `SCAN`. Với cây hiện tại:

* `28aed104…` và `94eb0dad…` — hai trong bốn candidate mà chính `LITE_RELEASE_GATES.md:19-20` liệt kê — chỉ có dạng đầy đủ trong `CURRENT_STATE.md:199`, `.hermes/WORKING_STATE.md:7` và `run/*.json`. **Không file nào trong `SCAN`.**
* Do đó mọi trích dẫn rút gọn của hai candidate đó là vô hình.

Bằng chứng sống trong cây, không phải giả định — `docs/release/SCREENSHOT_STATUS.md` (không có marker record: dòng 11/13 bắt đầu bằng `|`, nên `MARKER.match` ở `:81` trượt):

```
11 | Superseded | `34d7fab2…9e0cd` | Stats line read ...
12 | **Captured on** | `c776a020…8fdf` | The build actually running when these frames were taken |
```

Dòng 12 **không** chứa từ nào của `HISTORIC` (`:62-63`), nên nó được quét; `c776a020` là 8 hex, không phải tiền tố của bất kỳ digest nào trong `known` ⇒ không bị báo. Một file listing sống đang gọi tên một build không phải build đang ship, và cổng in `0 findings`.

**Hậu quả:** `LITE_RELEASE_GATES.md:32` nói *"truncated hashes are read too"* và ghi **0** findings. Câu chuyện thất bại mà docstring của chính file mô tả (`:19-23`: `SCREENSHOT_STATUS.md` nói `Shipping: a952d161…`) tái diễn nguyên vẹn nếu hash rút gọn đó là `28aed104…` thay vì `a952d161…`. Với `a952d161…` cổng chỉ bắt được vì `SPIGOT_HANDOFF.md:104`, `SPIGOT_PAGE_FINAL.md:22`, `SPIGOT_SUBMISSION_FORM.md:227` còn giữ dạng 64 ký tự — dọn ba file đó (việc hoàn toàn hợp lý) làm luật đi tong.

Test của chính cổng thừa nhận sự phụ thuộc này nhưng không ai đọc nó như một giới hạn — `scripts/test_check_listing_copies.py:100-101`:
```python
# The full digest, in a dated record, is what makes the short quote recognisable as stale.
self.write("2026-09-01-handover.md", f"an older handover: {OLD}\n")
```

**Kiểm chứng:** từ source + grep toàn repo cho `28aed104|94eb0dad` (chỉ có ngoài `SCAN`) và đọc `SCREENSHOT_STATUS.md:9-14`.

**Sửa tối thiểu:** thêm một danh sách `RETIRED_PREFIXES` khai báo tường minh (`28aed104`, `94eb0dad`, `a952d161`, `e11ada6a`, `34d7fab2`, `c776a020`) và báo mọi token trùng tiền tố đó bất kể `known`; giữ nguyên luật `known` cho các trường hợp chưa khai. Một dòng khai báo cho mỗi candidate bị thay, đúng tinh thần `SKIPPED_INSIDE_EXEMPT`.

---

### H-3 · Từ chối craft trên item đã có identity chỉ dẫn admin đi sửa một khóa config không có tác dụng gì

`src/main/java/com/itemguard/listeners/CraftListener.java:54-57`

```java
plugin.getMessages().send(player,
    action == CraftOutputPolicy.Action.CANCEL_TAGGED_NOT_READY
        ? "craft-refused-tagged"
        : "craft-refused");
```

`CraftOutputPolicy` có **ba** action hủy (`tracking/CraftOutputPolicy.java:41-47`): `CANCEL_UNTAGGED_ELIGIBLE`, `CANCEL_TAGGED_NOT_READY`, `CANCEL_TAGGED_READY_COPY`. Ánh xạ trên chỉ tách một cái. `CANCEL_TAGGED_READY_COPY` — nhánh mà `CraftOutputPolicy.java:28-31` trả về khi output **đã mang identity và identity đã verify**, tức đúng ca "sao chép identity" — rơi vào `else` và nhận `craft-refused`:

`src/main/resources/messages_en.yml:44`
```
craft-refused: "&cCraft cancelled: this server's ItemGuard policy blocks crafting a result that would need a new tracked identity. Set tracking.cancel-untracked-craft-output: false in config.yml to allow these crafts."
```

Hai điều sai cùng lúc:
1. Câu nói kết quả *"would need a new tracked identity"* — sai, kết quả **đã có** identity.
2. Câu bảo đặt `tracking.cancel-untracked-craft-output: false` để cho phép. `CraftOutputPolicy.decide` (`:28-32`) `return` trước khi đọc cờ khi `hasIdentity` là true, nên đặt `false` **không** mở nhánh này. Admin sửa config, restart, craft vẫn bị chặn, và họ không có manh mối nào khác.

Chính comment ngay phía trên chỗ lỗi (`CraftListener.java:50-53`) mô tả hành vi đúng mà code không làm: *"an untagged result is blocked by a server policy an admin can change, while a tagged one would copy an identity and has nothing to do with the switch."*

**Reachability:** nhánh này cần một recipe sao chép component từ nguyên liệu sang kết quả (nhuộm shulker, nhân bản banner, clone map — PDC nằm trong `minecraft:custom_data`). Tôi xác minh được đường code, **không** xác minh được danh sách recipe vanilla 1.21.11 từ repo này — đó là phần **suy luận**. Nhưng repo tự khẳng định nhánh này có thật: nó có action riêng, có hai test policy, và `LITE_RELEASE_GATES.md:37` bán nó như một tính năng ("the switch cannot open the tagged branches").

**Không có chứng cứ runtime nào cho `craft-refused-tagged`:** `tools/lite-runtime/verify.py:85-87` và `:534-536` chỉ pin chuỗi của `craft-refused` (đếm đúng 2 lần). Nhánh tagged chưa từng chạy trong bất kỳ gate nào.

**Kiểm chứng:** từ source, đối chiếu ba action với hai key.

**Sửa tối thiểu:** đổi điều kiện thành
```java
action == CraftOutputPolicy.Action.CANCEL_UNTAGGED_ELIGIBLE ? "craft-refused" : "craft-refused-tagged"
```
(mặc định về phía tagged, để một action hủy mới thêm vào sau này không tự nhận câu có tên khóa config), và thêm một test ánh xạ cả ba action.

---

## 3. MEDIUM

### M-1 · Một lời từ chối vẫn có thể im lặng: throttle dùng chung cho cả ba loại thông báo

`src/main/java/com/itemguard/listeners/ItemListener.java:79-101`

```java
private void notifyOnce(Player player, String key) {
    long now = System.currentTimeMillis();
    Long previous = identityNoticeAt.get(player.getUniqueId());
    if (previous != null && now - previous < NOTICE_WINDOW_MS) { return; }
```

`identityNoticeAt` khóa **chỉ theo `UUID` người chơi**, không theo `key`. Ba lời từ chối có nghĩa hoàn toàn khác nhau đi chung một cửa sổ 5 giây:

| key | nghĩa |
|---|---|
| `identity-not-ready` | tạm thời — *"Try again in a moment"* |
| `identity-corrupt` | vĩnh viễn — *"retrying will not change that. Staff need to look at it."* |
| `pickup-tagging` | tạm thời — *"Pick it up again in a moment."* |

Kịch bản thường gặp nhất, đúng kịch bản mà H3 được viết cho: vừa restart, cache readiness rỗng. Người chơi click một item trong rương ⇒ nhận `identity-not-ready` tại t. Trong vòng 5 giây họ nhặt một item có tag hỏng ⇒ `tellCorruptTag` → `notifyOnce` → `return` **im lặng**. Item không nhặt được, không một chữ nào, và đây đúng là ca C-4 mà adjudication ghi *"người chơi không còn bị nuốt im lặng"*.

Test canh hàng gate này không thấy được: `ItemListenerNoticeContractTest:33-45` chỉ **đếm regex trên text source** (`guards + corruptRefusals + tagSourceRefusals == notices`), và `:59-70` chỉ `assertTrue(body.contains("NOTICE_WINDOW_MS"))`. Cả hai xanh với một `notifyOnce` rỗng.

Hậu quả với hàng `LITE_RELEASE_GATES.md:36` *"Cancellation is never silent · Every refusal announces itself"*: câu đó đúng ở mức call-site, sai ở mức hành vi.

**Sửa tối thiểu:** đổi khóa map thành `player.getUniqueId() + "|" + key` (hoặc `Map<UUID, Map<String,Long>>` nếu muốn giữ cap theo người chơi); cap và prune giữ nguyên.

**Kiểm chứng:** từ source. Không chạy được server.

### M-2 · Cổng privacy chỉ soi những dòng có prefix hoặc số thứ tự; `LiteCommand` đã phát ra dòng không thuộc cả hai

`tools/lite-runtime/verify.py:407-416, 419-435, 447-453`

```python
def is_plugin_answer(message):
    return PLUGIN_PREFIX in message or bool(TIMELINE_ROW.match(message.strip()))
...
member = [message for player, message in rows
          if player == 'LiteMember' and is_plugin_answer(message)]
...
'member_leaked_actor': any('LiteStaff' in m for m in member),
```

`is_plugin_answer` là một **allowlist**, và `member_leaked_actor` chỉ chạy trên các dòng đã qua allowlist đó. Một dòng của plugin không mang `[ItemGuard LITE]` và không khớp `^\d+\.\s` là vô hình với kiểm tra rò rỉ.

Plugin **đã** phát ra đúng loại dòng ấy: `src/main/java/com/itemguard/lite/LiteCommand.java:158`

```java
entries.forEach(entry -> sender.sendMessage(LiteHistoryView.overviewLine(entry, isVietnamese())));
```

`LiteHistoryView.overviewLine` (`:79-83`) trả `"§6#" + code + " §f| latest: " + ... ` — gửi thẳng, không qua `message(...)`, nên không có prefix, và không bắt đầu bằng `N. `. Hôm nay dòng này không chứa tên người chơi nên chưa rò; nhưng cổng sẽ báo `member_leaked_actor=false` **bất kể** dòng đó chứa gì.

Cùng lỗ ở `plugin_lines`/`discloses_actor` (`:48-60`), đường mà `verify()` (`:544`) dùng.

Năm test contract (`tools/lite-runtime/test_contracts.py:129-172`) phủ ba cách "trông đúng mà không đúng", nhưng không có test nào cho dòng-không-prefix-không-số.

Hậu quả với hàng `LITE_RELEASE_GATES.md:48` (`member_leaked_actor=false` được nêu như bằng chứng runtime): giá trị đó đúng với tập dòng cổng chọn xem, không phải với những gì người chơi thấy.

**Kiểm chứng:** từ source cho cả hai phía. **Suy luận** một chỗ: để `member_foreign_rows=3` xảy ra được thì `bots.cjs` phải lột mã màu trước khi ghi log (vì `eventLine` bắt đầu bằng `"§7" + number`, và `'§71. …'.strip()` không khớp `^\d+\.\s`). Tôi không mở `tools/lite-runtime/bots.cjs` trong phiên này — đó là thứ sẽ chốt.

**Sửa tối thiểu:** trong `privacy_facts`, tính `member_leaked_actor` trên **toàn bộ** dòng gửi tới member, trừ đi các broadcast vanilla theo một denylist tường minh (`joined the game`, `left the game`, `<name> `), thay vì dựa vào allowlist hình thức.

### M-3 · `LITE_RELEASE_GATES.md:47` hứa một hành vi mà config không tạo ra được

Hàng gate:
> *"`0` still means 'no throttle'"*

`src/main/java/com/itemguard/config/AntiDupeSettings.java:50-59`
```java
long intervalTicks = Math.max(1L, config.getLong("performance.inventory-scan-interval", 600L));
...
long floor = cycleMillis > Long.MAX_VALUE / 2 ? Long.MAX_VALUE : cycleMillis * 2;
long configured = config.getLong("anti-dupe.detection-cooldown-ms", 300_000L);
return Math.max(configured, floor);
```

`intervalTicks >= 1` ⇒ `cycleMillis >= 50` ⇒ `floor >= 100`. Với mặc định 600 ticks thì `floor = 60_000`. Nên `detection-cooldown-ms: 0` trong config.yml **không bao giờ** đến được repository dưới dạng 0; nó thành 60 000. Nhánh `? > 0` ở `ItemSqliteRepository.java:1246` — và cả comment ở `:1238-1240` giải thích nó — chỉ có thể false khi test dựng repository trực tiếp (`DuplicateConfirmationEpochRuleTest`).

Hậu quả: hàng gate mô tả một hợp đồng vận hành cho admin ("đặt 0 để tắt throttle") mà sản phẩm không honour. Comment `:1238-1240` gọi nó là *"a documented behaviour"* — nó không được document ở đâu trong `config.yml`.

**Sửa tối thiểu:** hoặc cho `detectionCooldown` trả `0` nguyên vẹn khi `configured == 0` (bỏ qua floor cho giá trị 0 tường minh), hoặc sửa hàng gate 47 thành "0 chỉ có nghĩa đó ở mức API, config luôn được floor lên 2 chu kỳ".

### M-4 · Luật statement của cổng tiếng Việt: bất kỳ literal thứ hai nào cũng xóa được cảnh báo

`scripts/check_no_hardcoded_vietnamese.py:179-188`

```python
for literal in STRING_LITERAL.findall(chunk):
    why = is_vietnamese(literal)
    if why: flagged.append((literal, why))
    elif literal.strip() and not literal.strip().replace("-", "").isnumeric():
        english_beside_it = True
...
cleared = (bool(LANGUAGE_FLAG.search(chunk)) or inside_flagged_block[-1] or english_beside_it)
```

Luật rút gọn về: *"statement có ≥ 2 literal, trong đó ≥ 1 không phải tiếng Việt ⇒ tha"*. Không có gì kiểm rằng literal kia là **bản dịch tiếng Anh** của literal tiếng Việt. Mọi chuỗi phụ trợ đều đủ tư cách:

* khóa config — `config.getString("messages.foo", "Khong tim thay")` → tha
* mã màu — `sendMessage("§c" + "Khong the lam viec nay")` → tha (`"§c"` strip ra `§c`, `isnumeric()` false)
* dấu câu — `"-"`, `":"`, `"|"` đều tính là "English"

Thêm hai lỗ nhỏ hơn cùng hàm:
* `LANGUAGE_FLAG = re.compile(r"...|\bvietnamese\b")` (`:105`) khớp cả trong **comment**. `statements()` cắt theo `;{}` nên một dòng `// keep the vietnamese branch` ngay trên statement nằm cùng chunk ⇒ tha.
* `statements()` (`:149`) cắt cả bên trong string literal: một literal chứa `{`, `}` hoặc `;` bị xẻ đôi, `STRING_LITERAL` không còn khớp nó ⇒ literal biến mất khỏi mọi kiểm tra.

**Tình trạng hiện tại:** tôi grep diacritics trên `src/main/java` — 15 file, trong đó ngoài `EXEMPT_DIRS` chỉ có đúng 5 file là 5 `BILINGUAL_CLASSES` (`LiteCommand`, `LiteHistoryView`, `LiteMenuChrome`, `LossReason`, `CustodyPresentation`), đều dùng cờ thật. **Không có false negative sống hôm nay.** Đây là lỗ tiềm ẩn, nhưng docstring `:98-104` tự nhận chỉ một nửa của nó ("Still not provable: that the English branch is actually reachable") và không nhận nửa còn lại (literal phụ trợ bất kỳ cũng tha).

**Sửa tối thiểu:** đòi literal "bên cạnh" phải có độ dài ≥ 12 ký tự và chứa khoảng trắng (tức là một câu, không phải khóa/mã màu/dấu câu); bỏ comment (`//…`, `/*…*/`) khỏi chunk trước khi chạy `LANGUAGE_FLAG`. Thêm một test: `getString("messages.foo", "Khong tim thay")` phải là finding.

### M-5 · `HISTORIC` miễn trừ cả dòng dựa trên các chuỗi con xuất hiện trong văn xuôi bình thường

`scripts/check_listing_copies.py:62-63, 164-166`
```python
HISTORIC = re.compile(r"supersed|void|earlier|previously|no longer|rebind", re.IGNORECASE)
...
if HISTORIC.search(line): continue  # naming a dead jar while saying it is dead is not drift
```

`void` là chuỗi con của **`avoid`**. `earlier` và `previously` là từ thường. Một dòng hướng dẫn sống như *"To avoid confusion, upload `<64-hex cũ>`"* hoặc *"As mentioned earlier, the file to upload is `<64-hex cũ>`"* được miễn trừ hoàn toàn, và người mua được chỉ sang jar sai. Không có test nào cho ca này (`test_a_past_jar_named_where_the_line_says_it_is_past_is_not_drift` dùng đúng từ `superseded`).

**Sửa tối thiểu:** `\bvoid(ed)?\b` thay cho `void`, và thu hẹp `earlier|previously` thành các cụm có chủ ngữ rõ (`was superseded|is void|no longer|previously shipped|rebound`).

### M-6 · Điều khoản epoch giảm nhịp cảnh báo còn một nửa, không phải xuống bằng cửa sổ

`src/main/java/com/itemguard/persistence/ItemSqliteRepository.java:1241-1254`

Khi mệnh đề wall-clock thất bại (sweep tràn chu kỳ / TPS thấp — đúng ca H5), mệnh đề epoch chặn epoch **liền kề** ngay sau lần báo. Nhưng ở epoch N+2, hàng `duplicate_findings` gần nhất thuộc epoch N, không bằng `MAX(prior_epoch.scan_epoch)` = N+1 ⇒ báo lại. Kết quả thực tế: thay vì mỗi chu kỳ, staff nhận cảnh báo **mỗi hai chu kỳ**, cho tới khi duplicate biến mất — không phải một cảnh báo mỗi `detection-cooldown-ms`.

`LITE_RELEASE_GATES.md:47` viết *"so a sweep overrun or a slow server cannot make the window inert"*. Cửa sổ vẫn inert; cái được thêm là một bộ chia hai, độc lập với giá trị cấu hình. `AntiDupeSettings.java:42-45` mô tả chính xác hơn ("no longer carries the whole guarantee on its own") — hàng gate là chỗ nói quá.

**Sửa tối thiểu:** sửa chữ ở hàng gate 47 (`"halves the repeat rate when the wall-clock window is defeated; it is not a substitute for the window"`), hoặc — nếu muốn hành vi — đổi mệnh đề thành `previous.scan_epoch >= (epoch của lần báo cuối)` với một bộ đếm epoch, thuộc quyền quyết định sản phẩm.

### M-7 · `config.yml` nói "ít nhất một chu kỳ", code floor hai chu kỳ

`src/main/resources/config.yml:148-151`
```yaml
# Must be at least one scan cycle (performance.inventory-scan-interval below). A shorter window
# can never suppress anything, because the same identity is detected again one cycle later, so
# values below the cycle are raised to it rather than silently doing nothing.
detection-cooldown-ms: 300000
```

Code floor ở **hai** chu kỳ (`AntiDupeSettings.java:57`). Admin đặt `45000` (45 s, trên chu kỳ 30 s, hợp lệ theo comment) sẽ âm thầm được nâng lên 60 000. Đây đúng lớp "một claim trong tài liệu mà code không honour" — và là dòng comment mà L-5 của review #2 được cho là đã viết lại.

**Sửa tối thiểu:** đổi "at least one scan cycle" thành "at least two scan cycles" và giải thích một câu vì sao (khoảng cách thực giữa hai audit không bằng chu kỳ cấu hình).

---

## 4. LOW

**L-1 · `AntiDupeSettings.java:47-48`** — *"Floored rather than validated-and-refused: a server owner lowering this value wants fewer alerts, not a startup failure."* Hạ `detection-cooldown-ms` cho **nhiều** cảnh báo hơn, không phải ít hơn; và floor đúng là thứ từ chối yêu cầu đó. Câu lý do ngược chiều với setting.

**L-2 · `src/main/java/com/itemguard/custody/CustodyWindow.java:6-8`** — *"That value defaults to five seconds"* nói về detection cooldown. Mặc định hiện là `300_000` ms và còn được floor (`AntiDupeSettings.java:58`). Comment tồn tại để biện minh cho việc tách hai hằng số; lập luận của nó dựa trên một con số đã chết.

**L-3 · `scripts/verify_custody_rows.py:24, 80-83`**
```python
CUSTODY_WINDOW_MS = 15 * 60 * 1000  # tracking.custody-window-ms, the LITE default
...
assert max(gaps) < CUSTODY_WINDOW_MS
```
Giá trị đúng (`CustodyWindow.DEFAULT_MILLIS = 15 * 60_000L`, và `lite/config.yml` không set khóa đó — đã grep). Nhưng: (a) nó là bản sao hardcode, không đọc từ `config.yml` của fixture, nên một fixture đổi cấu hình sẽ được so với một con số hư cấu; (b) khoảng cách thực đo được là **2 201 ms** (`LITE_RELEASE_GATES.md:49`) so với cửa sổ 900 000 ms — assertion này không thể fail với bất kỳ run nào harness tạo ra. Nó là "một phép đo chỉ đúng với hình dạng dữ liệu harness tình cờ sinh ra". Docstring `:10-12` trình bày nó như một điều kiện tiên quyết được kiểm, hàng gate 49 nhắc lại — cả hai nên nói rõ nó vacuous ở quy mô này.

**L-4 · `src/main/java/com/itemguard/ItemGuard.java:88`** — sau `disablePlugin(this)`, các command khai trong `plugin.yml` vẫn nằm trong CommandMap của Bukkit và vẫn trỏ về plugin này. Admin đặt `general.enabled: false` rồi gõ `/ig` sẽ nhận `CommandException: ... plugin is disabled` (lỗi đỏ) thay vì câu giải thích vừa được log ở `:79-82`. **Suy luận** — dựa trên hành vi `PluginCommand.execute`/`SimplePluginManager.disablePlugin` của Bukkit, không có nguồn Paper trong repo này để chốt. Cùng nhóm suy luận: `JavaPluginLoader.enablePlugin` phát `PluginEnableEvent` **sau** khi `setEnabled(true)` trả về, tức sau khi ta đã tự disable — plugin khác nghe event đó thấy một ItemGuard "vừa enable" trong khi `isPluginEnabled()` là false. Cả hai đều không gây mất dữ liệu.

**L-5 · `src/main/java/com/itemguard/listeners/ItemListener.java:257, 277`** — `if (!tracking.hasCodeOrUuid(item)) { … return; }` rồi ngay sau đó `if (tracking.hasCodeOrUuid(item)) {`. Điều kiện thứ hai luôn đúng; nó là một lần đọc PDC thừa trên mọi inventory click. Cùng lớp với M-2 của review #2 (nhánh chết ở `:268-295`) nhưng nhẹ hơn.

**L-6 · `scripts/check_listing_copies.py:112-131` + `candidates():143`** — `stale_jars_inside` chỉ chạy cho `.zip`. Một `.jar` trần nằm cạnh listing không bao giờ được băm: `release/upload/ItemGuard-LITE-1.0.0.jar` tồn tại trong cây và docstring `:5` liệt kê chính "a second `release/upload/` jar" là một trong bảy bản sao đã drift. Bằng chứng duy nhất cho nó là `release/SHA256SUMS.txt` — một file text mà cổng đọc như text, không phải một phép băm lại. `LITE_RELEASE_GATES.md:13` (*"byte-identical to `release/upload/` … per `release/SHA256SUMS.txt`"*) đúng là một claim dựa trên file tự khai. Sửa tối thiểu: băm mọi `*.jar` trong `SCAN` và so với jar ship, không chỉ jar bên trong archive.

---

## 5. Đã tấn công mà không phá được

Theo đúng thứ tự brief liệt kê.

**`check_no_hardcoded_vietnamese.py`**
* **Parser constant pool** — tôi đối chiếu từng tag với JVMS: 1 Utf8, 3/4 (4 byte), 5/6 (8 byte + chiếm 2 slot, `index += 1` ở `:308` là đúng), 7/8/16/19/20 (2 byte), 9/10/11/12/17/18 (4 byte), 15 MethodHandle (3 byte: `reference_kind` u1 + `reference_index` u2, `:309-310` đúng). Không còn tag nào của class file hiện đại bị bỏ sót. Vòng lặp thoát khi `offset >= len(data)` rồi `:320-321` `raise` nếu `index < count` — không có đường trả về im lặng. **`UnparsedClass` không nuốt được gì.**
* **`scan_jar` báo unparsed như finding** (`:343-350`) thay vì skip — tôi thử tìm đường để một class hỏng thành "đã kiểm": không có.
* **`referenced_from_outside`** — wildcard (`:224`) và tên trần (`:240`) đều có. Tôi thử tấn công bằng `import static com.itemguard.commands.ItemCodeInput.normalize;` (khớp `:219`), bằng FQN trong comment (khớp `:218`), bằng tên trần trong một file ở package khác (khớp `:240`). Không dựng được tham chiếu nào lọt. Hàm over-report có chủ ý và làm đúng như vậy.
* **Luật khai báo undeclared** — `main():384` trừ cả hai dict rồi `return 1` nếu còn dư; `test_the_declared_files_exist` (`:191-195`) làm rename thành đỏ. Không tìm được cách khai một file rồi xoá nó mà cổng vẫn xanh.

**`check_listing_copies.py`**
* **`record_marker`** — `MARKER.match(stripped)` sau `lstrip("#>* \t")` (`:80-81`). Tôi thử `| Superseded |` (bảng), `Candidate X is SUPERSEDED` (văn xuôi), `## Record of handovers` (L4), marker ở dòng 21: cả bốn đều bị từ chối, và cả bốn đều có test. **Luật "phải là tuyên bố về chính file này" đứng vững.**
* **`stale_jars_inside`** — băm thật, `except BadZipFile → ["unreadable archive"]` (fail-closed). Archive hỏng không thành pass.
* **`text_of` cho zip** — đọc mọi entry có suffix trong `SUFFIXES`; tôi không dựng được entry text nào lọt ngoài (`.bbcode`, `.txt`, `.md`, `.html` đều có).

**`run_release_runtime_gates.py`**
* **Verdict không đọc exit code** — `standalone_gate:103-106` đọc `status` từ file. `reload.py:42` khởi tạo `{'status': 'FAILED'}` và ghi file trong `finally` (`:154-165`); `power_cut.py:66` giống hệt. Một gate crash giữa chừng để lại `FAILED` trên đĩa ⇒ REJECTED. **Không có đường "chết mà PASS".**
* **Verdict file thiếu** → `:100-102` REJECTED. **Verdict file hỏng JSON** → `json.loads` ném `ValueError`, bắt bởi `except Exception` ở `main():154` ⇒ REJECTED. Cả hai fail-closed.
* **Gate không chạy** — `newest_fixture:52-56` đòi đúng **một** fixture mới, `standalone_gate:96-98` cũng vậy. Một script không tạo fixture ⇒ REJECTED.
* **Ràng buộc candidate** — tôi tìm cách để runner in `candidate 80fc610b…` cho một run dùng jar khác: `smoke.py:217` `if sha(product) != EXPECTED: raise RuntimeError('Candidate mismatch')`, và cả bốn standalone gate đều gọi `smoke.stage()` (`reload.py:34`, `power_cut.py:59`; `two_plugin.py`/`multi.py` đi qua cùng đường). Jar bị thay mà không repin ⇒ stage ném ⇒ không có fixture ⇒ REJECTED. **Runner không cần tự so, và vẫn không nói dối được.**
* **Tên gate lạ** — `main():129-134` `return 2` trước khi khởi động server nào. Ca "0/0 gates PASS, exit 0" đã bị đóng.
* **`verify.py` exit code** — mọi exception còn lại (IndexError/TypeError/AttributeError) vẫn cho exit 1 qua traceback, nên `smoke_gate:77` không thể đọc 0 từ một verifier vỡ.

**`tools/lite-runtime/verify.py`**
* **`bot_messages`** (`:385-404`) — đúng như docstring: chỉ lấy trường `message`, `row.get('event') == 'message'`, bỏ dòng không parse được. Tôi thử tấn công bằng tên actor nằm trong **envelope** (`{"player":"LiteStaff",...}`) — không lọt, vì `rows` chỉ giữ `row['message']`. **Lỗi "assert trên envelope" đã thật sự được sửa.**
* **`adjudicate_privacy` thứ tự assert** (`:447-453`) — kiểm `staff_saw_actor` trước, rồi `member_answer_lines`, rồi rò rỉ, rồi redaction. Tôi thử ba ca vacuous mà docstring nêu: cả ba đều có test và đều đỏ. `member_answer_lines` một mình có thể được thỏa bởi một dòng permission-denial, **nhưng** `member_saw_redaction` (`:452`) đòi chuỗi `another player (staff only)` thật, nên nhánh redaction vẫn buộc phải chạy. Không phá được ở mức này — lỗ duy nhất là allowlist `is_plugin_answer` (M-2).
* **`verify_multi`** (`:457-495`) — kiểm port bằng `connect_ex` thật (`:479-481`) chứ không tin field; `candidate_sha256` (`:32-46`) băm lại từng artifact **và** đối chiếu `stage['candidate_sha256']` với hash jar thật, nên một `stage.json` khai bừa bị bắt. Đòi `summary.status == 'PASS_MULTI'` **sau** khi đã tự phán từ log. Không dựng được đường để `multi.json` tự phong PASS.

**`scripts/verify_custody_rows.py`**
* `assert len(tracked) == 1` (`:47`), `len(distinct) == 2` (`:57`), `len(handovers) >= 4` (`:74`), `last_action == 'CLEARED'` (`:85`), `pragma integrity_check` (`:42-43`) — đều đọc từ DB thật của fixture, không từ marker của probe. `spawner = next(...)` không có default nên thiếu hàng `SPAWN` thành `StopIteration` → exit 1. **Không tìm được đường cho một DB rỗng hay một DB của run khác đi qua.** Điểm yếu duy nhất là L-3 (một assertion vacuous), không phải một pass sai.

**Product**
* **`ItemGuard.onDisable` an toàn từ đường guard** — `:113-139` null-check đủ cả tám collaborator (`catalogUi`, `guiListener`, `filterChatListener`, `scheduledInventoryScan`, `inventoryScanTask`, `cleanupTask`, `lossListener`, `databaseManager`). Chỉ `loadConfigManagers()` đã chạy, và không collaborator nào trong số đó do nó tạo. **Không NPE.** Câu log `:80-82` ("the database file is not opened") cũng đúng: `DatabaseManager` được dựng trong `loadRuntimeManagers()` (`:175`), sau guard.
* **`notifyOnce` không bao giờ prune mất entry vừa ghi** — prune ở `:85-98` chạy **trước** `put` ở `:99`. Và map có cận trên thật: sau nhánh `:90-97` kích thước rớt về ~`MAX/2` rồi mới `put`, nên không vượt `NOTICE_MAX_PLAYERS` dù burst. Hai câu hỏi của brief, cả hai đều giữ.
* **Công tắc craft không mở được nhánh tagged** — `CraftOutputPolicy.decide:28-32` `return` trong `if (hasIdentity)` trước khi đọc `cancelUntrackedCraftOutput`. Cờ chỉ ảnh hưởng `:36-38`. **Claim ở `:16-20` và hàng gate 37 đúng.** (Vấn đề là câu *thông báo*, H-3, không phải chính sách.)
* **`ContainerListener.cooldownKey` nhánh `DoubleChest`** (`:138-145`) — key dựng từ `getBlockX/Y/Z` của `DoubleChest.getLocation()`, tức toạ độ trung điểm hai nửa rương: ổn định giữa các lần mở, giống nhau cho cả hai nửa, khác nhau cho hai rương đôi kề nhau. `location == null` và `location.getWorld() == null` đều có nhánh. Thứ tự `BlockState` → `DoubleChest` → `Entity` đúng (`DoubleChest` không phải `BlockState`). **Không phá được.** Một nhận xét, không phải lỗi: cùng một rương đôi vẫn sinh `BLOCK:` (khi holder là một nửa `Chest`) và `DOUBLE:` (khi holder là `DoubleChest`) — hai key khác nhau, nên cooldown không bắc cầu giữa hai đường đó; comment không hứa điều ngược lại.
* **Clamp tràn số trong `detectionCooldown`** — `:54-57` clamp **trước** phép nhân (`intervalTicks > Long.MAX_VALUE / 100L`, bảo thủ hơn hệ số 50 thực dùng) rồi lại clamp trước `* 2`. Tôi thử `inventory-scan-interval: 9223372036854775807` và `: -1`: cả hai cho floor hữu hạn dương, không âm, không 0. **Bug tràn số đã đóng.**
* **Marker `created_at = 0` bền** — `adoptIdentity` (`:1573-1582`) ghi 0; `upsertItem` (`:1648-1664`) `ON CONFLICT(code) DO UPDATE SET` **không** liệt kê `created_at`, nên mọi tương tác sau đó (pickup/drop/clear) không xoá được marker. `created_at = excluded.created_at` ở `:524` thuộc bảng khác (`item_search_requests`). Grep toàn bộ `created_at` trong `src/main/java`: chỗ duy nhất `ORDER BY created_at` là `:593`, cũng là `item_search_requests`. **Claim "nothing sorts on this column" ở `:1565` đúng.** `CheckCommand.java:82` `data.getCreatedAt() <= 0L` khớp đúng marker đó.
* **SQL `NOT EXISTS` chỉ khớp `item_uuid`** (`:1242`) trong khi phần còn lại group theo `(item_uuid, code)` — tôi nghi ngờ đây là hẹp sai, nhưng unique index là `idx_duplicate_finding_identity_epoch ON duplicate_findings(item_uuid, scan_epoch)` (`SqliteSchemaManager.java:95-97`), tức bảng vốn dedupe theo `item_uuid` mỗi epoch. **Nhất quán, không phải lỗi.**
* **`MessageManager` + bốn file** — `DEFAULT_MESSAGES` toàn tiếng Anh (`:30-85`), `MessageManagerFallbackTest:31-37` ghim nó khớp `messages_en.yml` hai chiều, `:56-61` đòi `messages.yml` và `messages_zh.yml` có **đúng cùng tập khóa**. Năm khóa mới (`identity-not-ready`, `identity-corrupt`, `craft-refused`, `craft-refused-tagged`, `pickup-tagging`) có mặt đủ trong cả bốn nguồn — tôi grep và đếm được cả ba file yml + map. `package_lite.py:55-72` drop `messages.yml`/`messages_zh.yml` khỏi jar **và** kiểm lại bằng allowlist ba resource gốc, nên không có file ngôn ngữ thứ tư đi nhờ. **Đường fallback không nói tiếng Việt được nữa.**
* **`commands/ItemCodeInput.java:22`** — literal tiếng Anh, đúng như comment. Nửa source của cổng thật sự quét nó (được khai `SCANNED_INSIDE_EXEMPT`). Vấn đề duy nhất là nửa jar (H-1).

---

## 6. Những gì tôi KHÔNG kiểm được trong phiên này

1. **Không chạy gì cả** — read-only theo yêu cầu. Mọi con số trong `LITE_RELEASE_GATES.md` (851/851 test, 57/57 script test, 106/106 contract, 361 class entries, 180 file, 9/9 version) là **chưa xác minh**; tôi chỉ kiểm được rằng logic sinh ra chúng có thể/không thể sai theo cách nào.
2. **`tools/lite-runtime/bots.cjs`** — chưa mở. Nó quyết định M-2 có phải là lỗ duy nhất của đường privacy hay còn nghiêm trọng hơn (nếu log giữ nguyên `§`, thì `TIMELINE_ROW` không khớp dòng nào và `member_foreign_rows=3` không thể xảy ra — mâu thuẫn với hàng gate 48, đáng điều tra).
3. **`multi.py`, `two_plugin.py`, `smoke.py` phần thân** — tôi chỉ đọc `stage()`/`EXPECTED` và các điểm ghi verdict. Không kiểm được liệu chính các scope đó có dựng đúng tình huống chúng khai hay không.
4. **Các fixture runtime** (`E:/AI.WORK/30_KET_QUA_THU_NGHIEM/…`) nằm ngoài repo; không đọc. Mọi hàng gate trích dẫn fixture (39-46, 48, 49) là lời khai không kiểm lại được từ đây.
5. **Danh sách recipe vanilla 1.21.11 sao chép `minecraft:custom_data`** — quyết định mức độ thường gặp của H-3. Không có nguồn trong repo.
6. **Thứ tự lifecycle của Bukkit/Paper quanh `disablePlugin` gọi từ trong `onEnable`** (L-4) — suy luận từ hành vi đã biết của `JavaPluginLoader`, không có source Paper trong cây.
7. **Các hàng OPEN** (50-55, 57, 58) — không kiểm theo yêu cầu.
8. **`scan()` trên toàn cây có thật sự đọc 180 file hay không**, và `checked > 100` của `test_the_shipping_source_tree_has_no_unflagged_vietnamese_literal` có tương ứng với thực tế — cần chạy.
