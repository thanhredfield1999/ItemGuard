package com.itemguard.restore;

import java.util.Locale;

/**
 * Why an item stopped existing.
 *
 * <p>"Absent from a sweep" is not something an admin can act on. Burned, cleared and despawned are
 * different events with different blame, and the reason is what makes a loss report trustworthy.
 *
 * <p>{@link #UNKNOWN} deliberately does not confirm destruction: guessing a reason is how a false
 * positive turns into a free duplicate.
 */
public enum LossReason {

    BURNED("BURNED", true, "Burned in fire or lava", "Bị thiêu cháy trong lửa hoặc dung nham"),

    CLEARED("CLEARED", true, "Removed by a command or plugin", "Bị lệnh hoặc plugin xoá"),

    DESPAWNED("DESPAWNED", true, "Vanished off the ground after the despawn timer",
        "Biến mất khỏi mặt đất sau thời gian chờ"),

    VOID("VOID", true, "Fell into the void", "Rơi xuống hư không"),

    UNKNOWN("LOST", false, "Gone, with no recorded cause", "Đã mất, không rõ nguyên nhân");

    private final String action;
    private final boolean confirmsDestruction;
    private final String english;
    private final String vietnamese;

    LossReason(String action, boolean confirmsDestruction, String english, String vietnamese) {
        this.action = action;
        this.confirmsDestruction = confirmsDestruction;
        this.english = english;
        this.vietnamese = vietnamese;
    }

    public static LossReason fromCause(String cause) {
        if (cause == null || cause.isBlank()) {
            return UNKNOWN;
        }
        return switch (cause.trim().toUpperCase(Locale.ROOT)) {
            case "FIRE", "FIRE_TICK", "LAVA", "BURNED", "BURN" -> BURNED;
            case "CLEARED", "CLEAR", "PLUGIN", "COMMAND" -> CLEARED;
            case "DESPAWN", "DESPAWNED" -> DESPAWNED;
            case "VOID" -> VOID;
            default -> UNKNOWN;
        };
    }

    /** The action string written to history. */
    public String action() {
        return action;
    }

    /** Whether this reason is specific enough to declare the item destroyed. */
    public boolean confirmsDestruction() {
        return confirmsDestruction;
    }

    public String describe(boolean vietnameseText) {
        return vietnameseText ? vietnamese : english;
    }
}
