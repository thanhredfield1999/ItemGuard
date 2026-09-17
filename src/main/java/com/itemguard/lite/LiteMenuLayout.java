package com.itemguard.lite;

/**
 * Layout for the LITE browser, 54 slots in six rows.
 *
 * <p>The screen is read top to bottom as three zones. Row 1 explains what the screen is. Rows 2 to 5
 * hold the framed content area, one recorded identity or one event per slot. Row 6 is navigation,
 * with only controls that move within the browser; closing is already available through Minecraft.
 */
public final class LiteMenuLayout {

    private LiteMenuLayout() {
    }

    public static final int SIZE = 54;

    /** Row 1: what this screen shows and what a click does. */
    public static final int GUIDE_SLOT = 4;

    /** Row 6: paging. Previous and next sit at opposite ends; the page indicator is centred. */
    public static final int PREVIOUS_SLOT = 45;
    public static final int PAGE_SLOT = 49;
    public static final int NEXT_SLOT = 53;

    /**
     * On a timeline the bottom-left control returns to the overview instead of paging, so the exit
     * from a drill-down never moves to a different corner.
     */
    public static final int BACK_SLOT = PREVIOUS_SLOT;

    private static final int[] CONTENT_SLOTS = buildContentSlots();

    /** Content per page, derived from the content area so the two can never drift apart. */
    public static final int PAGE_SIZE = CONTENT_SLOTS.length;

    private static int[] buildContentSlots() {
        int[] slots = new int[28];
        int index = 0;
        for (int row = 1; row <= 4; row++) {
            for (int column = 1; column <= 7; column++) {
                slots[index++] = row * 9 + column;
            }
        }
        return slots;
    }

    public static int[] contentSlots() {
        return CONTENT_SLOTS.clone();
    }

    /** Position of a raw slot inside the content area, or -1 when the slot is frame or control. */
    public static int contentIndex(int rawSlot) {
        if (rawSlot < 0 || rawSlot >= SIZE) {
            return -1;
        }
        int row = rawSlot / 9;
        int column = rawSlot % 9;
        if (row < 1 || row > 4 || column < 1 || column > 7) {
            return -1;
        }
        return (row - 1) * 7 + (column - 1);
    }

    /** True when the slot is decorative frame: everything that is not content and not a control. */
    public static boolean isFrame(int rawSlot) {
        if (contentIndex(rawSlot) >= 0) {
            return false;
        }
        return rawSlot != GUIDE_SLOT && rawSlot != PREVIOUS_SLOT && rawSlot != PAGE_SLOT
            && rawSlot != NEXT_SLOT;
    }
}
