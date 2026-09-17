# Báo cáo review đối kháng — ItemGuard (read-only, 2026-09-17)

## 1. Tóm tắt

| Mức | Số lượng |
|---|---|
| CRITICAL | 4 |
| HIGH | 5 |
| MEDIUM | 6 |
| LOW | 1 |

Tất cả phát hiện đều đọc được từ source (`file:line` kèm dòng quyết định). Tôi **không** chạy build/test/server/git, nên mọi kết luận về hành vi runtime là **suy luận từ source + hợp đồng Bukkit API**, không phải đo đạc. Ba fix C3 / H1 / H5(phần fallback map) đứng vững trước các đòn tôi thử; C1, C2, H3, H4 đều thủng một phần; và lỗi nghiêm trọng nhất tôi tìm được **không nằm trong 8 fix** mà nằm ở `CraftListener`.

---

## 2. Phát hiện

### CRITICAL

#### C-1. Mọi thao tác chế tạo công cụ / vũ khí / giáp bị hủy — trang bán hàng không hề nói điều đó

`src/main/java/com/itemguard/listeners/CraftListener.java:32-42`
```java
CraftOutputPolicy.Action action = craftOutputPolicy.decide(hasIdentity, identityReady, eligible);
if (action == CraftOutputPolicy.Action.ALLOW_UNTRACKED) return;
event.setCancelled(true);
player.sendMessage("§cItemGuard tam tu choi craft output identity cho den khi output transaction duoc ho tro an toan.");
```
`src/main/java/com/itemguard/tracking/CraftOutputPolicy.java:11` → `return eligible ? Action.CANCEL_UNTAGGED_ELIGIBLE : Action.ALLOW_UNTRACKED;`
`src/main/java/com/itemguard/tracking/TrackingWorthinessPolicy.java:21-34` → `_SWORD, _PICKAXE, _AXE, _SHOVEL, _HOE, _HELMET, _CHESTPLATE, _LEGGINGS, _BOOTS`, `BOW, CROSSBOW, TRIDENT, SHIELD, ELYTRA, MACE…`
`ConfigManager.java:53-59` → `tracking.enabled` mặc định `true`, `track-non-stackable` mặc định `true`; `lite/config.yml` không set hai khóa này.

**Hậu quả:** trên server LITE vừa cài, **không ai chế tạo được cuốc gỗ, kiếm, khiên, giáp, cung**. Người chơi nhận một dòng tiếng Việt và craft không xảy ra. `ItemGuard.registerListeners():191` đăng ký `CraftListener` vô điều kiện nên LITE cũng dính.
**Vì sao là CRITICAL (không chỉ là "fail-closed cố ý"):** `docs/release/ItemGuard-LITE-1.0.0-README.txt:171-172` có công bố việc này, nhưng **trang đăng Spigot thì không**: `release/spigot-upload/description.bbcode.txt:41-43` mục "Straight about the limits" chỉ nói "never deletes or rolls back… tracks items that don't stack… skips ender chests". Người mua đọc trang đó rồi thả jar vào server đang chạy.
**Tái hiện:** cài LITE → craft một cái cuốc đá. **Mức kiểm chứng:** verified từ source (đường dẫn sự kiện + policy + default config); chưa chạy server.
**Sửa tối thiểu:** thêm đúng một dòng vào mục "Straight about the limits" của `description.bbcode.txt` (và dịch câu từ chối sang tiếng Anh) — hoặc cho phép craft khi `!hasIdentity` và để sweep gắn tag sau.

---

#### C-2. Jar "English-only" in tiếng Việt ra console và ra chat người chơi

`src/main/java/com/itemguard/ItemGuard.java:75` `getLogger().info("  ItemGuard v" + … + " da kich hoat!");`
`src/main/java/com/itemguard/ItemGuard.java:114` `getLogger().info("ItemGuard da tat!");`
`src/main/java/com/itemguard/listeners/CraftListener.java:41` (chat, xem C-1)
`src/main/java/com/itemguard/services/ItemTrackingService.java:432, 445-447, 451, 501, 514-516, 520, 605, 618-620, 624` — ví dụ `"Identity da ghi vao inventory slot nhung canonical publish chua hoan tat; giu fail-closed de reconcile: "` (mức `SEVERE`).

