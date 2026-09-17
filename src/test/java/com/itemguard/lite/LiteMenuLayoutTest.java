package com.itemguard.lite;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Layout contract for the LITE browser. The GUI must read as clearly separated zones: a guide row on
 * top, one framed content area, and a navigation row at the bottom. Content must never collide with
 * the frame or the navigation controls.
 */
class LiteMenuLayoutTest {

    @Test void menuIsSixRows() {
        assertEquals(54, LiteMenuLayout.SIZE);
    }

    @Test void contentAreaIsAFramedBlockThatNeverTouchesTheBorder() {
        int[] content = LiteMenuLayout.contentSlots();
        assertEquals(28, content.length, "4 rows x 7 columns inside the frame");
        for (int slot : content) {
            assertTrue(slot >= 9 && slot < 45, "content stays out of the guide and navigation rows: " + slot);
            assertNotEquals(0, slot % 9, "content never uses the left border column: " + slot);
            assertNotEquals(8, slot % 9, "content never uses the right border column: " + slot);
        }
    }

    @Test void contentSlotsAreUniqueAndAscending() {
        int[] content = LiteMenuLayout.contentSlots();
        for (int i = 1; i < content.length; i++) {
            assertTrue(content[i] > content[i - 1], "slots must be ascending and unique");
        }
    }

    @Test void controlSlotsAreOnTheGuideOrNavigationRowAndDoNotOverlapContent() {
        // BACK_SLOT is deliberately an alias of PREVIOUS_SLOT, so distinctness is asserted on the
        // physical controls only. There is no close control: Minecraft already supplies that action.
        List<Integer> controls = List.of(
            LiteMenuLayout.GUIDE_SLOT, LiteMenuLayout.PREVIOUS_SLOT,
            LiteMenuLayout.PAGE_SLOT, LiteMenuLayout.NEXT_SLOT);
        assertEquals(controls.size(), controls.stream().distinct().count(), "controls must not share a slot");
        int[] content = LiteMenuLayout.contentSlots();
        for (int control : controls) {
            assertTrue(control < 54 && control >= 0);
            for (int slot : content) {
                assertNotEquals(slot, control, "a control must never sit on a content slot");
            }
        }
        assertTrue(LiteMenuLayout.GUIDE_SLOT < 9, "the guide belongs to the top row");
        for (int control : List.of(LiteMenuLayout.PREVIOUS_SLOT, LiteMenuLayout.PAGE_SLOT,
                LiteMenuLayout.NEXT_SLOT, LiteMenuLayout.BACK_SLOT)) {
            assertTrue(control >= 45, "navigation belongs to the bottom row: " + control);
        }
    }

    @Test void previousAndNextSitAtOppositeEndsWithThePageIndicatorCentred() {
        assertEquals(45, LiteMenuLayout.PREVIOUS_SLOT);
        assertEquals(53, LiteMenuLayout.NEXT_SLOT);
        assertEquals(49, LiteMenuLayout.PAGE_SLOT);
    }

    @Test void backReplacesPreviousOnTheTimelineSoTheExitIsAlwaysBottomLeft() {
        assertEquals(LiteMenuLayout.PREVIOUS_SLOT, LiteMenuLayout.BACK_SLOT);
    }

    @Test void contentIndexMapsOnlyContentSlots() {
        int[] content = LiteMenuLayout.contentSlots();
        for (int index = 0; index < content.length; index++) {
            assertEquals(index, LiteMenuLayout.contentIndex(content[index]));
        }
        assertEquals(-1, LiteMenuLayout.contentIndex(0));
        assertEquals(-1, LiteMenuLayout.contentIndex(LiteMenuLayout.PAGE_SLOT));
        assertEquals(-1, LiteMenuLayout.contentIndex(9));
        assertEquals(-1, LiteMenuLayout.contentIndex(-1));
        assertEquals(-1, LiteMenuLayout.contentIndex(54));
    }

    @Test void pageSizeMatchesTheContentArea() {
        assertEquals(LiteMenuLayout.contentSlots().length, LiteMenuLayout.PAGE_SIZE);
    }

    @Test void borderCoversEverySlotThatIsNotContentOrControl() {
        for (int slot = 0; slot < LiteMenuLayout.SIZE; slot++) {
            boolean content = LiteMenuLayout.contentIndex(slot) >= 0;
            boolean control = slot == LiteMenuLayout.GUIDE_SLOT || slot == LiteMenuLayout.PREVIOUS_SLOT
                || slot == LiteMenuLayout.PAGE_SLOT || slot == LiteMenuLayout.NEXT_SLOT;
            assertEquals(!content && !control, LiteMenuLayout.isFrame(slot),
                "frame must fill exactly the leftover slots, slot " + slot);
        }
    }
}
