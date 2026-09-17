package com.itemguard.commands;

import com.itemguard.search.FindItemService;
import com.itemguard.search.FindItemStartResult;
import com.itemguard.search.ItemSearchMode;
import com.itemguard.search.ItemSearchRequest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

public final class FindItemCommandTask {

    private static final int PAGE_SIZE = 10;

    private final FindItemService service;
    private final LongSupplier clockMillis;

    public FindItemCommandTask(FindItemService service, LongSupplier clockMillis) {
        this.service = Objects.requireNonNull(service, "service");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
    }

    public List<String> execute(
        FindItemCommandAction action,
        UUID actorUuid,
        String actorName
    ) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(actorName, "actorName");
        if (action instanceof FindItemCommandAction.Start start) {
            return start(start, actorUuid, actorName);
        }
        if (action instanceof FindItemCommandAction.Stop stop) {
            return List.of(service.stop(stop.code())
                ? "§e§l[ItemGuard] §aDa dung tim ID §f" + stop.code()
                : "§e§l[ItemGuard] §cKhong co yeu cau ACTIVE cho ID §f" + stop.code());
        }
        if (action instanceof FindItemCommandAction.ListActive list) {
            return list(list.page());
        }
        if (action instanceof FindItemCommandAction.Remove remove) {
            return List.of(service.remove(remove.code())
                ? "§e§l[ItemGuard] §aDa xoa yeu cau ID §f" + remove.code()
                : "§e§l[ItemGuard] §cKhong tim thay yeu cau ID §f" + remove.code());
        }
        if (action == FindItemCommandAction.ClearConfirmed.INSTANCE) {
            return List.of("§e§l[ItemGuard] §aDa xoa §f" + service.clear()
                + " §ayeu cau tim.");
        }
        throw new IllegalArgumentException("Unsupported find-item action: " + action);
    }

    private List<String> start(
        FindItemCommandAction.Start start,
        UUID actorUuid,
        String actorName
    ) {
        FindItemStartResult result = service.start(
            start.code(),
            start.mode(),
            start.duration(),
            actorUuid,
            actorName
        );
        return switch (result) {
            case NOT_TRACKED -> List.of(
                "§e§l[ItemGuard] §cID chua duoc theo doi: §f" + start.code());
            case ALREADY_ACTIVE -> List.of(
                "§e§l[ItemGuard] §cID dang co yeu cau ACTIVE: §f" + start.code());
            case STARTED -> {
                List<String> messages = new ArrayList<>();
                messages.add("§e§l[ItemGuard] §aDa tao yeu cau §f"
                    + start.mode().name() + " §acho ID §f" + start.code()
                    + " §atrong §f" + formatDuration(start.duration()));
                if (start.mode() == ItemSearchMode.TAKE) {
                    messages.add(
                        "§e§l[ItemGuard] §eTAKE hien chi la intent; plugin chua tu tich thu/xoa item."
                    );
                }
                yield List.copyOf(messages);
            }
        };
    }

    private List<String> list(int page) {
        int offset;
        try {
            offset = Math.multiplyExact(page - 1, PAGE_SIZE);
        } catch (ArithmeticException overflow) {
            return List.of("§e§l[ItemGuard] §cSo trang qua lon.");
        }

        List<ItemSearchRequest> requests = service.listActive(offset, PAGE_SIZE);
        List<String> messages = new ArrayList<>();
        messages.add("§e§l=== FindItem ACTIVE - Trang " + page + " ===");
        if (requests.isEmpty()) {
            messages.add("§7Khong co yeu cau tren trang nay.");
            return List.copyOf(messages);
        }

        long now = clockMillis.getAsLong();
        for (ItemSearchRequest request : requests) {
            long remainingMillis = Math.max(0L, request.expiresAt() - now);
            messages.add("§7- §f" + request.code()
                + " §8| §e" + request.mode().name()
                + " §8| §7con §f" + formatDuration(Duration.ofMillis(remainingMillis))
                + " §8| §7boi §f" + request.actorName());
        }
        return List.copyOf(messages);
    }

    private String formatDuration(Duration duration) {
        long seconds = Math.max(0L, duration.toSeconds());
        long days = seconds / 86_400L;
        long hours = (seconds % 86_400L) / 3_600L;
        long minutes = (seconds % 3_600L) / 60L;
        long remainingSeconds = seconds % 60L;
        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + minutes + "m";
        if (minutes > 0) return minutes + "m " + remainingSeconds + "s";
        return remainingSeconds + "s";
    }
}
