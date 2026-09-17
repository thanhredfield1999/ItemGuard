package com.itemguard.custody;

import com.itemguard.data.ItemHistory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How the custody chain is shown.
 *
 * <p>The count answers "did this item move around a lot", which any player may see for their own
 * item. The names answer "who had it", which is another player's data and stays staff-only, matching
 * the redaction already applied to history rows.
 */
class CustodyPresentationTest {

    private static final UUID A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final long MINUTE = 60_000L;

    private static ItemHistory row(String action, UUID player, String name, long at) {
        ItemHistory history = new ItemHistory();
        history.setCode("CODE01");
        history.setAction(action);
        history.setPlayerUuid(player);
        history.setPlayerName(name);
        history.setTimestamp(at);
        return history;
    }

    private static CustodyChain chain() {
        List<ItemHistory> rows = new ArrayList<>(List.of(
            row("SPAWN", A, "Alpha", 0),
            row("PICKUP", B, "Bravo", 10 * MINUTE)));
        java.util.Collections.reverse(rows);
        return CustodyChain.replay(List.copyOf(rows), new CustodyTransferPolicy(5 * MINUTE));
    }

    private static void assertNoSmallCaps(String text) {
        for (int i = 0; i < text.length(); ) {
            int code = text.codePointAt(i);
            i += Character.charCount(code);
            boolean smallCap = (code >= 0x1D00 && code <= 0x1D7F) || code == 0x0299 || code == 0x029C
                || code == 0x0280 || code == 0x028F || code == 0x0274 || code == 0x0262;
            assertFalse(smallCap, "ItemGuard must not use small caps: " + text);
        }
    }

    @Test void aMemberSeesTheCountsButNotTheOtherPlayersName() {
        List<String> lines = CustodyPresentation.lore(chain(), false, false);
        String body = String.join(" ", lines);
        assertTrue(body.contains("1"), "the handover count must be visible: " + body);
        assertFalse(body.contains("Alpha"), "a member must not see another holder's name: " + body);
        assertFalse(body.contains("Bravo"), body);
        lines.forEach(CustodyPresentationTest::assertNoSmallCaps);
    }

    @Test void staffSeeTheNamesInOrder() {
        List<String> lines = CustodyPresentation.lore(chain(), true, false);
        String body = String.join(" ", lines);
        assertTrue(body.contains("Alpha") && body.contains("Bravo"),
            "staff investigating an item need the holders: " + body);
        assertTrue(body.indexOf("Alpha") < body.indexOf("Bravo"), "order must be preserved: " + body);
    }

    @Test void bothLanguagesAreSupportedAndNeitherUsesSmallCaps() {
        CustodyPresentation.lore(chain(), true, true).forEach(CustodyPresentationTest::assertNoSmallCaps);
        String vietnamese = String.join(" ", CustodyPresentation.lore(chain(), false, true));
        assertFalse(vietnamese.isBlank());
        assertFalse(vietnamese.contains("Alpha"), vietnamese);
    }

    @Test void theWordingNeverClaimsPresentPossession() {
        for (boolean staff : List.of(false, true)) {
            for (boolean vietnamese : List.of(false, true)) {
                String body = String.join(" ", CustodyPresentation.lore(chain(), staff, vietnamese))
                    .toLowerCase();
                assertTrue(body.contains("recorded") || body.contains("đã ghi") || body.contains("ghi nhận"),
                    "custody wording must stay historical: " + body);
            }
        }
    }

    @Test void anItemThatNeverChangedHandsSaysSoPlainlyInsteadOfShowingAnEmptyLine() {
        List<ItemHistory> rows = List.of(row("SPAWN", A, "Alpha", 0));
        CustodyChain solo = CustodyChain.replay(rows, new CustodyTransferPolicy(MINUTE));
        String body = String.join(" ", CustodyPresentation.lore(solo, false, false)).toLowerCase();
        assertTrue(body.contains("0") || body.contains("no "), body);
    }

    @Test void anEmptyChainProducesNoLoreRatherThanMisleadingZeros() {
        CustodyChain empty = CustodyChain.replay(List.of(), new CustodyTransferPolicy(MINUTE));
        assertTrue(CustodyPresentation.lore(empty, true, false).isEmpty(),
            "with nothing recorded there is nothing honest to say");
    }
}
