package com.itemguard.catalog;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** All methods except future completion execute through the main-thread port. */
public final class CatalogController {
    public interface Data {
        CompletableFuture<CatalogPage> find(CatalogQuery query);
        CompletableFuture<List<CatalogEvent>> history(String code, String itemUuid);
        CompletableFuture<CatalogObservations> observations(String code, String itemUuid);
    }
    public interface Port {
        boolean allowed(boolean history);
        void main(Runnable action);
        void show(CatalogScreen screen);
        void close();
        void prompt(boolean owner, java.util.function.Consumer<String> completion);
        void message(String message);
    }
    private final Data data;
    private final Port port;
    private final CatalogSession session = new CatalogSession();
    private CatalogPage page = new CatalogPage(List.of(), false);
    private CatalogRow selected;
    private List<CatalogEvent> events = List.of();
    private CatalogObservations observations = new CatalogObservations(List.of(), false);
    private final Map<Integer, CatalogScreen.Icon> icons = new HashMap<>();
    private String text = "", owner = "", cursor = "";
    private CatalogCategory category = CatalogCategory.ALL;
    private final java.util.ArrayList<String> previous = new java.util.ArrayList<>();
    private int historyPage;
    private int observationPage;
    private long viewGeneration;
    private boolean hasPage, historyFailure;
    private Runnable retry = this::open;
    private record Request(CatalogQuery query, List<String> previous) {
        Request { previous = List.copyOf(previous); }
    }

    public CatalogController(Data data, Port port) { this.data = data; this.port = port; }
    public void open() {
        load(new Request(new CatalogQuery(text, owner, category, cursor), previous));
    }
    private void load(Request request) {
        if (!permit(false)) return;
        historyFailure = false; retry = () -> load(request);
        base(); icon(22, "CLOCK", "Đang tải…", List.of(), null); show("Kho tra cứu");
        if (!session.accepts(viewGeneration) || !permit(false)) return;
        long generation = viewGeneration;
        try {
            data.find(request.query()).whenComplete((result, failure) ->
                port.main(() -> {
                    if (!session.accepts(generation) || !permit(false)) return;
                    if (failure != null) { error(); return; }
                    text = request.query().text(); owner = request.query().ownerName();
                    category = request.query().category(); cursor = request.query().afterCode();
                    previous.clear(); previous.addAll(request.previous());
                    page = result; hasPage = true; catalog();
                }));
        } catch (RuntimeException failure) { error(); }
    }
    public void close() { session.close(); }

