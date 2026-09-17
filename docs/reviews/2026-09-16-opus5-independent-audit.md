Đã đọc xong source. Dưới đây là báo cáo audit.

---

# BÁO CÁO AUDIT ItemGuard — 2026-09-16

**Phạm vi đã đọc:** `src/main/java` (~200 file), `src/main/resources`, `pom.xml`, `scripts/package_lite.py`, `scripts/verify_lite_artifact.py`, `docs/RISK_REGISTER.md`, `src/test` (173 file test). Review-only, không sửa, không build.

**Tóm tắt:** kiến trúc lõi (publication 2-pha, fail-closed identity, single-owner SQLite) chất lượng rất cao và các ràng buộc kỹ thuật bạn nêu đều **đúng như mô tả** trong code. Nhưng chính cái fail-closed đó tạo ra 3 lỗi Critical mà chỉ chơi thật mới lộ, và 2 cổng chặn LITE chặn **tên file**, không chặn **văn bản VI/ZH** — mà VI đang nằm hardcode trong `.class`.

---

## 1. CRITICAL

### C1 — Item di chuyển giữa chừng một đợt sweep bị kết luận là dupe (false positive)
**File:** `src/main/java/com/itemguard/tasks/InventoryScanTask.java:90-104`, `:111-126`; `src/main/java/com/itemguard/persistence/ItemSqliteRepository.java:1194-1221`

`run()` quét toàn bộ túi người chơi online **tại tick T**, ghi observation với `scan_epoch = E`. Sau đó `startSweepPass()` chụp danh sách chunk và `advanceSweep()` rải việc quét container ra nhiều tick (`chunks-per-tick: 8`). Container observation cũng ghi vào **cùng epoch E**.

Query audit chỉ nhóm theo `(item_uuid, code)` và `HAVING COUNT(*) >= 2` (`ItemSqliteRepository.java:1217-1218`):

```sql
WHERE observations.scan_epoch = ?
  AND observations.epoch_complete = 1
GROUP BY observations.item_uuid, observations.code
HAVING COUNT(*) >= 2
```

Nên kịch bản sau sinh `CONFIRMED` (không phải `SUSPECTED`):

1. Tick T: kiếm netherite nằm slot 3 của người chơi → observation key `(PLAYER, uuid, 3)`, epoch E.
2. Người chơi bỏ kiếm vào rương.
3. Tick T+n: sweep tới chunk chứa rương → observation key `(CONTAINER, world:x:y:z, slot)`, **vẫn epoch E**.
4. Sweep xong → `epoch_complete = 1` → COUNT = 2 → **CONFIRMED duplicate**.

**Độ rộng cửa sổ:** 8 chunk/tick. Server 2.000 chunk loaded = 250 tick ≈ **12,5 giây**; 10.000 chunk ≈ **62 giây**. Mọi thao tác rương→túi, túi→rương, rương→rương (kể cả hopper/sorting tự động) trong cửa sổ đó đều báo dupe giả.

**Tệ hơn:** `src/main/resources/lite/config.yml:9` đặt `anti-dupe.enabled: true` cho bản **đã release**, trong khi `config.yml:127` của FULL đặt `false`. Nghĩa là bản LITE đang bán là bản duy nhất bật tính năng này.

**Lưu ý dương:** double chest **không** bị false positive — `BlockContainerPhysicalSlotResolver.resolve()` (`:29-39`) quy về đúng nửa vật lý. Đây là phần làm đúng.

**Hướng sửa:** epoch phải là *snapshot tại một thời điểm*, không phải *khoảng thời gian*. Ba lựa chọn theo thứ tự ưu tiên:
- Chỉ so sánh observation **cùng loại nguồn** (CONTAINER↔CONTAINER) trong một pass sweep, và so PLAYER↔PLAYER riêng ở tick quét túi.
- Hoặc: khi ghi observation mới cho một `item_uuid` trong cùng epoch, **xoá** observation cũ của chính `item_uuid` đó nếu `observed_at` chênh quá ngưỡng (item đã di chuyển, không phải nhân bản).
- Hoặc tối thiểu: hạ `CONFIRMED` xuống `SUSPECTED` khi hai observation cách nhau > ~1 giây, và yêu cầu **2 epoch liên tiếp** cùng phát hiện mới báo.

---

### C2 — Item trong ender chest / rương xe mỏ bị khoá vĩnh viễn sau khi restart server
**File:** `src/main/java/com/itemguard/listeners/ItemListener.java:217-221`; `src/main/java/com/itemguard/tracking/IdentityReadinessCoordinator.java:24,40-43`; `src/main/java/com/itemguard/services/ItemTrackingService.java:974,995-999`; `src/main/java/com/itemguard/tracking/BlockContainerPhysicalSlotResolver.java:66-78`

Chuỗi nhân quả:

1. `IdentityReadinessCoordinator.readyIdentities` là cache **in-memory** (`BoundedIdentitySet(50_000)`), **mất sạch khi restart**.
2. `ItemListener.onInventoryClick` huỷ click nếu `!isIdentityReady(item)` (`:218-221`). `isIdentityReady` **chỉ đọc cache**, không tự gọi reconcile (`ItemTrackingService.java:207-212`).
3. Cache chỉ được nạp lại qua `reconcilePhysicalIdentity()`, gọi từ `scanPlayerInventory` (`:874`) và `recordContainerInventoryObservations` (`:1003`).
4. `recordLoadedContainerObservations` **bỏ qua ender chest** (`:974`), và `BlockContainerPhysicalSlotResolver.resolveSide` yêu cầu `getHolder() instanceof org.bukkit.block.Container` (`:70`). Ender chest holder là `Player`; chest minecart holder là `StorageMinecart` → cả hai trả `Optional.empty()` → `continue` (`:997-999`).