`ItemGuardLite` không override `onEnable()`/`registerListeners()`, nên mọi dòng trên chạy trong LITE. Cổng đóng gói `scripts/package_lite.py:55-85` chỉ kiểm tra **tên file tài nguyên**, không thấy chuỗi biên dịch trong `.class` — đúng lỗ hổng mà H5 tuyên bố đã bịt.
**Hậu quả:** admin mua/ tải bản "English only" thấy banner khởi động tiếng Việt mỗi lần start, và người chơi thấy tiếng Việt khi craft bị chặn.
**Mức kiểm chứng:** verified (chuỗi là literal trong lớp dùng chung, không qua `MessageManager`).
**Sửa tối thiểu:** dịch các literal này sang tiếng Anh (chúng không có key message tương ứng nên không cần đụng `messages_*.yml`); và thêm vào `package_lite.py` một lần quét literal tiếng Việt trong các lớp LITE chạm tới.

---

#### C-3. Item "gạch" vĩnh viễn nếu canonical publish hỏng **sau** khi tag đã ghi vào item và item sau đó di chuyển

`src/main/java/com/itemguard/tracking/AsyncTagPublicationCoordinator.java:167-183`
```java
target.write(publication);            // PDC code+uuid đã nằm trên item thật
...
store.publish(publication.publicationId(), clock.getAsLong())   // hàng canonical mới được ghi sau
    .whenComplete(... : target.publishFailed(publication, failure));
```
`src/main/java/com/itemguard/persistence/ItemSqliteRepository.java:1445-1470`
```java
SELECT publication_id, sha256 FROM tag_publications
WHERE code = ? AND item_uuid = ? AND source_key = ? AND state = 'PREPARED'
...
if (identityIsUnknownToJournal(connection, normalizedCode, receipt.itemUuid())) { return adoptIdentity(...); }
return false;
```
`ItemSqliteRepository.java:1499-1512` — `identityIsUnknownToJournal` trả `false` ngay khi `tag_publications` có `code = ? OR item_uuid = ?`, kể cả hàng `PREPARED` mồ côi.

**Kịch bản:** server tắt / DB lỗi / plugin disable đúng giữa dòng 167 và 174. Item mang tag, `tracked_items` không có hàng, `tag_publications` có hàng `PREPARED` với `source_key` = ô slot lúc đó. Nếu item **vẫn nằm nguyên ô cũ**, lần scan sau khớp `source_key` + digest và hồi phục. Nếu người chơi đã cầm nó đi / đổi slot / lấy khỏi rương thì `source_key` khác → không tìm thấy `PREPARED` → journal *biết* code nên C3 từ chối adopt → `reconcile` trả `false` **mãi mãi**.
**Hậu quả:** `ItemListener` hủy mọi click/drop/use của item đó (xem H-3), hopper từ chối, craft từ chối — không một thông báo nào. Item còn trong túi nhưng không dùng được, vĩnh viễn.
**Tái hiện:** không tái hiện được trong phiên này (cần crash server đúng một tick). **Mức kiểm chứng:** suy luận từ source; bằng chứng dứt điểm là một test repository dựng hàng `PREPARED` với `source_key` A rồi `reconcile` với `source_key` B.
**Sửa tối thiểu:** trong `reconcileTagPublication`, khi không có canonical row nhưng có `PREPARED` cùng `(code, item_uuid)` ở `source_key` khác, so digest với `sha256` và nếu khớp thì `publishTagPublication` (đổi nguồn), ngược lại `abort` hàng đó rồi để nhánh adopt xử lý.

---

#### C-4. Tag hỏng (`CORRUPT`) = item rơi dưới đất không nhặt được → despawn

`src/main/java/com/itemguard/identity/PickupIdentityPolicy.java:9` `case CORRUPT -> PickupIdentityAction.IGNORE_CORRUPT;`
`src/main/java/com/itemguard/listeners/ItemListener.java:171-173`
```java
} else if (action == PickupIdentityAction.IGNORE_CORRUPT) {
    event.setCancelled(true);
}
```
`src/main/java/com/itemguard/identity/IdentityTagResolver.java:23-26, 34-36` — chỉ cần một trong hai key PDC còn lại, hoặc `uuid` không parse được, là `CORRUPT`.

