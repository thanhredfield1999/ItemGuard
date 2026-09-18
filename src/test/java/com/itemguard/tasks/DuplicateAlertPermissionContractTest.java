package com.itemguard.tasks;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Two defects met in one line of {@code InventoryScanTask.reportFindings}:
 *
 * <ul>
 *   <li>FULL gated the duplicate alert on {@code itemguard.bypass} — the permission that means "skip
 *       anti-dupe checks" — while LITE used {@code itemguard.notify}. A staff member trusted to see
 *       alerts but not to bypass checks never saw one, and the two meanings shared a node.</li>
 *   <li>{@code itemguard.notify} was declared in no {@code plugin.yml} at all, so on LITE the node
 *       only worked because the fixture granted it by hand; a real server owner had nothing to grant
 *       except {@code itemguard.*}.</li>
 * </ul>
 */
class DuplicateAlertPermissionContractTest {

    private static final Path TASK =
        Path.of("src/main/java/com/itemguard/tasks/InventoryScanTask.java");
    private static final Path DESCRIPTOR = Path.of("src/main/resources/plugin.yml");

    @Test
    void theAlertChecksTheNotifyPermissionOnBothEditions() throws Exception {
        String task = Files.readString(TASK);

        assertTrue(task.contains("hasPermission(\"itemguard.notify\")"),
            "the duplicate alert must check itemguard.notify");
        assertFalse(task.contains("hasPermission(\"itemguard.bypass\")"),
            "the alert must not be gated on the bypass permission, which means something else; "
                + "mentioning it in a comment is fine, checking it is not");
        assertFalse(task.contains("isLiteEdition() ?"),
            "one node for both editions: the edition branch is what let the two meanings diverge");
    }

    @Test
    void theNotifyPermissionIsDeclaredWithADefault() throws Exception {
        List<String> lines = Files.readAllLines(DESCRIPTOR);
        int declaration = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("  itemguard.notify:")) {
                declaration = i;
                break;
            }
        }
        assertTrue(declaration >= 0,
            "plugin.yml must declare itemguard.notify; otherwise a server owner has nothing to grant");
        String block = String.join("\n", lines.subList(declaration,
            Math.min(lines.size(), declaration + 5)));
        assertTrue(block.contains("default: op"),
            "duplicate alerts carry item names and locations, so the node defaults to op: " + block);
        assertTrue(Files.readString(DESCRIPTOR).contains("      itemguard.notify: true"),
            "it must also be a child of itemguard.* so an operator granting that gets it");
    }
}