**Kết quả:** một thanh kiếm đã tag nằm trong ender chest, server restart → người chơi mở ender chest, click vào kiếm → `event.setCancelled(true)`, **không có thông báo gì**. Không có đường nào đưa identity đó vào cache. Item bị khoá vĩnh viễn trong ender chest. Tương tự với chest minecart và mọi virtual inventory.

Đây chính là hệ quả chưa được ghi nhận của giới hạn "mù đồ trong ender chest/rương xe mỏ" mà bạn nêu: vấn đề không chỉ là *không phát hiện dupe ở đó*, mà là *item ở đó không dùng được nữa*.

**Hướng sửa:** khi `isIdentityReady` trả false cho item có tag COMPLETE, phải **kích hoạt reconcile lazily** (như `isEntityIdentityReady` đang làm ở `:214-228`) thay vì chỉ đọc cache; và cho phép reconcile với `sourceKey` dạng "unknown-holder" khi resolver không xác định được vị trí vật lý. Ngắn hạn: không huỷ event khi item có tag COMPLETE mà chỉ ghi log — fail-open cho *đọc*, fail-closed chỉ cho *cấp identity mới*.

---

### C3 — Item có tag nhưng mất bản ghi canonical trong DB → không nhặt được, không dùng được, despawn mất luôn
**File:** `src/main/java/com/itemguard/persistence/ItemSqliteRepository.java:1411-1451`; `src/main/java/com/itemguard/listeners/ItemListener.java:162-173`

`reconcileTagPublication` trả `true` **chỉ khi** có row trong `tracked_items` (`:1417-1426`), hoặc có `tag_publications` state `PREPARED` khớp **chính xác cả `source_key` lẫn `sha256`** (`:1428-1444`). Mọi trường hợp khác trả `false` — **vĩnh viễn**.

Các kịch bản thực tế làm mất row canonical:
- Admin xoá/đổi tên `itemguard.db` để "fix lag" (rất phổ biến).
- Restore backup DB cũ hơn state của thế giới.
- Chuyển world/item sang server khác (đúng bối cảnh Premium MySQL đa server bạn đang làm).
- Publish thất bại sau khi đã ghi NBT: `AsyncTagPublicationCoordinator.java:167` ghi tag vào item **trước**, `:174` publish sau; nếu publish fail thì item mang tag còn DB ở state PREPARED với `source_key` của slot cũ. Item rơi xuống đất → reconcile dùng `TagPhysicalSourceKey.entity(...)` → `source_key` **không khớp** → `false` mãi mãi.

Khi đó `onItemPickup` huỷ pickup mỗi lần (`:163-166`), không thông báo. Item nằm đất → **despawn sau 5 phút → mất thật**. `ItemLossListener.onGroundItemDespawn` sẽ ghi log mất, nhưng item đã mất rồi.

**Hướng sửa:** thêm đường tự phục hồi — khi tag COMPLETE nhưng không có canonical row, **tự adopt**: chèn row `tracked_items` từ chính tag trên item (đánh dấu `origin = ADOPTED_UNVERIFIED`) thay vì từ chối. Song song: không bao giờ huỷ `PlayerPickupItemEvent` — pickup là hành động không thể mất mát nếu sai; nếu buộc phải fail-closed thì phải gửi actionbar/message cho người chơi.

---

## 2. HIGH

### H1 — `FilterChatListener` "treo không tắt": nuốt tin nhắn chat nhiều giờ sau
**File:** `src/main/java/com/itemguard/gui/FilterChatListener.java:17,24-28,33-36`

`pendingFilters` (`ConcurrentHashMap`) **không có TTL**. Admin bấm nút Filter trong GUI → entry được đặt → nếu admin không gõ gì (đóng GUI, chạy lệnh khác, đi làm việc khác), entry ở lại đến khi quit.

Tin nhắn chat **bất kỳ** sau đó — kể cả 3 tiếng sau, giữa một cuộc trò chuyện bình thường — bị `event.setCancelled(true)` (`:36`), biến mất khỏi chat, và bị diễn giải thành filter query. Đúng mô hình lỗi "hologram không tắt" bạn mô tả.

**Hướng sửa:** lưu timestamp, hết hạn sau ~30s; huỷ khi `InventoryCloseEvent`; và khi hết hạn thì gửi một dòng "đã huỷ yêu cầu lọc".

*Phụ:* `AsyncPlayerChatEvent` đã deprecated trên Paper. Nếu server dùng chat plugin theo `AsyncChatEvent`, listener này không bao giờ chạy → nút Filter im lặng không làm gì.

---

### H2 — Đọc DB đồng bộ trên main thread, sau một executor serial fsync=FULL
**File:** `src/main/java/com/itemguard/commands/CheckCommand.java:70,77`; `src/main/java/com/itemguard/commands/StatsCommand.java:35`; `src/main/java/com/itemguard/commands/MatDoCommand.java:86,118`; `src/main/java/com/itemguard/ItemGuard.java:141`; `src/main/java/com/itemguard/services/ItemTrackingService.java:615,757,761`; `src/main/java/com/itemguard/tasks/CleanupTask.java:21`

`SqliteConnectionOwner.call()` (`:119-133`) block trên `future.get()` **không timeout**, chờ một executor đơn luồng (`SerialDatabaseExecutor`) đang commit với `PRAGMA synchronous = FULL` (`SqliteConnectionOwner.java:256`) — tức mỗi commit là một fsync.

`/igcheck` thực hiện **2 vòng blocking** (`getItem` + `getHistoryCount`). Nếu hàng đợi đang có vài trăm write (hopper traffic, sweep observation), main thread đứng chờ hết hàng đợi. Không có timeout ⇒ watchdog Paper có thể crash server thay vì chỉ lag.

Nặng nhất: `CleanupTask` được schedule bằng `runTaskTimer` (**sync**, `ItemGuard.java:241`) và gọi `deleteOldHistory` → `DELETE FROM item_history WHERE ...` trên bảng có thể hàng triệu row, **chặn main thread** cho tới khi xong.