**Hậu quả:** đúng lớp lỗi mà C3 đi sửa (item không nhặt được nên despawn = mất item), nhưng C3 chỉ chữa nhánh "canonical row biến mất", không chữa nhánh "tag hỏng". `isIdentityReady` (`ItemTrackingService.java:214-216`) cũng trả `false` ngay cho `CORRUPT` **trước khi** tới đường reconcile, nên item này còn không click/drop/use được nữa.
**Mức kiểm chứng:** logic verified; **tôi không chỉ ra được nguồn sinh `CORRUPT` bên trong codebase này** (pipeline ghi cả hai key trong cùng một `setItemMeta`). Nguồn khả dĩ: NBT editor, plugin khác copy meta thiếu, dữ liệu từ bản cũ. Bằng chứng dứt điểm: một item thật bị xóa một key PDC.
**Sửa tối thiểu:** `CORRUPT` nên đi cùng nhánh `ABSENT` (cho nhặt, rồi cấp identity mới / hoặc adopt theo C3) thay vì hủy; tối thiểu phải báo cho người chơi.

---

### HIGH

#### H-1. C1 mới chỉ đẩy lỗi lùi một chu kỳ: cùng một thao tác "cầm rồi cất" lặp hai epoch là CONFIRMED, và "epoch trước" không bị chặn về thời gian

`src/main/java/com/itemguard/persistence/ItemSqliteRepository.java:1220-1228`
```sql
SELECT 1 FROM item_observations prior
WHERE prior.item_uuid = observations.item_uuid
    AND prior.code = observations.code
    AND prior.scan_epoch < observations.scan_epoch
    AND prior.epoch_complete = 1
GROUP BY prior.item_uuid, prior.code
HAVING COUNT(*) >= 2
```
Epoch "trước" cũng trải dài thực tế đúng như epoch hiện tại (`InventoryScanTask.java:90-94` quét người chơi trong một tick, `advanceSweep():111-126` rải rương qua nhiều tick). Vì vậy `COUNT(*) >= 2` ở epoch trước **được thỏa bởi chính hành vi di chuyển hợp lệ**, không phải bởi hai bản sao.

Bằng chứng nằm ngay trong test của chính fix: `src/test/java/com/itemguard/persistence/DuplicateConfirmationEpochRuleTest.java:62-91` — case "confirmed" và case "move" chỉ khác nhau ở chỗ **lặp lại**:
```java
observe(..., HolderType.PLAYER, "player-1", 3, secondEpoch, secondEpoch);
observe(..., HolderType.CONTAINER, "BLOCK:world:1:2:3", 5, secondEpoch, secondEpoch + 40_000L);
assertEquals(1, audit(repository, secondEpoch)...size());
```
Ngoài ra `prior.scan_epoch < observations.scan_epoch` **không có cận dưới**: sau restart, epoch hoàn tất cuối cùng trước khi tắt máy vẫn nằm trong bảng (chỉ bị xóa ở `deleteOlderObservationEpochs`, `ItemSqliteRepository.java:370`, tức là sau lần audit kế tiếp). Chính test dùng khoảng cách 15 phút (`firstEpoch = 5_000L`, `secondEpoch = 900_000L`) mà vẫn CONFIRMED.

**Hậu quả:** cảnh báo dupe sai nêu đích danh code item của một người chơi vô tội — người sắp xếp đồ giữa rương và túi trong hai chu kỳ liên tiếp, hoặc item đi qua dây hopper (hopper là `Container` nên bị sweep, `ItemTrackingService.java:1000`). Không mất item (`InventoryScanTask.reportFindings():137-162` chỉ log + nhắn staff), nhưng đây là cảnh báo sai trong luồng chơi bình thường — và trang bán hàng bán chính xác cái cảnh báo đó (`description.bbcode.txt:6-8`).
**Mức kiểm chứng:** verified về SQL và về vòng đời epoch; tần suất thực tế là suy luận.
**Sửa tối thiểu:** hai thay đổi nhỏ — (a) buộc `prior` là epoch hoàn tất **liền kề** (`prior.scan_epoch = (SELECT MAX(scan_epoch) FROM item_observations WHERE scan_epoch < observations.scan_epoch AND epoch_complete = 1)`) và bỏ qua nếu khoảng cách > N chu kỳ; (b) yêu cầu **giao** của tập vị trí hai epoch có ≥2 phần tử, thay vì chỉ đếm 2 ở mỗi epoch.

---

