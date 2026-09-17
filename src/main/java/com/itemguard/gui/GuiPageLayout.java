package com.itemguard.gui;

public final class GuiPageLayout {

    private static final int COLUMNS = 7;
    private static final int ROWS = 4;
    private static final int FIRST_SLOT = 10;

    private GuiPageLayout() {
    }

    public static int pageSize() {
        return COLUMNS * ROWS;
    }

    public static int totalPages(int entryCount) {
        int boundedCount = Math.max(0, entryCount);
        return Math.max(1, (boundedCount + pageSize() - 1) / pageSize());
    }

    public static int startIndex(int page) {
        return Math.max(0, page - 1) * pageSize();
    }

    public static int endIndex(int page, int entryCount) {
        return Math.min(startIndex(page) + pageSize(), Math.max(0, entryCount));
    }

    public static int inventorySlot(int pageOffset) {
        if (pageOffset < 0 || pageOffset >= pageSize()) {
            throw new IllegalArgumentException("Page offset is outside the ItemGuard grid");
        }
        int row = pageOffset / COLUMNS;
        int column = pageOffset % COLUMNS;
        return FIRST_SLOT + row * 9 + column;
    }
}
