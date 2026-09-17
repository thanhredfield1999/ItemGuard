package com.itemguard.tracking;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftOutputWiringContractTest {

    @Test
    void listenerUsesFailClosedPolicyForNormalAndShiftClicks() throws Exception {
        String source = Files.readString(Path.of(
            "src/main/java/com/itemguard/listeners/CraftListener.java"
        ));
        String body = method(source, "public void onCraft(CraftItemEvent event)");

        assertTrue(body.contains("event.getCurrentItem()"),
            "CraftItemEvent clicked result is the only event-local output stack");
        assertTrue(body.contains("CraftOutputPolicy.Action action = craftOutputPolicy.decide("));
        assertTrue(body.contains("tracking.hasCodeOrUuid(result)"));
        assertTrue(body.contains("tracking.isIdentityReady(result)"));
        assertTrue(body.contains("tracking.shouldTrack(result)"));
        assertTrue(body.contains("event.setCancelled(true)"));
        assertFalse(body.contains("event.isShiftClick()"),
            "same fail-closed decision must cover normal and shift craft");
    }

    @Test
    void listenerNeverMutatesOrPublishesCraftResult() throws Exception {
        String source = Files.readString(Path.of(
            "src/main/java/com/itemguard/listeners/CraftListener.java"
        ));
        String body = method(source, "public void onCraft(CraftItemEvent event)");

        assertFalse(body.contains("setResult("));
        assertFalse(body.contains("tagItem("));
        assertFalse(body.contains("onItemCraft("));
        assertFalse(body.contains("requestInventorySlotTag("));
        assertFalse(source.contains("PrepareItemCraftEvent"));
    }

    @Test
    void corruptOrPartialIdentityIsEvaluatedBeforeEligibility() throws Exception {
        String source = Files.readString(Path.of(
            "src/main/java/com/itemguard/listeners/CraftListener.java"
        ));
        String body = method(source, "public void onCraft(CraftItemEvent event)");

        int identity = body.indexOf("tracking.hasCodeOrUuid(result)");
        int ready = body.indexOf("tracking.isIdentityReady(result)");
        int eligible = body.indexOf("tracking.shouldTrack(result)");
        assertTrue(identity >= 0 && ready > identity && eligible > ready);
    }

    @Test
    void genericInventoryClickDefersCraftEventsToCraftListener() throws Exception {
        String source = Files.readString(Path.of(
            "src/main/java/com/itemguard/listeners/ItemListener.java"
        ));
        String body = method(source,
            "public void onInventoryClick(InventoryClickEvent event)");

        int defer = body.indexOf("if (event instanceof CraftItemEvent) return;");
        int player = body.indexOf("event.getWhoClicked()");
        int currentItem = body.indexOf("event.getCurrentItem()");
        assertTrue(defer >= 0 && player > defer && currentItem > defer,
            "generic inventory click must not pre-empt CraftListener ownership");
    }

    private String method(String source, String start) {
        int from = source.indexOf(start);
        assertTrue(from >= 0, "missing method start " + start);
        int brace = source.indexOf('{', from);
        int depth = 0;
        for (int index = brace; index < source.length(); index++) {
            char ch = source.charAt(index);
            if (ch == '{') depth++;
            if (ch == '}' && --depth == 0) return source.substring(from, index + 1);
        }
        throw new AssertionError("unterminated method " + start);
    }
}
