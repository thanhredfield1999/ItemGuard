package com.itemguard.lite;

import com.itemguard.data.ItemHistory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Read-only presentation helpers for LITE history. Grouping is applied to the already bounded recent
 * window returned by the repository, so every count is window-scoped and never a lifetime total.
 */
final class LiteHistoryView {

    private static final DateTimeFormatter TIMESTAMP =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** One item code inside the loaded window: its newest event and how many events the window holds. */
    record Entry(ItemHistory latest, int eventsInWindow) {
        String code() {
            return latest.getCode();
        }
    }

    private LiteHistoryView() {}

    /** Rows arrive newest-first; the returned order keeps the most recently active item first. */
    static List<Entry> overview(List<ItemHistory> rows) {
        Map<String, ItemHistory> newest = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ItemHistory row : rows) {
            String code = row.getCode();
            if (code == null || code.isBlank()) continue;
            newest.putIfAbsent(code, row);
            counts.merge(code, 1, Integer::sum);
        }
        List<Entry> entries = new ArrayList<>(newest.size());
        newest.forEach((code, latest) -> entries.add(new Entry(latest, counts.get(code))));
        return List.copyOf(entries);
    }

    static String actionLabel(String action, boolean vietnamese) {
        String raw = action == null ? "" : action.toUpperCase(Locale.ROOT);
        return switch (raw) {
            case "PICKUP" -> vietnamese ? "Nhặt lên" : "Picked up";
            case "DROP" -> vietnamese ? "Vứt ra" : "Dropped";
            case "SPAWN" -> vietnamese ? "Bắt đầu theo dõi" : "First tracked";
            case "USE" -> vietnamese ? "Sử dụng" : "Used";
            case "DEATH" -> vietnamese ? "Chết khi đang mang" : "Died carrying";
            case "INVENTORY_MOVE" -> vietnamese ? "Chuyển trong túi đồ" : "Moved in inventory";
            case "INVENTORY_DRAG" -> vietnamese ? "Kéo trong túi đồ" : "Dragged in inventory";
            case "CONTAINER_TAKE" -> vietnamese ? "Lấy ra từ rương" : "Taken from a chest";
            case "CONTAINER_PUT" -> vietnamese ? "Bỏ vào rương" : "Put into a chest";
            case "SHULKER_TAKE" -> vietnamese ? "Lấy ra từ shulker đặt xuống" : "Taken from a placed shulker";
            case "SHULKER_PUT" -> vietnamese ? "Bỏ vào shulker đặt xuống" : "Put into a placed shulker";
            case "ENDERCHEST_TAKE" -> vietnamese ? "Lấy ra từ rương ẩn" : "Taken from an ender chest";
            case "ENDERCHEST_PUT" -> vietnamese ? "Bỏ vào rương ẩn" : "Put into an ender chest";
            case "CARRIED_CONTAINER_TAKE" -> vietnamese
                ? "Lấy ra từ hộp mang theo" : "Taken from a carried box";
            case "CARRIED_CONTAINER_PUT" -> vietnamese
                ? "Bỏ vào hộp mang theo" : "Put into a carried box";
            default -> raw.startsWith("CONTAINER_")
                ? (vietnamese ? "Trong rương: " : "In container: ")
                    + humanize(raw.substring("CONTAINER_".length()))
                : humanize(raw);
        };
    }

    static String time(long timestamp, boolean vietnamese) {
        String absolute = TIMESTAMP.format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()));
        return absolute + " (" + relative(timestamp, vietnamese) + ")";
    }

    static String overviewLine(Entry entry, boolean vietnamese) {
        return "§6#" + entry.code() + " §f| " + (vietnamese ? "gần nhất: " : "latest: ")
            + actionLabel(entry.latest().getAction(), vietnamese)
            + " §7| " + time(entry.latest().getTimestamp(), vietnamese);
    }

    /**
     * Owner-scoped queries are scoped by the CURRENT holder, so a returned window can still contain
     * events recorded while another player held the identity. Without staff permission the acting
     * player's name and recorded location are withheld; the event itself stays visible.
     */
    static boolean disclosesActor(ItemHistory row, java.util.UUID viewer, boolean staffView) {
        if (staffView) return true;
        java.util.UUID actor = row.getPlayerUuid();
        return actor == null || actor.equals(viewer);
    }

    private static String actorName(ItemHistory row, java.util.UUID viewer, boolean staffView,
                                    boolean vietnamese) {
        return disclosesActor(row, viewer, staffView)
            ? safe(row.getPlayerName(), vietnamese)
            : (vietnamese ? "người chơi khác (cần quyền nhân viên)" : "another player (staff only)");
    }

    private static String actorLocation(ItemHistory row, java.util.UUID viewer, boolean staffView,
                                        boolean vietnamese) {
        return disclosesActor(row, viewer, staffView)
            ? safe(row.getLocation(), vietnamese)
            : (vietnamese ? "vị trí bị ẩn" : "location hidden");
    }

    static String eventLine(int number, ItemHistory row, boolean vietnamese,
                            java.util.UUID viewer, boolean staffView) {
        return "§7" + number + ". §f" + actionLabel(row.getAction(), vietnamese)
            + " §7| " + actorName(row, viewer, staffView, vietnamese)
            + " §7| " + actorLocation(row, viewer, staffView, vietnamese)
            + " §7| " + time(row.getTimestamp(), vietnamese);
    }

    static String iconTitle(Entry entry) {
        return "§6#" + entry.code();
    }

    // NOTE: there is deliberately no unredacted icon-lore helper here. Any lore builder that
    // renders a player name or location must take the viewer and staffView flags, like
    // eventIconLore below, so redaction cannot be skipped by calling the wrong method. A
    // previous helper without those parameters was removed before release for that reason.

    static List<String> eventIconLore(ItemHistory row, boolean vietnamese,
                                      java.util.UUID viewer, boolean staffView) {
        return List.of(
            "§7" + actionLabel(row.getAction(), vietnamese),
            "§7" + actorName(row, viewer, staffView, vietnamese),
            "§7" + actorLocation(row, viewer, staffView, vietnamese),
            "§7" + time(row.getTimestamp(), vietnamese),
            vietnamese ? "§8Lịch sử, không phải vị trí hiện tại" : "§8Historical record, not live custody");
    }

    private static String events(boolean vietnamese) {
        return vietnamese ? " sự kiện trong cửa sổ này" : " events in this window";
    }

    private static String relative(long timestamp, boolean vietnamese) {
        long difference = Math.max(0L, System.currentTimeMillis() - timestamp);
        String value;
        if (difference < 60_000L) {
            value = (difference / 1_000L) + (vietnamese ? " giây" : "s");
        } else if (difference < 3_600_000L) {
            value = (difference / 60_000L) + (vietnamese ? " phút" : "m");
        } else if (difference < 86_400_000L) {
            value = (difference / 3_600_000L) + (vietnamese ? " giờ" : "h");
        } else {
            value = (difference / 86_400_000L) + (vietnamese ? " ngày" : "d");
        }
        return vietnamese ? value + " trước" : value + " ago";
    }

    private static String humanize(String raw) {
        if (raw.isEmpty()) return "Unknown";
        String spaced = raw.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    private static String safe(String value, boolean vietnamese) {
        return value == null || value.isBlank() ? (vietnamese ? "không rõ" : "unknown") : value;
    }
}