#### H-2. Hopper vĩnh viễn không đưa được item đã track vào/ra minecart chứa đồ — im lặng

`src/main/java/com/itemguard/tracking/HopperTransferPolicy.java:16-18`
```java
if ((hasIdentity || eligible) && (!sourceSupported || !destinationSupported)) {
    return Action.CANCEL;
}
```
`supports()` (`BlockContainerPhysicalSlotResolver.java:17-23, 66-78`) chỉ trả `true` khi `sideInventory.getHolder() instanceof Container` **block state**. Hopper minecart / chest minecart có holder là entity → `false`.
`ContainerListener.java:74-77` `event.setCancelled(true);` và không gửi gì cho ai.

**Hậu quả:** mọi hệ thống lưu trữ dùng minecart (và mọi inventory ảo của plugin khác) bị kẹt đúng ở các item đáng giá: kiếm/giáp/elytra nằm lại trong hopper mãi mãi. Không log, không message, không phụ thuộc readiness — đây chính là lớp lỗi C2 ("holder không phân giải được slot") còn sống ở đường hopper. Trang bán hàng chỉ nói "does not **read** storage minecarts" (`description.bbcode.txt:27-28`), không nói là **chặn**.
**Mức kiểm chứng:** verified về code; việc holder của minecart không phải `Container` block state là suy luận từ API Bukkit (bằng chứng dứt điểm: một lần chạy thật với hopper dưới chest minecart).
**Sửa tối thiểu:** khi holder không phân giải được, cho `ALLOW` nếu `hasIdentity && identityReady` (đã có identity thì di chuyển không tạo dupe), chỉ giữ `CANCEL` cho item chưa tag mà `eligible`.

---

#### H-3. Mọi lần hủy thao tác trong `ItemListener` đều không nói gì với người chơi

`src/main/java/com/itemguard/listeners/ItemListener.java:182-184` (drop), `:218-220` (click), `:271-273` (drag), `:287-289` (interact/use), `:163-165` (pickup)
```java
if (!tracking.isIdentityReady(item)) {
    event.setCancelled(true);
    return;
}
```
`src/main/java/com/itemguard/services/ItemTrackingService.java:230, 236`
```java
// Still false on the first contact, because reconciliation is asynchronous by design.
return false;
```
`IdentityReadinessCoordinator.java:24` cache chỉ 50 000 entry và mất sạch sau restart.

**Hậu quả:** sau **mỗi lần khởi động lại server**, lần chạm đầu tiên vào mỗi item đã track — cú nhấp chuột, cú thả, cú ăn/dùng — bị nuốt im lặng. Người chơi kết luận "server lag / plugin hỏng". Với C-3 hoặc C-4 thì trạng thái này là vĩnh viễn và vẫn hoàn toàn câm. Đây đúng là hạng mục mà brief gọi tên: hủy thao tác mà không phản hồi.
**Mức kiểm chứng:** verified.
**Sửa tối thiểu:** một message có throttle (ví dụ 1 lần / 5 giây / người chơi) ở nhánh `!isIdentityReady`, lấy từ `MessageManager` với một key mới thêm vào cả `DEFAULT_MESSAGES` lẫn `messages_en.yml`.

---

#### H-4. Tuyên bố của C2 "không thể mint" là sai — `unresolvedHolder` mint được hàng canonical

`src/main/java/com/itemguard/services/ItemTrackingService.java:227-229`
```java
// The identity is already COMPLETE here, so the only open question is whether the
// journal still knows it. Asking costs one lookup and cannot mint anything: the source
// key used is one no publication can carry, so a row that is missing stays missing.
```
nhưng chính hàm được gọi, `ItemSqliteRepository.java:1462-1468`, làm đúng điều ngược lại:
```java
if (identityIsUnknownToJournal(connection, normalizedCode, receipt.itemUuid())) {
    return adoptIdentity(connection, normalizedCode, receipt.itemUuid(), updatedAt);
}
```
**Hậu quả:** một cú click vào item trong ender chest / minecart mang tag mà DB chưa từng cấp sẽ **tạo hàng `tracked_items` mới** (`last_action='ADOPTED'`). Đây không phải thảm họa về dữ liệu — nó trùng ý đồ của C3 — nhưng nhận định bảo mật mà C2 dựa vào ("confirm only") không đúng, nên bất kỳ ai sau này siết C3 lại sẽ không biết rằng đường ender-chest cũng mint. Hai đòn còn lại của C2 thì **không thủng**: prefix `UNRESOLVED_HOLDER:` không đụng namespace nào (`TagPhysicalSourceKey.java:11-47`) nên không hoàn tất được `PREPARED`, và `reconcile` đi qua `owner.callAsync` nên không chặn main thread.
**Mức kiểm chứng:** verified.
**Sửa tối thiểu:** sửa comment cho đúng, hoặc truyền một cờ `allowAdoption` và tắt nó cho `unresolvedHolder` nếu chủ ý là chỉ-xác-nhận.

