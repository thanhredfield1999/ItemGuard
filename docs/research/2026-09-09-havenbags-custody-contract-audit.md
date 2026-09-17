# HavenBags — bounded observation, không absence/ownership

Trạng thái: OBSERVED pinned upstream source, chưa installed-artifact/runtime verified. Không code adapter/schema; không sửa BastionForge. Hai audit agents timeout; các kết luận dưới do parent đọc lại source trực tiếp, không dùng self-report.

## Nguồn chính xác

Pin nghiên cứu: `ed33cc849191ca0ece6bac6ce53081bdb4c2911d` trong repo https://github.com/Valorless/HavenBags . Base nguồn: https://github.com/Valorless/HavenBags/blob/ed33cc849191ca0ece6bac6ce53081bdb4c2911d/ . Paths/lines dưới theo raw files đọc 2026-09-09, không theo JAR đang cài.

- `src/main/java/valorless/havenbags/api/HavenBagsAPI.java`: isBag/getBagUUID/getBagData/getBag/getPlayerBags/getBagsOnPlayer/getOpenBagsUUIDs/bagOpenBy. Các API tạo/mở/đóng/sửa túi không phải read-only adapter.
- `src/main/java/valorless/havenbags/Database.java:323–366`: getBag -> getbag -> loaded `data.get(UUID)`; không cho phép suy storage durable trống khi null. Sai UUID có thể log và trả null.
- `Database.java:379–402`: updateBag chỉ setContent/setChanged và thêm changedBags. Lỗi bị catch/log. Không có durable-success receipt.
- `Database.java:841–845,1033–1036`: getBagsData(owner) và getOpenBags() full stream `data.values()`, không owner-indexed cursor/hard bound. Không gọi trước rồi cắt list để giả bounded enumeration.
- `Database.java:998–1010`: markBagClosed clear viewer/gui; MYSQLPLUS save bằng async task, không có future commit receipt trả cho caller.
- `src/main/java/valorless/havenbags/datamodels/Bag.java:118–119,146–147,172–173,327–342`: configured owner, size, mutable content list, GUI, viewer là các field khác nhau. getContent trả alias mutable. Không đem Bukkit ItemStack/list live sang worker thread.
- `src/main/java/valorless/havenbags/gui/BagGUI.java:452–476`: updateBag, markBagClosed, unregister rồi mới emit BagCloseEvent. Event là observation boundary sau cập nhật cache, KHÔNG fsync/transaction outcome. Viewer trong Bag có thể đã null, actor phải lấy event player và vẫn không là owner/custodian tự động.
- `src/main/java/valorless/havenbags/events/BagCloseEvent.java`: exposes Inventory/Player/bagItem/Bag/forced; không có transfer from/to hoặc committed version. Không suy close là item đã chuyển sang người đóng.
- `src/main/java/valorless/havenbags/database/EtherealBags.java:426–428`: key ownerUUID + '-' + bagId -> mutable cache list hoặc null; không phải physical bag ItemStack. Empty/null không complete server absence.
- `src/main/resources/plugin.yml`: Bukkit name HavenBags, depend ValorlessUtils; version `version-number` là build placeholder. Javadocs label 1.43.0 KHÔNG đủ pin exact binary; không dùng main-SNAPSHOT floating dependency cho support gate.

## Đối chiếu lịch sử

Session 20260902_222046_23004b và `E:/AI.WORK/ForceItem/docs/ITEM_RECOVERY_DESIGN.md` đã xác định tên HavenBags và research API 1.43.0. Không hỏi lại tên plugin. Tuy nhiên đoạn 1.43.0 trong lịch sử xuất phát Javadocs/research, chưa có installed JAR metadata/hash trong evidence ItemGuard. Cần đường dẫn server mục tiêu để inventory metadata read-only, không đoán từ fixture cũ hay từ server khác. BastionForge API bridge đang DEFERRED tới khi ItemGuard xong phần liên quan; không tự code sang repo kia.

## Thiết kế đề xuất để xin duyệt C2/C3

1. Observation-first: chỉ item/slot đang được server hoặc public event cho quan sát; capture scalar immutable tại server thread, persist async. Mỗi record gắn exact ItemGuard UUID/code, provider version/adapter revision, observation time và recorded time, evidence ID, coverage kind.
2. Bag physical identity, storage bag key và child identity tách riêng. Owner configured của túi, actor mở/đóng và holder quan sát tách riêng. Unbound không là unknown nếu API xác định ownerless. Không gán source của child thành HavenBags.
3. Plugin descriptor là nguồn định nghĩa ở thời điểm quan sát, không đủ chứng minh ai đã tạo/cấp hay last mutation. Không suy từ lore/name/PDC namespace. Unknown/conflicting/unavailable là trạng thái lưu được, không sửa canonical PDC.
4. Chuỗi custody chỉ có cạnh từ/to khi evidence chuyển giao có correlation/commit semantics. Hai observations rời rạc chỉ là hai mốc và gap; same UUID ở nhiều chỗ không tự chọn một holder. Latest recorded có timestamp; không label current verified qua dữ liệu cũ.
5. Retention xóa event phải giữ watermark/gap; migration item cũ không backfill creator/custody từ owner/action. Exact absence/reclaim là capability độc lập vẫn CLOSED.
6. Query/index/migration phải theo contract durable mới: single-owner SQLite, transactional additive version, rollback/future/corrupt/unknown-field tests; projection rebuildable nhưng event facts không silent overwrite. Chưa chốt tên SQL fields hay schema version.
7. Bounded budget định nghĩa trước adapter: giới hạn slots per observation, queued batches, per-tick capture, cancellation, runtime heartbeat; không all-bags/full-world scan/force load. Túi lồng nhau cần visited bag IDs và depth bound, partial vượt bound không COMPLETE. Các giá trị cụ thể cần workload trước, không tăng budget để xanh.

## Blocker thực

- Chưa duyệt durable provenance/custody/event projection contract mới và retention/backfill semantics; đây là architectural (không chỉ nhãn GUI).
- Chưa có installed target artifact/ValorlessUtils version + server path để pin compile/runtime support matrix. Source pin trên chỉ là reference.
- Upstream cache/event API được đọc không cung cấp toàn-storage bounded snapshot/absence hoặc transaction-confirmed transfer receipt. Có thể triển khai partial observation, không thể hứa đầy đủ chuyển tay/all items/reclaim với API này.
- Cần user chọn partial observation với nhãn gaps trước hay yêu cầu upstream contract/bridge cho completeness trước. Không bỏ yêu cầu full roadmap nếu chọn chia phase.