**Hướng sửa:** chuyển toàn bộ command sang `*Async` (đã có sẵn `getItemAsync`, `getStatsAsync`, `getHistoryAsync`); `CleanupTask` → `runTaskTimerAsynchronously` + `callAsync`; thêm timeout cho `call()` và log cảnh báo khi vượt ngưỡng.

---

### H3 — `ContainerListener.cooldowns` là map không giới hạn (memory leak)
**File:** `src/main/java/com/itemguard/listeners/ContainerListener.java:28,95-106`

```java
private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();
...
cooldowns.put(holder.toString(), System.currentTimeMillis());
```

Không bao giờ prune. Mỗi container server từng chạm vào để lại một entry vĩnh viễn. `onInventoryMoveItem` chạy **mỗi hopper mỗi tick** ⇒ tăng rất nhanh trên server có sorting system. Sau vài ngày uptime là hàng trăm nghìn entry giữ String.

Thêm nữa, key là `holder.toString()`. Với `CraftBlockState` thì chuỗi có toạ độ (còn phân biệt được), nhưng với holder rơi về `Object.toString()` thì chuỗi chứa identity hash **đổi theo từng snapshot** ⇒ cooldown không bao giờ trúng **và** map phình nhanh gấp bội.

**Hướng sửa:** dùng `Location`/`BlockVector` làm key (không dùng `toString()`), và thay bằng cache có TTL (Caffeine hoặc `LinkedHashMap` LRU với sweep định kỳ).

---

### H4 — Cảnh báo dupe lặp vô hạn 30 giây/lần, `duplicate_findings` phình không giới hạn
**File:** `src/main/java/com/itemguard/tasks/InventoryScanTask.java:137-163`; `src/main/java/com/itemguard/persistence/ItemSqliteRepository.java:1191-1193,1212-1216`

Cooldown chống lặp là `detection-cooldown-ms: 5000` (`config.yml:140`), nhưng chu kỳ quét là `inventory-scan-interval: 600` tick = **30 giây**. 5s < 30s ⇒ cooldown **không bao giờ chặn được gì**.

Hệ quả: một item bị đánh dấu dupe (thật hoặc giả — xem C1) sẽ:
- Ghi 1 row mới vào `duplicate_findings` mỗi 30s = **2.880 row/ngày/item**, không có retention (`deleteOldHistory` chỉ đụng `item_history`).
- Gửi 2 dòng chat cho **mọi** staff online, mỗi 30 giây, **mãi mãi**, không có cách acknowledge/dismiss.

Với 50 item dupe → 100 dòng chat mỗi 30 giây. Staff sẽ tắt `notify-staff` và tính năng chết.

**Hướng sửa:** cooldown mặc định phải ≥ chu kỳ quét (ví dụ 3600000ms); thêm trạng thái `ACKNOWLEDGED` cho finding và lệnh `/ig dupe ack <code>`; thêm retention cho `duplicate_findings`.

*Ghi nhận đúng:* cảnh báo chỉ in **số lượng** vị trí (`:153-155` dùng key `dupe-locations` với placeholder `{locations}` là `finding.distinctLocations()`), không lộ toạ độ — đúng như thiết kế bạn mô tả.

---

### H5 — Hai cổng chặn LITE chỉ chặn TÊN FILE, không chặn văn bản VI/ZH
**File:** `scripts/package_lite.py:50,53,61-62`; `scripts/verify_lite_artifact.py:11,161-169,220-234`; `src/main/java/com/itemguard/MessageManager.java:22-69,77-95`

Cả hai cổng đều dùng một **denylist tên file cứng**:
```python
dropped = {'messages.yml', 'messages_zh.yml'}      # package_lite.py:50
DROPPED_FROM_LITE = {'messages.yml','messages_zh.yml'}  # verify_lite_artifact.py:161
```
và chỉ scan nội dung của `plugin.yml` + `config.yml` (`verify_lite_artifact.py:183-234`). **Không có entry `.class` nào được kiểm tra.** Ba lỗ hổng thật:

**(a) Tiếng Việt hardcode trong `.class` đang nằm trong jar LITE.**
`MessageManager.DEFAULT_MESSAGES` (`:22-69`) là map tiếng Việt biên dịch vào `MessageManager.class`, và nó là **fallback cho mọi key thiếu**: `getRaw()` tại `:130` trả `DEFAULT_MESSAGES.getOrDefault(key, key)`. Hiện `messages_en.yml` phủ đủ 43/43 key nên chưa lộ — nhưng thêm một key mới trong code là VI rò ra ngay, **không cổng nào phát hiện**.

Log console tiếng Việt cũng nằm trong `.class` LITE:
`ItemTrackingService.java:407` `"Khong the reserve entity identity publication"`, `:420-422` `"Identity da ghi vao entity nhung canonical publish chua hoan tat; giu fail-closed de reconcile"`, `:426`, `:476`, `:489-491`, `:495`; `CheckCommand.java:58,66,88,92,94`.

**(b) `language: vi` tái tạo đúng cái file đã bị xoá.**
`lite/config.yml:5` ghi `language: en # en or vi` — chính thức quảng cáo `vi` là hợp lệ. Khi admin đặt `vi`:
`MessageManager.load()` → `messages_vi.yml` không tồn tại → nhánh `"vi".equals(lang)` (`:82-83`) trỏ về `messages.yml` → cũng không tồn tại (đã bị cổng xoá) → `createDefaultMessages()` (`:94`) **ghi ra `plugins/ItemGuard/messages.yml` toàn tiếng Việt**. Kết quả: đúng kịch bản mà comment ở `package_lite.py:46-49` nói là muốn ngăn.

