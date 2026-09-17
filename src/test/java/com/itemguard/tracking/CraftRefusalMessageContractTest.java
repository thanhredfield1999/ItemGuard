package com.itemguard.tracking;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H3 (review #3): the refusal message was chosen by a binary condition in {@code CraftListener}, and
 * it was inverted — the copy refusal fell through to the message that tells an admin to set
 * {@code tracking.cancel-untracked-craft-output: false}, a key {@link CraftOutputPolicy#decide} never
 * reaches once the result carries an identity. An admin would change the setting, restart, and still
 * be blocked, with no clue as to why.
 *
 * <p>The mapping is now a pure function next to the actions, so it is tested here rather than
 * eyeballed, and the three refusals are required to be three different sentences — one of which may
 * name that config key and two of which may not.
 */
class CraftRefusalMessageContractTest {

    private static final List<CraftOutputPolicy.Action> REFUSALS = List.of(
        CraftOutputPolicy.Action.CANCEL_UNTAGGED_ELIGIBLE,
        CraftOutputPolicy.Action.CANCEL_TAGGED_NOT_READY,
        CraftOutputPolicy.Action.CANCEL_TAGGED_READY_COPY);

    private static final String CONFIG_KEY = "tracking.cancel-untracked-craft-output";

    @Test
    void everyRefusalGetsItsOwnMessage() {
        Set<String> keys = new LinkedHashSet<>();
        for (CraftOutputPolicy.Action action : REFUSALS) {
            keys.add(CraftOutputPolicy.messageKeyFor(action));
        }
        assertEquals(REFUSALS.size(), keys.size(),
            "three refusals with three different meanings must not share a message, got " + keys);
        assertEquals(Set.of("craft-refused", "craft-refused-tag-pending", "craft-refused-tagged"), keys);
    }

    @Test
    void onlyTheRefusalAConfigKeyDecidesMayNameThatKey() throws Exception {
        String english = englishMessages();
        String untagged = lineFor(english, CraftOutputPolicy.messageKeyFor(
            CraftOutputPolicy.Action.CANCEL_UNTAGGED_ELIGIBLE));
        assertTrue(untagged.contains(CONFIG_KEY),
            "the refusal an admin can switch off has to name the switch: " + untagged);

        for (CraftOutputPolicy.Action action : List.of(
                CraftOutputPolicy.Action.CANCEL_TAGGED_NOT_READY,
                CraftOutputPolicy.Action.CANCEL_TAGGED_READY_COPY)) {
            String key = CraftOutputPolicy.messageKeyFor(action);
            String text = lineFor(english, key);
            assertFalse(text.contains(CONFIG_KEY),
                key + " must not send an admin to a setting that cannot change its outcome: " + text);
            assertFalse(text.contains("would need a new tracked identity"),
                key + " must not claim the result lacks an identity - it has one: " + text);
        }
    }

    @Test
    void everyKeyTheMappingNamesExistsInEveryMessageSource() throws Exception {
        // Four sources, because LITE ships English only while FULL keeps the Vietnamese and Chinese
        // files; a key that exists in one and not the others is a message nobody can read.
        List<String> sources = List.of(
            "src/main/resources/messages_en.yml",
            "src/main/resources/messages.yml",
            "src/main/resources/messages_zh.yml",
            "src/main/java/com/itemguard/MessageManager.java");
        for (String source : sources) {
            String text = Files.readString(Path.of(source));
            for (CraftOutputPolicy.Action action : REFUSALS) {
                String key = CraftOutputPolicy.messageKeyFor(action);
                assertTrue(text.contains(key),
                    key + " is missing from " + source + ", so that refusal would print nothing");
            }
        }
    }

    @Test
    void theAllowedActionsAreNotTreatedAsRefusals() {
        // Both allow actions return before the message is sent, so their key is never printed. Mapped
        // to the untagged text because it is the only one that makes no claim about being blocked.
        assertEquals("craft-refused",
            CraftOutputPolicy.messageKeyFor(CraftOutputPolicy.Action.ALLOW_UNTRACKED));
        assertEquals("craft-refused",
            CraftOutputPolicy.messageKeyFor(CraftOutputPolicy.Action.ALLOW_UNTAGGED_ELIGIBLE));
        assertNotEquals(CraftOutputPolicy.messageKeyFor(CraftOutputPolicy.Action.ALLOW_UNTRACKED),
            CraftOutputPolicy.messageKeyFor(CraftOutputPolicy.Action.CANCEL_TAGGED_READY_COPY),
            "an allowed craft must never be handed the duplicate-refusal text");
    }

    private static String englishMessages() throws Exception {
        return Files.readString(Path.of("src/main/resources/messages_en.yml"));
    }

    private static String lineFor(String yaml, String key) {
        return yaml.lines()
            .filter(line -> line.startsWith(key + ":"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no line for " + key + " in messages_en.yml"));
    }
}
