package com.itemguard;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M5 (review 2026-09-17): `general.enabled` was documented as "Enable/disable the entire plugin" and
 * read by nothing.
 *
 * <p>The first version of the guard returned early from {@code onEnable} *after* the old
 * {@code loadManagers()} call — which opens the SQLite file, takes the single-owner sidecar lock and
 * runs startup claim recovery. "Disabled" while still holding that lock is not what the key says, and
 * the log line that guard printed claimed no database work ran while the database was already open.
 * That is the defect class this project keeps meeting: code that does what it says while the sentence
 * beside it is false.
 *
 * <p>A source contract rather than a server test: what has to stay true is the *order* of startup,
 * and that is a property of this file. It asserts the split exists, that the database is constructed
 * on the far side of the switch, and that nothing is registered before it.
 */
class StartupEnableGuardContractTest {

    private static final String SOURCE = "src/main/java/com/itemguard/ItemGuard.java";

    @Test
    void theDatabaseIsBuiltAfterTheEnableSwitchIsRead() throws Exception {
        String source = Files.readString(Path.of(SOURCE));
        String onEnable = method(source, "public void onEnable()");

        int configHalf = onEnable.indexOf("loadConfigManagers();");
        int switchRead = onEnable.indexOf("configManager.isEnabled()");
        int runtimeHalf = onEnable.indexOf("loadRuntimeManagers();");
        int firstRegistration = onEnable.indexOf("registerHooks();");

        assertTrue(configHalf >= 0, "config must load first: it holds the switch");
        assertTrue(switchRead > configHalf, "the switch must be read after the config exists");
        assertTrue(runtimeHalf > switchRead,
            "the database and every collaborator must be built on the far side of the switch");
        assertTrue(firstRegistration > runtimeHalf, "nothing may register before that");

        String runtime = method(source, "private void loadRuntimeManagers()");
        assertTrue(runtime.contains("new DatabaseManager(this)"),
            "the database belongs to the runtime half");

        String config = method(source, "private void loadConfigManagers()");
        assertFalse(config.contains("new DatabaseManager(this)"),
            "opening the database in the config half would put it back in front of the switch");
        assertFalse(source.contains("loadManagers()"),
            "the old single entry point is what allowed the switch to be read too late");
    }

    @Test
    void theDisabledMessageDoesNotClaimMoreThanTheCodeDoes() throws Exception {
        String source = Files.readString(Path.of(SOURCE));
        String onEnable = method(source, "public void onEnable()");
        int switchRead = onEnable.indexOf("configManager.isEnabled()");
        String disabledMessage = onEnable.substring(switchRead, onEnable.indexOf("return;", switchRead));
        // The message is built from concatenated literals, so the phrase is split across the source.
        assertTrue(disabledMessage.contains("database file is not")
                && disabledMessage.contains("opened."),
            "the message must state what actually happens to the database");
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