Đồng thời `LiteCommand` có sẵn cặp EN/VI hardcode (`LiteCommand.java:35-45` và ~30 chỗ gọi `text(en, vi)`), `LiteMenuChrome`, `LiteHistoryView` cũng vậy — nên UI LITE **chuyển hẳn sang tiếng Việt** trong khi messages thì hỗn hợp.

**(c) Denylist không mở rộng được.**
`expected = {full_names} - lite/ - DROPPED`. Thêm một resource mới tên `messages_vi.yml`, `lang/vi.yml`, hay `help_zh.txt` → có mặt ở cả hai jar → **pass cả hai cổng**.

**Hướng sửa:**
- Đổi sang **allowlist** entry resource: liệt kê chính xác tập non-`.class` được phép có trong jar LITE.
- Thêm gate quét **toàn bộ entry** (kể cả `.class`, decode UTF-8 phần constant pool) tìm ký tự trong dải `U+00C0–U+1EF9` và `U+4E00–U+9FFF`; whitelist theo hash cho các lớp đã biết cho tới khi dọn xong.
- Thêm test parity: mọi key trong `MessageManager.DEFAULT_MESSAGES` phải có trong `messages_en.yml`; đổi `DEFAULT_MESSAGES` sang tiếng Anh, đưa VI về `messages.yml`.
- Hoặc gate `language` ở LITE: `getLanguage()` trả cứng `"en"` khi `isLiteEdition()` (hiện chỉ là *default*, `ConfigManager.java:45-47`), và bỏ `# en or vi` khỏi `lite/config.yml`.

---

### H6 — Hopper fail-closed làm hỏng hệ thống phân loại đồ, không một lời cảnh báo
**File:** `src/main/java/com/itemguard/listeners/ContainerListener.java:65-68`; `src/main/java/com/itemguard/tracking/HopperTransferPolicy.java:16-22`

`HopperTransferPolicy.decide()` trả `CANCEL` khi item **chưa có identity nhưng đủ điều kiện track**, hoặc khi source/destination không phải block container. `ContainerListener:67` `event.setCancelled(true)`.

Trên server thật: mọi item sorter dùng hopper chở kiếm/cuốc/giáp/shulker sẽ **im lặng ngừng hoạt động**; item dồn ứ trong hopper. Với minecart/virtual topology thì `CANCEL` là vĩnh viễn (không có nhánh scan-source). Người chơi báo "server hỏng", admin không có manh mối vì không có log.

Risk register có ghi IG-R006 về "hopper topology fail-closed" nhưng chỉ ở góc độ *đúng đắn anti-dupe*, **không ghi hệ quả gameplay**. Và LITE bật mặc định (`lite/config.yml:29` `container-scan-enabled: true`).

**Hướng sửa:** khi CANCEL vì lý do topology, log WARNING kèm toạ độ (rate-limited) để admin thấy; cân nhắc cho phép ALLOW với item chưa tag khi destination là block container (không có nguy cơ dupe, chỉ trễ tag).

---

### H7 — Discord webhook: không timeout, injection `@everyone`, và toàn bộ tích hợp là code chết
**File:** `src/main/java/com/itemguard/integrations/DiscordWebhook.java:13,21,32,73-92`

- **`sendDuplicateAlert` và `send` không được gọi từ bất kỳ đâu** trong `src/main/java` (đã grep toàn bộ). `ItemGuard.getDiscordWebhook()` cũng không có caller. Toàn bộ block `discord:` trong `config.yml:288-301` hứa một tính năng **không tồn tại**.
- Nếu bật lại: `HttpURLConnection` (`:76-88`) **không set `setConnectTimeout`/`setReadTimeout`** ⇒ mặc định vô hạn. Host Discord không reachable ⇒ thread async của Bukkit treo vĩnh viễn; với cooldown chỉ 500ms, các alert liên tiếp sẽ vét cạn async pool và làm chậm async của **mọi plugin khác**.
- Payload không set `allowed_mentions`. `itemName` lấy từ display name do người chơi đặt ⇒ đặt tên item là `@everyone` sẽ ping toàn Discord server.

**Hướng sửa:** hoặc gỡ hẳn tích hợp + block config, hoặc: nối dây thật, thêm `setConnectTimeout(5000)/setReadTimeout(5000)`, thêm `"allowed_mentions":{"parse":[]}`, đọc `webhookUrl` live thay vì cache ở constructor (`:21` — hiện `/ig reload` không đổi được URL).

---

### H8 — `sqlite-jdbc` không được relocate trong shade → xung đột driver giữa các plugin
**File:** `pom.xml:171-176`

Chỉ `org.bstats` được relocate. `org.xerial:sqlite-jdbc` shade nguyên package `org.sqlite`. Kết nối mở qua `DriverManager.getConnection("jdbc:sqlite:" + path)` (`SqliteConnectionOwner.java:243`).

`DriverManager` là registry **toàn cục JVM**. Nếu một plugin khác cũng shade `org.sqlite` (rất phổ biến), driver được chọn cho URL `jdbc:sqlite:` phụ thuộc thứ tự load plugin — ItemGuard có thể chạy trên driver **phiên bản khác** với các đặc tính pragma/transaction khác, phá vỡ toàn bộ chứng cứ `synchronous=FULL` / `journal_mode=DELETE`.

**Hướng sửa:** relocate `org.sqlite` → `com.itemguard.libs.sqlite`, và mở connection trực tiếp qua `new org.sqlite.SQLiteDataSource(config).getConnection()` thay vì `DriverManager`, để hoàn toàn không phụ thuộc registry toàn cục.

---

## 3. MEDIUM

