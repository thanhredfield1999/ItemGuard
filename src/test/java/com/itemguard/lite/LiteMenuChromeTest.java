package com.itemguard.lite;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Presentation contract for the LITE browser chrome. Every label must tell the player what the
 * screen is, what a click will do, and what the data does not mean. ItemGuard does not use small
 * caps anywhere.
 */
class LiteMenuChromeTest {

    private static void assertNoSmallCaps(String text) {
        for (int i = 0; i < text.length(); ) {
            int code = text.codePointAt(i);
            i += Character.charCount(code);
            // Latin small-capital letters live in the Phonetic Extensions and IPA blocks.
            boolean smallCap = (code >= 0x1D00 && code <= 0x1D7F) || code == 0x0299 || code == 0x029C
                || code == 0x0280 || code == 0x028F || code == 0x0274 || code == 0x0262;
            assertFalse(smallCap, "ItemGuard must not use small caps: U+" + Integer.toHexString(code)
                + " in " + text);
        }
    }

    private static void assertReadable(LiteMenuChrome.Element element) {
        assertNotNull(element);
        assertFalse(element.title().isBlank(), "every control needs a visible title");
        assertNoSmallCaps(element.title());
        element.lore().forEach(LiteMenuChromeTest::assertNoSmallCaps);
        assertNotNull(element.material());
        assertFalse(element.material().isBlank());
    }

    @Test void guideExplainsTheScreenAndTheClickActionInBothLanguages() {
        for (boolean vietnamese : List.of(false, true)) {
            LiteMenuChrome.Element guide = LiteMenuChrome.overviewGuide(vietnamese, 3, 28);
            assertReadable(guide);
            String body = String.join(" ", guide.lore()).toLowerCase();
            assertTrue(body.contains("click") || body.contains("nhấn"),
                "the guide must say what a click does: " + guide.lore());
            assertTrue(body.contains("3"), "the guide must state how much is shown: " + guide.lore());
            assertTrue(body.contains("not") || body.contains("không"),
                "the guide must state that history is not live custody: " + guide.lore());
        }
    }

    @Test void timelineGuideNamesTheItemAndSaysHowToGoBack() {
        for (boolean vietnamese : List.of(false, true)) {
            LiteMenuChrome.Element guide = LiteMenuChrome.timelineGuide(vietnamese, "9PDTWF", 7);
            assertReadable(guide);
            assertTrue(guide.title().contains("9PDTWF"), "the timeline must name the item: " + guide.title());
            String body = String.join(" ", guide.lore()).toLowerCase();
            assertTrue(body.contains("back") || body.contains("quay lại"),
                "the timeline guide must explain how to return: " + guide.lore());
        }
    }

    @Test void pageIndicatorShowsCurrentAndTotalPages() {
        LiteMenuChrome.Element page = LiteMenuChrome.pageIndicator(false, 2, 5);
        assertReadable(page);
        assertTrue(page.title().contains("2") && page.title().contains("5"),
            "the indicator must show position and total: " + page.title());
    }

    @Test void disabledPagingIsStillLabelledInsteadOfBeingAnUnexplainedBlankSlot() {
        LiteMenuChrome.Element disabled = LiteMenuChrome.previous(false, false);
        assertReadable(disabled);
        String body = (disabled.title() + " " + String.join(" ", disabled.lore())).toLowerCase();
        assertTrue(body.contains("first") || body.contains("no "), "a disabled control must say why: " + body);
        assertNotEquals(LiteMenuChrome.previous(false, true).material(), disabled.material(),
            "enabled and disabled paging must be visually distinct");
    }

    @Test void pagingAndBackAreAlwaysLabelled() {
        for (boolean vietnamese : List.of(false, true)) {
            assertReadable(LiteMenuChrome.back(vietnamese));
            assertReadable(LiteMenuChrome.next(vietnamese, true));
            assertReadable(LiteMenuChrome.next(vietnamese, false));
        }
    }

    @Test void emptyStateTellsThePlayerWhatToDoNextInsteadOfOnlySayingNothingFound() {
        for (boolean vietnamese : List.of(false, true)) {
            LiteMenuChrome.Element empty = LiteMenuChrome.emptyState(vietnamese);
            assertReadable(empty);
            String body = String.join(" ", empty.lore()).toLowerCase();
            assertTrue(body.contains("/ig check") || body.contains("/ig"),
                "the empty state must point at a concrete next step: " + empty.lore());
            assertTrue(body.contains("not") || body.contains("không"),
                "absence of history must not be presented as proof: " + empty.lore());
        }
    }

    @Test void frameMaterialsSeparateBorderFromContentBackground() {
        assertNotEquals(LiteMenuChrome.BORDER_MATERIAL, LiteMenuChrome.CONTENT_BACKGROUND_MATERIAL,
            "the border and the content area must be visually distinguishable");
        assertNoSmallCaps(LiteMenuChrome.BORDER_MATERIAL);
    }

    @Test void everyLabelAvoidsSmallCapsIncludingItemAndEventRows() {
        assertNoSmallCaps(LiteMenuChrome.itemRowHint(false));
        assertNoSmallCaps(LiteMenuChrome.itemRowHint(true));
        assertNoSmallCaps(LiteMenuChrome.historyDisclaimer(false));
        assertNoSmallCaps(LiteMenuChrome.historyDisclaimer(true));
    }
}