---

#### H-5. `general.enabled` không được đọc ở bất kỳ đâu — công tắc tắt plugin là giả

`src/main/resources/config.yml:9-10`
```yaml
  # Enable/disable the entire plugin
  enabled: true
```
`src/main/java/com/itemguard/ConfigManager.java:37-39` định nghĩa `isEnabled()`, và grep toàn bộ `src/main/java` không có lời gọi nào tới `getConfigs().isEnabled()`.

**Hậu quả:** admin gặp sự cố (ví dụ C-1: craft bị chặn) sẽ làm đúng thứ config bảo họ làm — `enabled: false`, restart — và **không có gì thay đổi**: listener, sweep, hủy click vẫn chạy nguyên. Một cam kết trong file cấu hình mà code không tôn trọng, đúng lớp lỗi mục 2 của brief.
**Mức kiểm chứng:** verified bằng grep toàn repo.
**Sửa tối thiểu:** trong `onEnable()`, nếu `!configManager.isEnabled()` thì log một dòng và `return` trước `registerListeners()` (hoặc bỏ hẳn khóa đó khỏi config.yml).

---

### MEDIUM

#### M-1. Sàn của H4 là `>=` một chu kỳ, tức vẫn đúng bằng mức vô tác dụng

`src/main/java/com/itemguard/config/AntiDupeSettings.java:44-46`
```java
long cycleMillis = Math.max(1L, config.getLong("performance.inventory-scan-interval", 600L)) * 50L;
long configured = config.getLong("anti-dupe.detection-cooldown-ms", 300_000L);
return Math.max(configured, cycleMillis);
```
`src/main/java/com/itemguard/persistence/ItemSqliteRepository.java:1191-1193, 1229-1233`
```java
long cooldownCutoff = ... createdAt - detectionCooldownMillis;
AND NOT EXISTS (SELECT 1 FROM duplicate_findings previous
    WHERE previous.item_uuid = observations.item_uuid AND previous.created_at > ?)
```
Hai lần audit cách nhau **≥** một chu kỳ (`InventoryScanTask.run():76-82` còn bỏ qua tick khi sweep đang chạy, nên luôn `≥`). Với `cooldown == cycle`: `previous.created_at = now − Δ`, `Δ ≥ cooldown` ⇒ `now − Δ > now − cooldown` là **false** ⇒ không chặn. Admin hạ `detection-cooldown-ms` xuống 5000 vẫn bị spam mỗi chu kỳ — đúng lỗi H4, nhưng bây giờ trông như đã sửa. Default 300000 (10 chu kỳ) nên đa số không dính; test `AntiDupeSettingsTest:64` cũng chỉ assert `>= cycle`.
**Sửa tối thiểu:** `Math.max(configured, cycleMillis * 2)` (hoặc `cycleMillis + 1`), và đổi assert của test thành `>`.

#### M-2. H3 chưa xử lý rương đôi — loại container phổ biến nhất

`src/main/java/com/itemguard/listeners/ContainerListener.java:126-138`
```java
if (holder instanceof org.bukkit.block.BlockState block) { return "BLOCK:" + ... }
if (holder instanceof org.bukkit.entity.Entity entity) { return "ENTITY:" + entity.getUniqueId(); }
return "OTHER:" + holder.getClass().getName() + '@' + System.identityHashCode(holder);
```
Holder của rương đôi là `DoubleChest` — không phải `BlockState`, không phải `Entity` — và CraftBukkit dựng một instance mới cho mỗi lần `getHolder()`. Key rơi vào nhánh `identityHashCode`, tức **cooldown không bao giờ khớp** cho rương đôi: mỗi lần mở (`:93-101`) và mỗi lần hopper hút (`:79-84`) đều quét lại toàn bộ rương. Comment ở `:135-137` gọi đây là "the occasional miss"; với rương đôi nó là 100%. Test `ContainerListenerCooldownKeyTest` không có case nào cho `DoubleChest`.
**Mức kiểm chứng:** verified phần code; phần "CraftBukkit tạo `DoubleChest` mới mỗi lần" là suy luận từ API (bằng chứng dứt điểm: in `cooldownEntries()` sau 10 lần mở cùng một rương đôi).
**Sửa tối thiểu:** thêm nhánh `holder instanceof DoubleChest dc` → key theo `dc.getLocation()`.