| # | File:line | Vấn đề | Hướng sửa |
|---|---|---|---|
| M1 | `config.yml:34-110` + `tracking/ItemIdentityEligibilityPolicy.java:19-21` | **Toàn bộ 77 dòng `force-track-materials` là code chết.** (a) `supportsIdentity` yêu cầu `maxStackSize == 1`, nên ~30 mục stackable (`NETHERITE_INGOT`, `TOTEM_OF_UNDYING`, `BEACON`, `GOLDEN_APPLE`, `NETHER_STAR`, toàn bộ music disc, `COMPARATOR`, `REPEATER`…) bị bỏ im lặng. (b) 16 mục `SHULKER_BOX_WHITE`…`SHULKER_BOX_BLACK` **không phải tên Material hợp lệ** (đúng là `WHITE_SHULKER_BOX`) → `Material.valueOf` ném và bị nuốt ở `ConfigManager.java:67-68`. (c) Khi `track-non-stackable: true` (mặc định) thì `trackNonStackable \|\| forceTrackedMaterial` đã luôn true → list không có tác dụng gì. Chủ server đọc config sẽ tin netherite ingot đang được track. | Sửa tên Material; cảnh báo WARNING cho mục không hợp lệ và mục stackable thay vì nuốt; ghi rõ trong comment rằng stackable không được hỗ trợ. |
| M2 | `ConfigManager.java:37,157-167,202-237,241,170-180` | **Config chết hàng loạt.** `general.enabled` (`config.yml:10`, "Enable/disable the entire plugin") **không có caller nào** — đặt `false` không làm gì. Tương tự: `database.pool.*` (không có pool, chỉ 1 connection), `logging.file.*` (file `itemguard.log` không bao giờ được ghi), `logging.log-to-console`/`console-level`/`log-duplicates`, `performance.batch-size`, `gui.title`/`history-rows`/`entries-per-page`/`colors.*`. | Xoá khỏi config.yml, hoặc nối dây. Thêm test contract: mọi key trong config.yml phải có ít nhất một caller. |
| M3 | `listeners/ItemListener.java:197-215` vs `:233-260` | **Nhánh chết.** `:197` `if (!hasCodeOrUuid(item)) { ...; return; }` khiến `:217` luôn true, nên `else if (tracking.shouldTrack(item))` ở `:233-260` **không bao giờ chạy**. Đây là đường tag item chưa có ID khi click **trong rương** — đã chết. Kéo theo field `inventoryClickSourcePolicy` (`:42`) và `blockContainerSlotResolver` (`:44`) của class này thành code chết. | Xoá nhánh chết, hoặc khôi phục nếu hành vi tag-trong-rương vẫn cần. |
| M4 | `services/ItemTrackingService.java:121` | `this.forceTrack = plugin.getConfigs().getForceTrackMaterials();` chụp **một lần lúc construct**. `/ig reload` không áp dụng thay đổi `force-track-materials`, nhưng `ReloadPolicy` cũng không liệt kê nó là restart-sensitive → admin reload, thấy "thành công", thực tế không đổi gì. | Đọc live, hoặc thêm vào `RestartSensitiveSettings`. |
| M5 | `services/ItemTrackingService.java:91` | `playerTrackedItems` là **`HashMap` thường** nhưng inner set lại là `Collections.synchronizedSet` — mâu thuẫn ý định. `computeIfAbsent` từ hai thread sẽ hỏng cấu trúc map (infinite loop / mất entry). Hiện mọi caller *có vẻ* trên main thread, nhưng `markPublished` (`:254-273`) chạy từ callback đã hop về main thread nên tạm an toàn — rất mong manh. | Đổi thành `ConcurrentHashMap`. |
| M6 | `plugin.yml:4` vs `pom.xml:48` | `api-version: '1.21.11'` nhưng compile target là `paper-api 1.21.4`. Bản FULL **từ chối load** trên mọi server < 1.21.11 mà không có lý do kỹ thuật. LITE dùng `1.21.4` (`lite/plugin.yml:4`) — hai bản lệch nhau. | Hạ FULL về `1.21.4` cho khớp compile target. |
| M7 | `plugin.yml:11-17` | `ig` được khai báo **vừa là alias của `itemguard`** (`:14`) **vừa là command độc lập** (`:15-17`). Bukkit sẽ đăng ký chồng; command nào thắng phụ thuộc thứ tự nội bộ, và fallback `itemguard:ig` gây nhầm lẫn. | Bỏ khối `ig:` độc lập, chỉ giữ alias. |
| M8 | `config.yml:259-265` + `tasks/CleanupTask.java:21` | Ràng buộc bạn nêu ("KHÔNG dọn/xóa dữ liệu DB tự động") **chỉ đúng với LITE** (`ConfigManager.java:246-248` ép `0`). Bản FULL mặc định `auto-cleanup.enabled: true`, `interval-hours: 24`, `keep-days: 30` → **xoá lịch sử > 30 ngày mỗi ngày**. Với sản phẩm điều tra dupe, mất 30 ngày lịch sử là mất bằng chứng. Lưu ý thêm: `ConfigManager.getCleanupIntervalHours()` **không đọc `auto-cleanup.enabled`** — chỉ đọc `interval-hours`, nên đặt `enabled: false` cũng không tắt được. | Mặc định `interval-hours: 0` cho FULL; đọc cả `enabled`; hoặc bỏ hẳn key `enabled`. |
| M9 | `pom.xml:183-188` | `<filtering>true</filtering>` áp cho **toàn bộ** `src/main/resources`. Hiện chỉ `lite/plugin.yml:2` cần (`${project.version}`), nhưng mọi chuỗi `${...}` hoặc `@...@` trong `messages_*.yml` / `config.yml` sẽ bị Maven nuốt hoặc làm fail build. | Tách một `<resource>` riêng có filtering chỉ cho `lite/plugin.yml`, phần còn lại `filtering=false`. |
| M10 | `gui/GUIListener.java:119-141,180-198` | GUI FULL **không có in-flight guard** (LITE có, `LiteCommand.java:204-207`). Admin click nhanh liên tục xếp hàng không giới hạn `getHistoryAsync(code, 100)` vào executor serial duy nhất → làm nghẽn hàng đợi, và vì H2 các `call()` main-thread đứng chờ phía sau ⇒ **một admin spam click có thể treo server**. | Áp dụng cùng cơ chế `pending` như LITE, hoặc debounce theo player. |
| M11 | `src/main/resources/messages.yml` | File này **không bao giờ được ghi ra đĩa**: `saveDefaultConfig()` chỉ lo `config.yml`, và `MessageManager.load()` chỉ gọi `saveResource` ở nhánh non-`vi` (`:84-90`). Với `language: vi` (mặc định FULL, `config.yml:16`), code đi tới `createDefaultMessages()` (`:94`) và ghi ra một **tập con** key từ `DEFAULT_MESSAGES`, không phải nội dung file đã ship. Nghĩa là mọi chỉnh sửa `messages.yml` trong repo không tới tay người dùng FULL. | Gọi `saveResource("messages.yml", false)` cho nhánh `vi`. |
| M12 | `gui/GUIListener.java:315-316` | `handleFilterInput(Player, String)` là method **rỗng**. | Xoá. |
| M13 | `api/ItemGuardAPI.java:23-25,27-33,76-78` | `getInstance()` tạo **instance mới mỗi lần gọi** (tên gây hiểu nhầm là singleton). `getTrackedItem`/`getTrackedItemByUuid` gọi xuống `db.getItem()` **blocking**; `getOnlineTrackedCount()` duyệt `Bukkit.getOnlinePlayers()` (**bắt buộc main thread**) mà không có guard — plugin bên thứ ba gọi từ async sẽ gây `ConcurrentModificationException` hoặc đọc sai. Không có hằng số version/handshake. | Singleton thật; thêm biến thể `*Async`; thêm `int getApiVersion()`; `Bukkit.isPrimaryThread()` guard. |
| M14 | `listeners/ItemListener.java:155` | Dùng `PlayerPickupItemEvent` đã deprecated thay vì `EntityPickupItemEvent`. Không bắt được pickup bởi entity khác (allay, zombie nhặt đồ) — những đường đó dẫn item ra khỏi tầm quan sát mà không có sự kiện. | Chuyển sang `EntityPickupItemEvent`. |
| M15 | `integrations/VaultHook.java` + `plugin.yml:8` | `VaultHook.hasPermission`/`groupHasPermission` **không có caller** — tích hợp chết, chỉ in một dòng log lúc khởi động (kể cả trên LITE, gây nhiễu). Ngoài ra `plugin.yml:8` chỉ có `softdepend: [WorldGuard]`, **thiếu Vault** → nếu có nối dây sau này, hook sẽ fail vì thứ tự load. | Gỡ, hoặc nối dây + thêm `Vault` vào `softdepend`. |
| M16 | `pom.xml:113-118` | Surefire `argLine` hardcode đường dẫn `.../mockito-core/5.14.2/mockito-core-5.14.2.jar` và dùng `${settings.localRepository}` (deprecated ở Maven 4). Nâng version Mockito trong `<dependencies>` mà quên sửa `argLine` → build fail khó hiểu. | Dùng property `${mockito.version}` cho cả hai, hoặc `@{argLine}` + `org.mockito:mockito-junit-jupiter` với `mockito-agent` được resolve bằng `dependency:properties`. |