    private boolean permit(boolean history) {
        if (port.allowed(history)) return true;
        close(); port.close(); return false;
    }
    private void base() {
        viewGeneration = session.begin(); icons.clear();
        icon(53, "BARRIER", "Đóng", List.of(), () -> { close(); port.close(); });
        icon(0, "BOOK", "Chỉ dữ liệu đã ghi nhận", List.of(
            "Chưa phải toàn bộ item trên hệ thống", "HavenBags/kho ngoài: chưa hỗ trợ", "Chỉ xem — không lấy hoặc xóa đồ"), null);
    }
    private void icon(int slot, String material, String title, List<String> lore, Runnable action) {
        icons.put(slot, new CatalogScreen.Icon(material, title, lore, action));
    }
    private void show(String title) { show(title, false); }
    private void show(String title, boolean historyRequired) {
        if (!permit(historyRequired)) return;
        long generation = viewGeneration;
        var guarded = new HashMap<Integer, CatalogScreen.Icon>();
        icons.forEach((slot, icon) -> guarded.put(slot, new CatalogScreen.Icon(icon.material(), icon.title(), icon.lore(),
            icon.action() == null ? null : () -> {
                if (session.accepts(generation) && permit(historyRequired)) icon.action().run();
            })));
        port.show(new CatalogScreen(title, guarded));
    }
    private void catalog() {
        if (!hasPage) { error(); return; }
        base();
        filters();
        for (int i=0; i<page.items().size(); i++) {
            var row = page.items().get(i);
            icon(i+9, row.material(), text(row.name()), List.of(row.code(),
                "Người liên quan (bản ghi): " + text(row.ownerName()),
                "Nguồn plugin: chưa xác định", "Bấm xem hồ sơ"), () -> { selected = row; profile(); });
        }
        if (page.items().isEmpty()) icon(22, "PAPER", "Không có bản ghi phù hợp", List.of("Không chứng minh item không tồn tại"), null);
        if (!previous.isEmpty()) icon(48, "ARROW", "Trang trước", List.of(), () -> {
            var stack = new java.util.ArrayList<>(previous);
            String target = stack.removeLast();
            load(new Request(new CatalogQuery(text, owner, category, target), stack));
        });
        if (page.hasMore() && !page.items().isEmpty()) {
            if (previous.size() < 127) icon(50, "ARROW", "Trang sau", List.of(), () -> {
                var stack = new java.util.ArrayList<>(previous); stack.add(cursor);
                load(new Request(new CatalogQuery(text, owner, category, page.items().getLast().code()), stack));
            });
            else icon(50, "BARRIER", "Đã tới giới hạn 128 trang", List.of("Thu hẹp bộ lọc để tra tiếp"), null);
        }
        icon(49, "PAPER", "Trang " + (previous.size()+1), List.of("Xếp theo mã; không phải snapshot DB", "Dữ liệu mới có thể cần làm mới"), null);
        show("Kho tra cứu · " + (previous.size()+1));
    }
    private void filters() {
        icon(2, "NAME_TAG", "Tìm tên / mã / material", List.of(text.isEmpty() ? "Chưa lọc" : text), () -> input(false));
        icon(3, "PLAYER_HEAD", "Người liên quan", List.of(owner.isEmpty() ? "Chưa lọc" : owner, "Theo tên trong bản ghi, không phải custody"), () -> input(true));
        icon(4, "HOPPER", "Loại đồ: " + categoryName(category), List.of("Suy từ material; không phải loại custom"), this::categories);
        icon(6, "SUNFLOWER", "Làm mới từ đầu", List.of(), () -> first(text, owner, category));
        icon(7, "MILK_BUCKET", "Xóa bộ lọc", List.of(), () -> first("", "", CatalogCategory.ALL));
    }
    private void first(String search, String related, CatalogCategory kind) {
        load(new Request(new CatalogQuery(search, related, kind, ""), List.of()));
    }
    private void input(boolean byOwner) {
        long generation = session.begin();
        port.prompt(byOwner, value -> {
            if (!session.accepts(generation) || !permit(false)) return;
            if (value.equalsIgnoreCase("huy") || value.equalsIgnoreCase("hủy")) { catalog(); return; }
            String input = value.equals("*") ? "" : value;
            try {
                first(byOwner ? text : input, byOwner ? input : owner, category);
            } catch (IllegalArgumentException invalid) {
                port.message("Nội dung không hợp lệ: tên tối đa 16, tìm kiếm tối đa 64 ký tự."); catalog();
            }
        });
    }
    private void categories() {
        base();
        String[] materials={"CHEST","DIAMOND_SWORD","DIAMOND_CHESTPLATE","DIAMOND_PICKAXE","BOW","PAPER"};
        for (var value : CatalogCategory.values()) {
            icon(18+value.ordinal(), materials[value.ordinal()], categoryName(value), List.of("Phân loại theo material"), () -> first(text, owner, value));
        }
        icon(45, "ARROW", "Về kho", List.of(), this::catalog); show("Chọn loại đồ");
    }
    private static String categoryName(CatalogCategory value) {
        return switch(value) { case ALL -> "Tất cả"; case SWORD -> "Kiếm"; case ARMOR -> "Giáp";
            case TOOLS -> "Công cụ"; case RANGED -> "Tầm xa"; case OTHER -> "Khác / chưa phân loại"; };
    }
    private void profile() {
        base();
        icon(4, selected.material(), text(selected.name()), List.of(selected.code(), "UUID: " + text(selected.itemUuid()),
            "Người liên quan (bản ghi): " + text(selected.ownerName()), "Nguồn plugin: chưa xác định"), null);
        icon(20, "CLOCK", "Lịch sử", List.of("Tối đa 100 sự kiện còn lưu", "Không đầy đủ: giới hạn + retention"), this::loadHistory);
        icon(22, "PLAYER_HEAD", "Qua tay ai — chưa hỗ trợ", List.of("Actor lịch sử không phải chuỗi chuyển tay"), null);
        icon(24, "CHEST", "Nơi đã quan sát", List.of("Bản ghi tổng hợp: " + text(selected.location()), "Thời điểm bản ghi: " + time(selected.lastSeenAt()),
            "Bấm xem các quan sát còn lưu", "Không xác minh tồn tại hiện tại"), this::loadObservations);
        icon(45, "ARROW", "Về kho", List.of(), this::catalog);
        show("Hồ sơ · " + selected.code());
    }
    private void loadObservations() {
        if (!permit(true)) return;
        historyFailure = true; retry = this::loadObservations;
        base(); icon(22, "CLOCK", "Đang tải quan sát…", List.of(), null); show("Quan sát", true);
        if (!session.accepts(viewGeneration) || !permit(true)) return;
        long generation = viewGeneration;
        try {
            data.observations(selected.code(), selected.itemUuid()).whenComplete((result, failure) -> port.main(() -> {
                if (!session.accepts(generation) || !permit(true)) return;
                if (failure != null) { error(); return; }
                observations = result; observationPage=0; observations();
            }));
        } catch (RuntimeException failure) { error(); }
    }
    private void observations() {
        base();
        int start = observationPage*36;
        for (int i=0; i<Math.min(36, observations.rows().size()-start); i++) {
            var row = observations.rows().get(start+i);
            icon(i+9, "PAPER", holderName(row.holderType()), List.of(time(row.observedAt()),
                text(row.holderId()), slotName(row.slot()), "Đợt quét: " + row.epoch(), epochStatus(row)), () -> observationDetail(row));
        }
        icon(4, "BOOK", "Quan sát còn lưu — không phải chuyển tay", List.of(
            "Không xác minh người giữ hiện tại", "Khác đợt ≠ đồng thời; không kết luận dupe",
            "Cùng đợt vẫn có thể khác thời điểm", "Item di chuyển có thể hiện nhiều nơi",
            "Đợt cũ bị xóa khi đợt mới hoàn tất",
            "Chỉ túi người online + rương đang mở", "Đợt hoàn tất không phải toàn hệ thống",
            observations.hasMore() ? "Đã cắt: chỉ 100 quan sát đầu" : "Chỉ các quan sát còn lưu", "HavenBags/kho ngoài: chưa hỗ trợ"), null);
        icon(45, "ARROW", "Về hồ sơ", List.of(), this::profile);
        if (observationPage>0) icon(48,"ARROW","Trang trước",List.of(),()->{observationPage--;observations();});
        if (start+36<observations.rows().size()) icon(50,"ARROW","Trang sau",List.of(),()->{observationPage++;observations();});
        if (observations.rows().isEmpty()) icon(22,"PAPER","Chưa có quan sát còn lưu",List.of("Không chứng minh item không tồn tại"),null);
        show("Quan sát · " + selected.code() + " · " + (observationPage+1), true);
    }
    private void observationDetail(CatalogObservation row) {
        base();
        icon(22,"PAPER",holderName(row.holderType()),List.of("Quan sát #" + row.id(), time(row.observedAt()),
            "Khóa vị trí: " + text(row.holderId()), slotName(row.slot()), "Đợt quét: " + row.epoch(), epochStatus(row),
            "Đây là nơi thấy tại thời điểm ghi", "Không chứng minh chuyển tay hoặc sở hữu"),null);
        icon(45,"ARROW","Về quan sát",List.of(),this::observations);
        show("Chi tiết quan sát · " + selected.code(), true);
    }
    private static String epochStatus(CatalogObservation row) {
        return row.epochComplete() ? "Đợt quét đã hoàn tất (phạm vi hẹp)" : "Đợt quét chưa hoàn tất";
    }
    private static String slotName(int slot) {
        return slot < 0 ? "Ô: chưa xác định" : "Ô: " + slot;
    }
    private static String holderName(String type) {
        if (type == null) return "Loại vị trí chưa xác định";
        return switch(type) { case "PLAYER" -> "Túi người chơi (đã quan sát)";
            case "CONTAINER" -> "Rương / khối chứa (đã quan sát)";
            default -> "Loại vị trí chưa hỗ trợ: " + text(type); };
    }
    private void loadHistory() {
        if (!permit(true)) return;
        historyFailure = true; retry = this::loadHistory;
        base(); icon(22, "CLOCK", "Đang tải lịch sử…", List.of(), null); show("Lịch sử");
        if (!session.accepts(viewGeneration) || !permit(true)) return;
        long generation = viewGeneration;
        try {
            data.history(selected.code(), selected.itemUuid()).whenComplete((result, failure) -> port.main(() -> {
                if (!session.accepts(generation) || !permit(true)) return;
                if (failure != null) { error(); return; }
                events = List.copyOf(result); historyPage=0; history();
            }));
        } catch (RuntimeException failure) { error(); }
    }
    private void history() {
        base();
        int start = historyPage*36;
        for (int i=0; i<Math.min(36, events.size()-start); i++) {
            var event = events.get(start+i);
            icon(i+9, "PAPER", text(event.action()), List.of(time(event.timestamp()),
                "Người thực hiện: " + text(event.actor()), text(event.location())), () -> detail(event));
        }
        icon(4, "BOOK", "Lịch sử không đầy đủ", List.of("Tối đa 100 sự kiện còn lưu; retention", "Người thực hiện không đồng nghĩa người giữ"), null);
        icon(45, "ARROW", "Về hồ sơ", List.of(), this::profile);
        if (historyPage>0) icon(48,"ARROW","Trang trước",List.of(),()->{historyPage--;history();});
        if (start+36<events.size()) icon(50,"ARROW","Trang sau",List.of(),()->{historyPage++;history();});
        if (events.isEmpty()) icon(22,"PAPER","Chưa có sự kiện còn lưu",List.of("Không đồng nghĩa chưa từng xảy ra"),null);
        show("Lịch sử · " + selected.code() + " · " + (historyPage+1), true);
    }
    private void detail(CatalogEvent event) {
        base();
        icon(22, "PAPER", text(event.action()), List.of("Sự kiện #" + event.id(), time(event.timestamp()),
            "Người thực hiện: " + text(event.actor()), "UUID: " + text(event.actorUuid()),
            text(event.location()), text(event.detail()), "Không phải bằng chứng chuyển quyền sở hữu"), null);
        icon(45, "ARROW", "Về lịch sử", List.of(), this::history);
        show("Sự kiện · " + selected.code(), true);
    }
    private void error() {
        base(); icon(22, "BARRIER", "Không thể tải dữ liệu", List.of("DB đang bận, lỗi hoặc vượt giới hạn", "Đợi ít nhất 1 giây rồi thử lại", "Không coi là kết quả rỗng"), null);
        icon(49, "CLOCK", "Thử lại", List.of("Giữ nguyên yêu cầu vừa lỗi"), retry);
        if (historyFailure && selected != null) icon(45,"ARROW","Về hồ sơ",List.of(),this::profile);
        else if (hasPage) icon(45,"ARROW","Về trang trước đó",List.of("Giữ trang và bộ lọc đã tải thành công"),this::catalog);
        if (!historyFailure) filters();
        show("ItemGuard · Lỗi truy vấn");
    }
    private static String text(String value) { return value == null || value.isBlank() ? "Chưa ghi nhận" : value; }
    private static String time(long value) {
        return value <= 0 ? "Chưa ghi thời gian" : java.time.format.DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm:ss XXX")
            .withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.ofEpochMilli(value));
    }
}