#### M-3. `language: zh` im lặng trở thành tiếng Việt, dù `messages_zh.yml` có trong jar

`src/main/java/com/itemguard/config/MessageLanguagePolicy.java:29-32`
```java
return switch (requested.trim().toLowerCase(Locale.ROOT)) {
    case ENGLISH -> ENGLISH;
    default -> VIETNAMESE;
};
```
`src/main/resources/config.yml:15` `# Language: en, vi, zh, etc. (add to messages.yml)` và `src/main/resources/messages_zh.yml` tồn tại. Admin bản FULL đặt `zh` nhận tiếng Việt, không cảnh báo. **Sửa tối thiểu:** thêm `case "zh" -> "zh";` hoặc bỏ "zh" khỏi comment.

#### M-4. Hàng ADOPTED khai `created_at` là lúc adopt, nên item cũ trông như vừa sinh ra

`src/main/java/com/itemguard/persistence/ItemSqliteRepository.java:1544-1553`
```java
INSERT INTO tracked_items (code, item_uuid, created_at, last_seen_at, last_action, detection_count)
VALUES (?, ?, ?, ?, 'ADOPTED', 0)
```
`src/main/java/com/itemguard/commands/CheckCommand.java:84` in `item-info-created` từ `data.getCreatedAt()`. Sau khi khôi phục DB từ backup, `/ig check` một thanh kiếm hai năm tuổi nói "Created: hôm nay", Owner "Unknown", History 1 dòng. Comment ở `:1534-1536` có nói về owner/material nhưng không nói về `created_at` — và `created_at` là con số staff dùng để buộc tội. **Sửa tối thiểu:** `created_at = 0`/NULL cho hàng adopted và cho `/ig check` in "unknown (adopted)" thay vì một mốc thời gian sai.

#### M-5. `plugin.yml` (FULL) hứa những thứ không tồn tại

`src/main/resources/plugin.yml:72-77` khai `itemguard.teleport` ("Teleport to a tracked item's container location") và `itemguard.restore` ("carry out the restore of a destroyed item") — không có command nào tiêu thụ hai quyền này (`MainCommand` chỉ có `check|history|search|stats|browser|reload|info|help`). `:84-86` mô tả `itemguard.bypass` là "Bypass anti-dupe checks (staff)" nhưng `canTrack` không đọc nó; nơi duy nhất dùng là `InventoryScanTask.java:157` — nó là quyền **nhận cảnh báo**. `:33, 36, 88, 91, 94` mô tả command bằng tiếng Việt (hiện trong `/help`). `:4` `api-version: '1.21.11'` cao hơn API biên dịch (`pom.xml:48` dùng paper-api 1.21.4; `lite/plugin.yml:4` dùng `'1.21.4'`), nên bản FULL nhiều khả năng bị server 1.21.4–1.21.10 từ chối nạp. **Sửa tối thiểu:** xóa hai quyền chết, sửa mô tả `bypass` thành "Receive duplicate alerts", hạ `api-version` xuống `'1.21.4'`.

#### M-6. Lời nhắc của bộ lọc chat và các lệnh FULL là tiếng Việt cứng

`src/main/java/com/itemguard/gui/FilterChatListener.java:40-42, 53, 70, 74`; `src/main/java/com/itemguard/commands/CheckCommand.java:58, 66, 88, 92, 94` (trộn với text tiếng Anh lấy từ `getRaw("action-…")` ở `:90`). Fix H1 (TTL) tự nó đúng — chỉ phần ngôn ngữ là chưa được H5 quét tới, vì H5 chỉ pin `DEFAULT_MESSAGES` ↔ `messages_en.yml`, không chạm các literal ngoài `MessageManager`.

---

### LOW

