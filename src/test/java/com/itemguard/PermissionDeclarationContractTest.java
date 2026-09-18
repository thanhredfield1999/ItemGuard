package com.itemguard;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A permission declared in {@code plugin.yml} is a promise to a server owner.
 *
 * <p>This exists because two of them — {@code itemguard.restore} and {@code itemguard.teleport} —
 * sat in the file for months describing features with no code behind them at all; a full source
 * audit found them ("declared, word never appears in Java") and they were the single most damaging
 * line a paid listing could inherit. The other three FULL gaps found that day were the same species:
 * something advertised that nothing calls.
 *
 * <p>The rule: every leaf node must appear as a string literal somewhere in {@code src/main/java}.
 * The parent node and a short, explicit list of exceptions carry their reasons here, so adding a
 * permission without a use fails this test rather than shipping quietly.
 */
class PermissionDeclarationContractTest {

    private static final Pattern LEAF = Pattern.compile(
        "^ {2}(itemguard\\.[a-z.]+):\\s*$", Pattern.MULTILINE
    );

    /**
     * Nodes deliberately declared without a Java literal, each with the reason it is still honest.
     * Everything else must be used.
     */
    private static final Set<String> DECLARED_WITHOUT_A_LITERAL = Set.of(
        // Bukkit resolves the wildcard itself; no plugin code ever checks it.
        "itemguard.*",
        // Checked by the command framework through the plugin.yml `commands:` section rather than
        // by a hasPermission call in this plugin's source.
        "itemguard.matdo",
        "itemguard.track"
    );

    private static Set<String> declaredPermissions() throws IOException {
        String descriptor = Files.readString(
            Path.of("src/main/resources/plugin.yml"), StandardCharsets.UTF_8
        );
        Set<String> nodes = new LinkedHashSet<>();
        Matcher matcher = LEAF.matcher(descriptor);
        while (matcher.find()) {
            nodes.add(matcher.group(1));
        }
        return nodes;
    }

    private static String allJavaSource() throws IOException {
        StringBuilder source = new StringBuilder();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            List<Path> javaFiles = new ArrayList<>(files.filter(p -> p.toString().endsWith(".java")).toList());
            for (Path file : javaFiles) {
                source.append(Files.readString(file, StandardCharsets.UTF_8)).append('\n');
            }
        } catch (UncheckedIOException failure) {
            throw failure;
        }
        return source.toString();
    }

    @Test
    void everyDeclaredPermissionIsUsedSomewhereInJava() throws Exception {
        Set<String> declared = declaredPermissions();
        assertTrue(declared.size() >= 10,
            "the permission list was not parsed; found only " + declared);

        String java = allJavaSource();
        List<String> unused = new ArrayList<>();
        for (String node : declared) {
            if (DECLARED_WITHOUT_A_LITERAL.contains(node)) {
                continue;
            }
            if (!java.contains("\"" + node + "\"")) {
                unused.add(node);
            }
        }

        assertTrue(unused.isEmpty(),
            "these permissions are declared but no Java code checks them, so granting them does "
                + "nothing: " + unused
                + " — implement them, remove them from plugin.yml, or add them to "
                + "DECLARED_WITHOUT_A_LITERAL with the reason they are still honest");
    }

    @Test
    void theTwoPermissionsThatAdvertisedNothingAreGone() throws Exception {
        String descriptor = Files.readString(
            Path.of("src/main/resources/plugin.yml"), StandardCharsets.UTF_8
        );
        String java = allJavaSource();

        assertTrue(!descriptor.contains("itemguard.restore"),
            "itemguard.restore promised a restore command; the reclaim path uses itemguard.matdo "
                + "and itemguard.giveoldid instead, so the promise has to go");
        assertTrue(!descriptor.contains("itemguard.teleport"),
            "itemguard.teleport promised teleport-to-container, which no code implements");
        assertTrue(!java.contains("\"itemguard.teleport\""),
            "no Java source checks that node; the word itself still appears in LITE's jump javadoc, "
                + "which is why this asserts the node and not the word");
    }

    @Test
    void theIssuancePermissionAndTheAlertPermissionAreDeclared() throws Exception {
        Set<String> declared = declaredPermissions();

        assertTrue(declared.contains("itemguard.giveoldid"),
            "handing an item back needs its own node, not the read-only admin one");
        assertTrue(declared.contains("itemguard.notify"),
            "the alert node must be declared, or a non-op staff member can never receive one");
        assertTrue(!declared.contains("itemguard.bypass"),
            "itemguard.bypass promised 'bypass anti-dupe checks' and the only code that ever read it "
                + "used it to route alerts; with that corrected nothing checks it, and this plugin's "
                + "own audit calls a permission that does nothing the most damaging line on a "
                + "listing. It returns with the transfer-restriction work that needs it.");
    }

    @Test
    void theFullCommandsCarryEnglishAliasesForAnInternationalListing() throws Exception {
        String descriptor = Files.readString(
            Path.of("src/main/resources/plugin.yml"), StandardCharsets.UTF_8
        );

        assertTrue(descriptor.contains("itemsearch"),
            "/finditem needs an English alias: the command names are Vietnamese on an English listing");
        assertTrue(descriptor.contains("itemreturn"),
            "/matdo needs an English alias for the same reason");
    }
}