---

## 4. LOW

- `ConfigManager.java:299-302` — `colorize()` dùng `msg.replace("&","§")` (naive, khác `ChatColor.translateAlternateColorCodes` dùng ở `MessageManager.java:177`). Bất kỳ ký tự `&` trong tiêu đề GUI bị biến thành `§` gây lỗi hiển thị.
- `SqliteConnectionOwner.java:223-239` — `rollback()` chỉ catch `SQLException`. Nếu `connection` là `null` thì `NullPointerException` bay ra và **thay thế** exception gốc, mất thông tin chẩn đoán.
- `data/DatabaseManager.java:336-347` — `getOnlineTrackedCount()` đếm số người chơi có *bất kỳ* item nào (break ngay slot đầu tiên không AIR), không phải số item được track. Tên hàm sai với hành vi; giá trị hiển thị trong `/igstats` là vô nghĩa.
- `integrations/DiscordWebhook.java:94-101` — `escapeJson` không escape ký tự điều khiển `< 0x20` ngoài `\n\r\t`.
- `RISK_REGISTER.md:3` — "Cập nhật: 2026-09-01", trong khi `docs/reviews/` có tài liệu tới 2026-09-09 và commit gần nhất muộn hơn. Register đang lệch pha.

---

## 5. SO SÁNH VỚI COREPROTECT

### Điểm khác biệt có được hiện thực hoá không? — **Có, phần cốt lõi là thật.**

CoreProtect `LookupOptions` không có trường định danh món; truy vấn theo `Material` + toạ độ + thời gian. ItemGuard **đã cài đặt thật** định danh cấp món:

| Thành phần | Bằng chứng |
|---|---|
| Định danh vật lý bền vững | PDC keys `itemguard:code` + `itemguard:item_uuid` — `ItemTrackingService.java:318-340` |
| Bảng canonical theo món | `tracked_items (code, item_uuid)` — `ItemSqliteRepository.java:1207-1209` |
| Lịch sử theo mã món | `getHistoryAsync(code, limit)` — truy vấn theo `code`, không theo material |
| Truy vấn người dùng | `/ig history #ID` — `LiteCommand.java:123-142` |
| Publish 2-pha chống dupe-định-danh | `AsyncTagPublicationCoordinator` + `tag_publications` state machine |

Đây là thứ CoreProtect **không có** và không dễ thêm vào (schema của họ theo block/material). Định vị thị trường là hợp lý.

### Nhưng khoảng cách thực tế lớn hơn bạn nghĩ

