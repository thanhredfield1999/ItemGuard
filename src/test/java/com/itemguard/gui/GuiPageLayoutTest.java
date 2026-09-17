package com.itemguard.gui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GuiPageLayoutTest {

    @Test
    void twentyNineEntriesUseTwoPagesWithoutOmission() {
        assertEquals(28, GuiPageLayout.pageSize());
        assertEquals(2, GuiPageLayout.totalPages(29));
        assertEquals(List.of(0, 28), List.of(
            GuiPageLayout.startIndex(1),
            GuiPageLayout.startIndex(2)
        ));
        assertEquals(List.of(28, 29), List.of(
            GuiPageLayout.endIndex(1, 29),
            GuiPageLayout.endIndex(2, 29)
        ));
    }

    @Test
    void pageWindowsCoverEveryEntryExactlyOnce() {
        List<Integer> visited = new ArrayList<>();
        int size = 200;
        for (int page = 1; page <= GuiPageLayout.totalPages(size); page++) {
            for (int index = GuiPageLayout.startIndex(page);
                 index < GuiPageLayout.endIndex(page, size);
                 index++) {
                visited.add(index);
            }
        }
        assertEquals(200, visited.size());
        assertEquals(200, visited.stream().distinct().count());
        assertEquals(0, visited.getFirst());
        assertEquals(199, visited.getLast());
    }
}
