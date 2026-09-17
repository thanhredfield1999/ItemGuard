package com.itemguard;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * H5 (review 2026-09-16): the LITE jar ships no Vietnamese files, but it was shipping Vietnamese
 * *text* — {@code DEFAULT_MESSAGES} was a hardcoded Vietnamese map compiled into the class and used
 * as the fallback for every key a message file does not define. The two packaging gates only look
 * at resource file <em>names</em>, so nothing could see it. An English-only listing with Vietnamese
 * leaking out of the class on the first missing key is exactly the kind of claim the listing must
 * not make.
 *
 * <p>The fallback and {@code messages_en.yml} are now pinned to each other here: same key set, same
 * text. Adding a code-side message without adding it to the English file fails this test instead of
 * showing a player Vietnamese.
 */
class MessageManagerFallbackTest {

    @Test
    void hardcodedFallbackIsTheSameEnglishTextAsTheShippedFile() throws Exception {
        YamlConfiguration english = load("/messages_en.yml");
        Map<String, String> defaults = hardcodedDefaults();

        assertEquals(
            english.getKeys(false),
            defaults.keySet(),
            "every code-side default must exist in messages_en.yml, and vice versa"
        );
        for (String key : english.getKeys(false)) {
            assertEquals(
                english.getString(key),
                defaults.get(key),
                "fallback text for '" + key + "' must be the English text that ships, "
                    + "not a second copy that can drift or be written in another language"
            );
        }
    }

    /**
     * L3 (review 2026-09-17): only `messages_en.yml` was pinned, so a key deleted from the Vietnamese
     * file would have gone unnoticed until a FULL server running `language: vi` silently fell back to
     * English for it. Nothing else compares the three files.
     */
    @Test
    void everyShippedMessageFileCarriesTheSameKeys() throws Exception {
        var english = load("/messages_en.yml").getKeys(false);
        for (String resource : new String[] {"/messages.yml", "/messages_zh.yml"}) {
            assertEquals(
                english,
                load(resource).getKeys(false),
                resource + " must define exactly the same keys as messages_en.yml"
            );
        }
    }

    private YamlConfiguration load(String resource) throws Exception {
        try (InputStream input = getClass().getResourceAsStream(resource)) {
            assertNotNull(input, "missing test resource " + resource);
            return YamlConfiguration.loadConfiguration(
                new InputStreamReader(input, StandardCharsets.UTF_8)
            );
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> hardcodedDefaults() throws Exception {
        Field field = MessageManager.class.getDeclaredField("DEFAULT_MESSAGES");
        field.setAccessible(true);
        return (Map<String, String>) field.get(null);
    }
}
