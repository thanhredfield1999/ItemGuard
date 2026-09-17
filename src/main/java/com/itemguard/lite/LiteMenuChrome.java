package com.itemguard.lite;

import java.util.List;

/**
 * Labels and icons for the LITE browser chrome.
 *
 * <p>Every control states what it is, what clicking it does, and where the data's limits are. Text
 * is written in normal casing: ItemGuard deliberately does not use small caps, unlike other plugins
 * on this network.
 */
final class LiteMenuChrome {

    static final String BORDER_MATERIAL = "BLUE_STAINED_GLASS_PANE";
    static final String CONTENT_BACKGROUND_MATERIAL = "GRAY_STAINED_GLASS_PANE";

    private LiteMenuChrome() {
    }

    /** One rendered control: an icon plus the text that explains it. */
    record Element(String material, String title, List<String> lore) {
    }

    static Element overviewGuide(boolean vietnamese, int shownItems, int pageSize) {
        return vietnamese
            ? new Element("BOOK", "Lịch sử vật phẩm đã ghi", List.of(
                "Đang hiển thị " + shownItems + " vật phẩm (tối đa " + pageSize + " mỗi trang).",
                "Nhấn một vật phẩm để xem dòng thời gian của nó.",
                "Đây là lịch sử đã ghi, không phải vị trí hiện tại.",
                "Thiếu lịch sử không có nghĩa là vật phẩm không tồn tại."))
            : new Element("BOOK", "Recorded item history", List.of(
                "Showing " + shownItems + " items (up to " + pageSize + " per page).",
                "Click an item to open its timeline.",
                "This is recorded history, not live custody.",
                "Missing history is not proof that an item does not exist."));
    }

    static Element timelineGuide(boolean vietnamese, String code, int events) {
        return vietnamese
            ? new Element("BOOK", "Dòng thời gian: " + code, List.of(
                events + " sự kiện trong cửa sổ này, mới nhất trước.",
                "Nhấn Quay lại ở góc dưới bên trái để về danh sách.",
                "Dòng cũ nhất ở đây không phải nguồn gốc của vật phẩm."))
            : new Element("BOOK", "Timeline: " + code, List.of(
                events + " events in this window, newest first.",
                "Click Back at the bottom left to return to the list.",
                "The oldest row here is not the item's origin."));
    }

    static Element pageIndicator(boolean vietnamese, int page, int totalPages) {
        String title = vietnamese
            ? "Trang " + page + " / " + totalPages
            : "Page " + page + " of " + totalPages;
        return new Element("PAPER", title, vietnamese
            ? List.of("Dùng mũi tên hai bên để đổi trang.")
            : List.of("Use the arrows on either side to change page."));
    }

    static Element previous(boolean vietnamese, boolean enabled) {
        if (!enabled) {
            return new Element("GRAY_DYE",
                vietnamese ? "Đang ở trang đầu" : "Already on the first page",
                vietnamese
                    ? List.of("Không có trang trước.")
                    : List.of("No previous page to show."));
        }
        return new Element("ARROW",
            vietnamese ? "Trang trước" : "Previous page",
            vietnamese
                ? List.of("Nhấn để lùi một trang.")
                : List.of("Click to go back one page."));
    }

    static Element next(boolean vietnamese, boolean enabled) {
        if (!enabled) {
            return new Element("GRAY_DYE",
                vietnamese ? "Đang ở trang cuối" : "Already on the last page",
                vietnamese
                    ? List.of("Không có trang tiếp theo.")
                    : List.of("No further page to show."));
        }
        return new Element("ARROW",
            vietnamese ? "Trang sau" : "Next page",
            vietnamese
                ? List.of("Nhấn để sang trang tiếp theo.")
                : List.of("Click to go forward one page."));
    }


    static Element back(boolean vietnamese) {
        return new Element("ARROW",
            vietnamese ? "Quay lại" : "Back",
            vietnamese
                ? List.of("Nhấn để về danh sách vật phẩm.")
                : List.of("Click to return to the item list."));
    }

    static Element emptyState(boolean vietnamese) {
        return vietnamese
            ? new Element("LIGHT_GRAY_DYE", "Chưa có lịch sử nào được ghi", List.of(
                "Cầm một vật phẩm và gõ /ig check để xem mã của nó.",
                "Vật phẩm được theo dõi khi máy chủ ghi nhận nó.",
                "Không có lịch sử không chứng minh vật phẩm không tồn tại."))
            : new Element("LIGHT_GRAY_DYE", "No recorded history yet", List.of(
                "Hold an item and run /ig check to see its ID.",
                "Items are tracked once the server records them.",
                "No history is not proof that an item does not exist."));
    }

    static String itemRowHint(boolean vietnamese) {
        return vietnamese ? "Nhấn để xem dòng thời gian" : "Click to open the timeline";
    }

    static String historyDisclaimer(boolean vietnamese) {
        return vietnamese ? "Lịch sử đã ghi, không phải vị trí hiện tại" : "Recorded history, not live custody";
    }
}