#### L-1. `DuplicateDetector` / `assessDuplicate` vẫn giữ nguyên luật trước-C1

`src/main/java/com/itemguard/dupe/DuplicateDetector.java:29-32` `epochComplete ? DuplicateStatus.CONFIRMED : SUSPECTED` — không có điều kiện epoch trước. `ItemSqliteRepository.java:449` `assessDuplicate` chỉ được gọi từ `ItemSqliteRepositoryTest:155,164`. Không có hậu quả cho người chơi hôm nay, nhưng nó là bản sao còn sống của luật đã bị bác — ai nối nó vào GUI/API sẽ tái sinh C1. **Sửa tối thiểu:** xóa, hoặc cho nó nhận thêm tham số "đã thấy ≥2 vị trí ở epoch trước".

---

## 3. Đã tấn công mà không phá được

- **C3 (ADOPT)** — đòn mạnh nhất tôi thử: "có cách nào để một bản dupe thật được adopt không?". Không. `identityIsUnknownToJournal` (`ItemSqliteRepository.java:1499-1512`) hỏi cả `tag_publications` **và** `tracked_items` bằng `code = ? OR item_uuid = ?`, và trong toàn repo **không có `DELETE FROM tracked_items` hay `DELETE FROM tag_publications` nào** — `CleanupTask.java:21` chỉ gọi `deleteOldHistory`, tương ứng `DELETE FROM item_history WHERE timestamp < ?` (`:1080`). Nên một identity đã từng được cấp ở DB này thì không bao giờ trở thành "unknown". Đòn thứ hai, race giữa kiểm tra và insert: cũng hỏng — cả hai chạy trong cùng một lambda trên một connection duy nhất do `SerialDatabaseExecutor` (`Executors.newSingleThreadExecutor`, `:19`) tuần tự hóa. Hai bản sao cùng code/uuid: bản đầu adopt, bản sau thấy hàng canonical nên chỉ confirm, và luồng phát hiện dupe (observations) vẫn bắt được chúng độc lập.
- **C2, phần va chạm key** — `TagPhysicalSourceKey.unresolvedHolder` (`:43-47`) dùng prefix `UNRESOLVED_HOLDER:`, còn ba hàm mint dùng `PLAYER_SLOT:` / `BLOCK_CONTAINER_SLOT:` / `ENTITY:` (`:11-31`). Không có đường nào làm `source_key` của một `PREPARED` mang prefix đó, nên truy vấn ở `:1445-1451` không bao giờ hoàn tất một publication đang treo. Thử tiếp: chặn main thread? Không — `db::reconcile` → `owner.callAsync` (`:133`), và `IdentityReadinessCoordinator.reconcile` (`:45-81`) trả về ngay, chỉ `markReady` khi callback quay lại main thread. Thử tiếp: spam? `inFlightReceipts` (`:52`) chống trùng trong lúc bay. Chỉ có tuyên bố "cannot mint" là sai (H-4).
- **C1, phần "cái gì mở/đóng một epoch"** — tôi cố tìm một epoch không bao giờ đóng: `startPass` với danh sách chunk rỗng vẫn đặt `passInFlight = true` (`ChunkSweepCursor.java:31`) và tick sau `position >= size` đóng ngay (`:55-57`), nên không treo. Epoch bị cắt giữa chừng (tắt server / `/ig reload` dựng lại task) để lại hàng `epoch_complete = 0`; chúng bị loại khỏi cả mệnh đề `prior` (`:1225`) lẫn mệnh đề chính (`:1211`), rồi bị `deleteOlderObservationEpochs` dọn — tức fail về phía *ít* cảnh báo, không phải cảnh báo sai. `ScanEpochGenerator` nhận sàn từ `getMaximumPersistedObservationEpoch()` (`InventoryScanTask.java:32-35`, `:414-431`) nên epoch không lùi sau restart hay sau khi đồng hồ hệ thống bị chỉnh.
- **H1 (TTL bộ lọc)** — đúng như công bố: `FILTER_INPUT_TTL_MS = 30_000` (`FilterChatListener.java:25`), `remove` trước rồi mới kiểm tra hạn (`:48-50`) nên không thể sống lại; hết hạn thì **không** cancel event, tin nhắn của người chơi vẫn được gửi (`:51-54`) — chi tiết này quan trọng và đã làm đúng; người chơi được cho biết cửa sổ bao lâu (`:41-42`). Session cũng được dọn khi quit (`PlayerListener.java:38` → `GUIListener.java:250`) và khi disable (`ItemGuard.java:90-92`). Chỉ ngôn ngữ là sai (M-6).
- **H3, phần đã sửa** — key theo tọa độ cho block container và theo UUID cho entity (`ContainerListener.java:127-133`) là ổn định thật; map được prune trước mỗi insert và có trần 4096 với chiến lược bỏ nửa cũ nhất (`:145-158`) nên không còn rò bộ nhớ. Chỉ nhánh `DoubleChest` là thủng (M-2).
- **H5, phần fallback map** — `DEFAULT_MESSAGES` (`MessageManager.java:30-72`) là tiếng Anh thật, và `MessageManagerFallbackTest:34-46` ghim hai chiều (cùng tập key **và** cùng nội dung) với `messages_en.yml`, nên không thể thêm key ở code mà quên file. `MessageLanguagePolicy.resolve` ép `ENGLISH` cho LITE bất kể config (`:22-25`), và `scripts/package_lite.py:55-85` không chỉ xóa `messages.yml`/`messages_zh.yml` mà còn allowlist toàn bộ entry non-class ở gốc jar, nên một `lang/vi.yml` tương lai sẽ làm vỡ build. Đòn duy nhất xuyên qua được là các literal biên dịch trong `.class` (C-2).
- **Ranh giới LITE / FULL** — tôi tìm đường cho LITE **tạo/xóa/di chuyển/trả lại** item và không thấy: `ItemGuardLite.registerCommands()` (`:12-18`) chỉ gắn `itemguard`, và `lite/plugin.yml` không khai `finditem`/`matdo` (script đóng gói còn assert điều đó, `package_lite.py:41-43`). `LiteCommand` cancel mọi click/drag trên GUI của nó (`:412-437`) và chỉ gọi các API đọc (`getHistory*Async`, `getStatsAsync`). Toàn bộ họ `reclaim/*` và `JumpGate/JumpLanding` không có tham chiếu nào từ code chạy — chỉ từ test. `getAntiDupeAction()` cứng "NOTIFY" cho LITE (`ConfigManager.java:107`), `getCleanupIntervalHours()` cứng 0 (`:250`), WorldGuard/Discord bị ép tắt (`:281, 286`). Điểm duy nhất LITE *ghi* vào item là ghi tag identity (`ItemTrackingService.java:494` `inventory.setItem(slot, restoreTagged(publication))`) — đúng chức năng đã công bố, không tạo/xóa item.