1. **Phạm vi phủ quá hẹp.** `ItemIdentityEligibilityPolicy.supportsIdentity` yêu cầu `maxStackSize == 1 && amount == 1`, cộng thêm `TrackingWorthinessPolicy` (chỉ gear/enchanted/custom-named). CoreProtect log **mọi thứ**. Phần lớn dupe thực tế trên server là netherite ingot, kim cương, shulker chứa đồ, XP bottle — ItemGuard mù hoàn toàn với những thứ đó, trong khi `config.yml:34-110` khiến người đọc tin ngược lại (M1). Đây là khoảng cách **marketing vs code** lớn nhất.

2. **Không có log block-break/container-break.** Đã grep: **không có `BlockBreakEvent`, `BlockPlaceEvent`, `BlockExplodeEvent`, `EntityDeathEvent`, `InventoryPickupItemEvent` listener nào** trong `src/main/java`. Đó chính là câu trả lời cho câu hỏi "item mất khi container bị đập giữa lúc ghi log": ItemGuard không hề biết rương bị đập. Item trở thành entity, đi qua đường `PaperEntitySpawnListener` → và rơi vào C3 nếu reconcile fail. Với một sản phẩm forensics, thiếu "ai đập rương này" là thiếu câu hỏi số 1 của admin.

3. **Mô hình ghi kém hơn nhiều bậc.** CoreProtect: hàng đợi batch, flush định kỳ, blob nhị phân tự đóng gói. ItemGuard: **một transaction / một sự kiện**, `synchronous=FULL` (1 fsync/commit), trên **một** connection serial. `synchronous=FULL` là lựa chọn đúng cho tính toàn vẹn, nhưng phải đi kèm batching — hiện mỗi `updateLocation` của mỗi item mỗi lần quét là một fsync riêng.

### Nên học CoreProtect những gì

| Lĩnh vực | Đề xuất cụ thể |
|---|---|
| **Hiệu năng ghi** | Gom N thao tác vào **một** transaction trước khi commit (giữ `synchronous=FULL` nhưng amortize fsync). Hiện `owner.execute()` đã đi qua một executor serial — chỉ cần thêm lớp gom batch theo tick/ngưỡng. Riêng việc này có thể giảm số fsync 50–100×. |
| **Schema** | CoreProtect chuẩn hoá tên người chơi/world/material thành bảng `*_map` với id số nguyên. ItemGuard lưu `owner_name` VARCHAR lặp lại trên mỗi row của `item_history` và `tracked_items` → DB phình nhanh và query chậm. Thêm `players(id, uuid, name)` và tham chiếu id. |
| **Retention** | CoreProtect có `/co purge t:30d` — thao tác **thủ công, có chủ đích**. Đó là mô hình đúng cho sản phẩm forensics, thay vì `CleanupTask` tự chạy nền (M8, H2). |
| **API** | Bắt chước `CoreProtectAPI.APIVersion()` + `isEnabled()` handshake và object kết quả có schema rõ. Hiện `ItemGuardAPI` (M13) không có version, không có async, trả `Optional<ItemData>` nhưng block. |
| **WAL cho Premium MySQL** | Khi sang MySQL 2 server ghi chung DB, mô hình "single-owner + FileLock" (`SqliteProcessLock`) **không còn áp dụng được**. Cần thiết kế lại từ đầu: `tag_publications` phải dùng `INSERT ... ON DUPLICATE KEY` / `SELECT ... FOR UPDATE` với server_id, và `SqliteConnectionOwner.call()` blocking (H2) sẽ trở thành network round-trip — bắt buộc phải xoá hết trước khi làm Premium. Đây là rủi ro thiết kế lớn nhất của roadmap Premium, hiện **chưa có trong RISK_REGISTER**. |

---

## 6. TEST COVERAGE — MODULE KHÔNG CÓ TEST TƯƠNG ỨNG

173 file test, phủ tốt ở tầng policy thuần (`*Policy`, `*Gate`, `*Parser`, codec, repository). Thiếu:

**Không có test nào:**
- `MessageManager` — **đáng lo nhất**, đây chính là class giữ fallback tiếng Việt (H5a) và logic chọn file ngôn ngữ (H5b).
- `ConfigManager` — không có test đơn vị; `DefaultConfigContractTest` chỉ assert 3 key trong YAML.
- `CleanupTask`
- `DiscordWebhook`
- `VaultHook`
- `ItemGuardAPI`
- `DatabaseManager` (chỉ được mock trong test khác)
- `ItemTrackingService.scanPlayerInventory` / `recordContainerInventoryObservations` / `recordLoadedContainerObservations` — **các hàm sinh ra C1 và C2**
- `ItemGuard.registerListeners/scheduleTasks`

**Chỉ có test tầng policy, không có test hành vi listener:**
- `ContainerListener` (chỉ có `HopperTransferPolicyTest` + `HopperTransferWiringContractTest`)
- `GUIListener`, `FilterChatListener`, `PlayerBrowserGUI`, `HistoryGUI`

**Vấn đề về chất lượng test:** một loạt `*WiringContractTest` (`HopperTransferWiringContractTest:16`, `GuiItemMetaSafetyWiringContractTest:16`, `UiAsyncReadWiringContractTest:35-38`, `DoubleChestPhysicalSourceWiringContractTest:58`…) **đọc file `.java` dưới dạng chuỗi** và assert trên nội dung source. Chúng bắt được regression kiểu "ai đó xoá dòng này", nhưng:
- refactor giữ nguyên hành vi làm chúng đỏ giả;
- thay đổi hành vi giữ nguyên chữ thì chúng xanh giả.

Đó là lý do C1, C2, C3, M3 sống sót qua 173 test — không có test nào chạy hai đường quan sát cùng epoch, hay mô phỏng cache readiness rỗng sau restart.

