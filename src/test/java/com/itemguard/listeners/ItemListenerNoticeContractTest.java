package com.itemguard.listeners;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H3 (review 2026-09-17): every cancellation in {@link ItemListener} was silent.
 *
 * <p>Readiness is an in-memory cache that a restart empties, so the first click, drop, drag, use or
 * pickup of a tracked item is refused while the check runs — and the player was told nothing at all.
 * The behaviour of the guard is deliberate (fail-closed); the silence was the defect, and it is the
 * defect class this project keeps meeting: things that work exactly as written and are still wrong
 * for the player.
 *
 * <p>A source contract rather than a mocked-event test: what has to stay true is that every place
 * which cancels for this reason also tells the player, and that is a property of the code, not of
 * one event path. Calling the guard on a real event is covered by the runtime fixtures.
 */
class ItemListenerNoticeContractTest {

    private static final String SOURCE = "src/main/java/com/itemguard/listeners/ItemListener.java";

    @Test
    void everyIdentityReadyCancelTellsThePlayer() throws Exception {
        String source = Files.readString(Path.of(SOURCE));
        int guards = count(source, Pattern.compile(
            "if \\(!tracking\\.is(?:Entity)?IdentityReady\\("));
        // Two refusals are not reached through a readiness guard: a corrupt tag is rejected before
        // any check is attempted, and a pickup is cancelled so the item can be tagged first.
        int corruptRefusals = count(source, Pattern.compile("PickupIdentityAction\\.IGNORE_CORRUPT"));
        int tagSourceRefusals = count(source, Pattern.compile("PickupIdentityAction\\.TAG_SOURCE"));
        int refusals = count(source, Pattern.compile("event\\.setCancelled\\(true\\);"));
        int notices = count(source, Pattern.compile("tell(?:IdentityNotReady|CorruptTag|BeingTagged)\\(player\\);"));
        assertTrue(guards >= 5, "expected at least five identity guards, found " + guards);
        assertEquals(guards + corruptRefusals + tagSourceRefusals, notices,
            "every refusal must be announced: " + guards + " readiness guards + "
                + corruptRefusals + " corrupt-tag + " + tagSourceRefusals + " tag-source, but "
                + notices + " notices (" + refusals + " cancellations in the class)");
    }

    @Test
    void theCorruptTagRefusalIsAnnouncedToo() throws Exception {
        String source = Files.readString(Path.of(SOURCE));
        String body = method(source, "public void onItemPickup(PlayerPickupItemEvent event)");
        int corrupt = body.indexOf("PickupIdentityAction.IGNORE_CORRUPT");
        assertTrue(corrupt >= 0, "the corrupt-tag branch must still be handled");
        assertTrue(body.indexOf("tellCorruptTag(player);", corrupt) > corrupt,
            "an item with a corrupt tag is refused and must say so, not vanish silently");
    }

    @Test
    void theNoticeIsThrottledRatherThanSentPerEvent() throws Exception {
        String source = Files.readString(Path.of(SOURCE));
        String body = method(source, "private void notifyOnce(Player player, String key)");
        assertTrue(body.contains("NOTICE_WINDOW_MS"), "the notice needs a window");
        assertTrue(body.contains("identityNoticeAt"), "the window needs to be kept per player");
        assertTrue(body.contains("NOTICE_MAX_PLAYERS"),
            "the map must be bounded the way the container cooldown is");
        assertTrue(source.contains("\"identity-not-ready\""),
            "the text belongs in the message files, not in the listener");
        assertTrue(source.contains("\"identity-corrupt\"") && source.contains("\"pickup-tagging\""),
            "each refusal names its own key, so a permanent one does not reuse the transient text");
    }

    private static int count(String source, Pattern pattern) {
        Matcher matcher = pattern.matcher(source);
        int found = 0;
        while (matcher.find()) {
            found++;
        }
        return found;
    }

    private static String method(String source, String start) {
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