---

## 4. Những gì tôi KHÔNG kiểm được trong phiên này

1. **Mọi hành vi runtime.** Không chạy build, test, hay server (ràng buộc read-only). Cụ thể chưa đo được: `DoubleChest.getHolder()` có thực sự trả instance mới mỗi lần không (M-2); holder của chest/hopper minecart có thực sự trượt `resolveSide` không (H-2); cửa sổ crash giữa `write` và `publish` (C-3).
2. **Jar đã publish.** `release/spigot-upload/ItemGuard-LITE-1.0.0.jar` và `release/upload/…` là binary; tôi không giải nén/đối chiếu SHA-256 với `description.bbcode.txt:155`, nên không khẳng định được jar trên trang khớp với source này. Muốn kết luận: `compare_jar_entries.py` + `verify_lite_artifact.py`, đều cần chạy.
3. **Nguồn sinh `CORRUPT` thật** (C-4). Tôi chứng minh được hậu quả, không chứng minh được nguyên nhân trong codebase này.
4. **Tần suất thực của H-1.** Tôi chứng minh luật SQL cho phép false positive; tần suất phụ thuộc thói quen người chơi và số chunk tải — cần một lần chạy có kịch bản (cầm → cất → lấy ra → cất, hai chu kỳ liên tiếp, `anti-dupe.enabled: true`).
5. **Các mảng tôi chỉ lướt qua** vì nằm ngoài 8 fix và ngoài ranh giới LITE: `catalog/*` (~30 file), `custody/*`, `ItemLossListener`/`LossJournal`, `ItemGuardAPI`, `HistoryGUI`/`PlayerBrowserGUI` (FULL-only). Trong số này `ItemGuardAPI` đáng được review riêng vì nó là bề mặt public cho plugin khác.