**Test nên viết đầu tiên (theo thứ tự giá trị):**
1. Epoch có 1 observation PLAYER + 1 observation CONTAINER cùng `item_uuid` cùng epoch → assert **không** sinh CONFIRMED (test này hiện sẽ FAIL, đó là C1).
2. `readyIdentities` rỗng + item tag COMPLETE trong inventory không resolve được → assert click **không** bị cancel (C2).
3. `reconcileTagPublication` với DB không có canonical row → assert có đường adopt (C3).
4. Parity: `MessageManager.DEFAULT_MESSAGES.keySet()` ⊆ `messages_en.yml` (H5a).
5. `ConfigManager.getForceTrackMaterials()` — mọi tên trong `config.yml` phải parse được (M1).

---

## 7. ĐỐI CHIẾU VỚI `docs/RISK_REGISTER.md`

### Rủi ro đã ghi nhận và audit này xác nhận
- **IG-R005 / IG-R013** (single-owner SQLite): đúng như mô tả. `SqliteProcessLock.acquire` trước JDBC (`SqliteConnectionOwner.java:67`), `synchronous=FULL` + `journal_mode=DELETE` (`:256-257`) — **xác nhận khớp 100% với mô tả sản phẩm**.
- **IG-R002** (identity fail-closed, wrong-datatype PDC): `resolveIdentityTags` kiểm type trước typed read (`ItemTrackingService.java:186-195`) — đúng.
- **IG-R004** (không có full-world scan / remove): xác nhận, sweep chỉ dùng `world.getLoadedChunks()` (`InventoryScanTask.java:99-101`) và lọc `Chunk::isLoaded` (`:115`). **Ràng buộc "chỉ quét chunk đã tải" là đúng.**
- **IG-R014** (`PublicItemCodeGenerator` + SecureRandom 6 ký tự): đúng.
- **IG-R009** (WorldGuard mặc định tắt): đúng, `ConfigManager.java:277-279` còn ép tắt ở LITE.

### Rủi ro MỚI — chưa có trong register

| ID đề xuất | Mức | Nội dung |
|---|---|---|
| **NEW-01** | Critical | C1 — cửa sổ epoch trải dài nhiều tick sinh false positive CONFIRMED cho mọi item di chuyển giữa chừng. Register nói về "observation ledger chỉ giữ latest epoch" (IG-R004) nhưng **chưa từng đặt câu hỏi một epoch kéo dài bao lâu**. |
| **NEW-02** | Critical | C2 — cache readiness in-memory + không reconcile ender chest/minecart ⇒ item ở đó bị khoá vĩnh viễn sau restart. IG-R011 có nói "unopened/custom inventory còn mở" nhưng ở góc độ *coverage*, không phải *item bị brick*. |
| **NEW-03** | Critical | C3 — mất canonical row ⇒ item không dùng được và despawn mất. Register ghi "Không claim atomic Bukkit+DB" (IG-R013) nhưng chưa ghi hệ quả: hậu quả không đối xứng — DB mất một row thì **người chơi mất cả item**. |
| **NEW-04** | High | H5 — hai cổng LITE là denylist tên file; VI nằm trong `.class` và `language: vi` tái tạo `messages.yml` tiếng Việt. Không có mục nào trong register nói về ranh giới ngôn ngữ LITE. |
| **NEW-05** | High | H1 — session UI (`pendingFilters`) không hết hạn, nuốt chat của người dùng về sau. Lớp lỗi "state UI treo" chưa có trong register. |
| **NEW-06** | High | H2/H3/H4 — main-thread blocking DB, map cooldown không giới hạn, `duplicate_findings` + alert không giới hạn. Register hoàn toàn không có mục **resource-exhaustion / performance**. |
| **NEW-07** | High | H6 — hopper fail-closed phá hệ thống phân loại của người chơi. IG-R006 ghi "hopper topology correction" như một thành tựu anti-dupe; chưa ghi giá phải trả về gameplay. |
| **NEW-08** | High | H8 — `org.sqlite` không relocate ⇒ xung đột `DriverManager` giữa các plugin có thể vô hiệu hoá mọi bằng chứng pragma durability đã thu thập ở IG-R005/R013. |
| **NEW-09** | Medium | M8 — FULL mặc định xoá lịch sử > 30 ngày, và `auto-cleanup.enabled` không được đọc. Mâu thuẫn trực tiếp với định vị sản phẩm "không dọn dữ liệu". |
| **NEW-10** | Medium | Premium MySQL đa server: `SqliteProcessLock` + `SqliteConnectionOwner.call()` blocking là **cản trở kiến trúc**, không chỉ là việc đổi driver. Cần vào register **trước khi** bắt đầu code Premium. |
| **NEW-11** | Medium | M1/M2 — config hứa tính năng không tồn tại (force-track stackable, Discord, file logging, `general.enabled`, pool, GUI colors). Rủi ro uy tín/hỗ trợ khi bán. |

---

## Thứ tự xử lý đề xuất

**Trước khi bán bất kỳ bản nào:** C2, C3 (item bị brick — nghiêm trọng hơn dupe), rồi C1 (LITE đang release kèm `anti-dupe.enabled: true`).
**Trước bản LITE kế tiếp:** H5 (đổi cổng sang allowlist + quét `.class`), H1, H4, H3.
**Trước khi khởi động Premium:** H2 và H8 — cả hai sẽ nhân lên khi chuyển sang MySQL.
**Dọn nợ:** M1, M2 (config nói dối là thứ khách trả tiền phát hiện đầu tiên).
SessionEnd hook [C:/Users/thanh/.orca/agent-hooks/claude-hook.cmd || echo {}] failed: At line:1 char:50
+ C:/Users/thanh/.orca/agent-hooks/claude-hook.cmd || echo {}
+                                                  ~~
The token '||' is not a valid statement separator in this version.
    + CategoryInfo          : ParserError: (:) [], ParentContainsErrorRecordException
    + FullyQualifiedErrorId : InvalidEndOfLine
 

