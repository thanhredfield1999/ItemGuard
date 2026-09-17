package com.itemguard.lite;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * LITE must not touch Adventure (net.kyori) at startup.
 *
 * <p>Spigot does not bundle Adventure; Paper does. On 2026-09-15 a real Spigot 1.21.4 server
 * refused to enable the plugin with:
 *
 * <pre>
 * java.lang.NoClassDefFoundError: net/kyori/adventure/text/format/TextColor
 *     at com.itemguard.ItemGuard.registerListeners(ItemGuard.java:144)
 * </pre>
 *
 * <p>The cause was not that LITE uses Adventure — it does not. It was that
 * {@code registerListeners()} constructed {@code CatalogUi} unconditionally, and CatalogUi is
 * a FULL class whose static initialisation resolves Adventure colour constants. Merely naming
 * the class was enough to kill startup on a server without the library.
 *
 * <p>These tests pin the source-level rule rather than the symptom, because the symptom only
 * appears on a server we cannot boot inside a unit test.
 */
class LiteAdventureIsolationTest {

    private static final Path PLUGIN_MAIN =
        Path.of("src/main/java/com/itemguard/ItemGuard.java");

    private static String readPluginMain() {
        try {
            return Files.readString(PLUGIN_MAIN);
        } catch (Exception failure) {
            throw new IllegalStateException("cannot read " + PLUGIN_MAIN, failure);
        }
    }

    @Test
    @DisplayName("CatalogUi is only constructed when the edition is not LITE")
    void catalogUiIsGatedBehindEditionCheck() {
        String source = readPluginMain();
        int construction = source.indexOf("new CatalogUi(");
        assertTrue(construction > 0, "expected ItemGuard to construct CatalogUi somewhere");

        // The construction must sit inside a block guarded by the edition check. Look back a
        // short distance for the guard rather than parsing Java: the point is that a future
        // edit which moves the construction out of the guard fails this test loudly.
        String preceding = source.substring(Math.max(0, construction - 400), construction);
        assertTrue(
            preceding.contains("isLiteEdition()"),
            "new CatalogUi(...) must be guarded by isLiteEdition(); Spigot has no Adventure "
                + "and merely constructing CatalogUi throws NoClassDefFoundError at enable"
        );
    }

    @Test
    @DisplayName("no LITE runtime package imports net.kyori")
    void liteRuntimePackagesDoNotImportAdventure() {
        List<String> liteRuntime = List.of(
            "src/main/java/com/itemguard/lite",
            "src/main/java/com/itemguard/tasks",
            "src/main/java/com/itemguard/services",
            "src/main/java/com/itemguard/listeners",
            "src/main/java/com/itemguard/persistence",
            "src/main/java/com/itemguard/dupe",
            "src/main/java/com/itemguard/tracking"
        );

        for (String directory : liteRuntime) {
            Path root = Path.of(directory);
            if (!Files.isDirectory(root)) {
                continue;
            }
            List<String> offenders;
            try (var paths = Files.walk(root)) {
                offenders = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> {
                        try {
                            return Files.readString(path).contains("net.kyori");
                        } catch (Exception failure) {
                            return false;
                        }
                    })
                    .map(Path::toString)
                    .collect(Collectors.toList());
            } catch (Exception failure) {
                throw new IllegalStateException("cannot walk " + root, failure);
            }
            assertTrue(
                offenders.isEmpty(),
                "these files are on the LITE runtime path and import Adventure, which does "
                    + "not exist on Spigot: " + offenders
            );
        }
    }

    @Test
    @DisplayName("PlayerListener tolerates a null GUI listener")
    void playerListenerDoesNotAssumeGuiListenerExists() {
        String source;
        try {
            source = Files.readString(
                Path.of("src/main/java/com/itemguard/listeners/PlayerListener.java"));
        } catch (Exception failure) {
            throw new IllegalStateException("cannot read PlayerListener", failure);
        }

        // Once CatalogUi/GUIListener are skipped in LITE, getGuiListener() returns null, and
        // an unguarded call NPEs on every quit event.
        assertFalse(
            source.contains("plugin.getGuiListener().releasePlayer("),
            "PlayerListener must null-check getGuiListener(); in LITE the GUI stack is never "
                + "constructed, so an unguarded call throws on every player quit"
        );
    }
}
