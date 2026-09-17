package com.itemguard;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * H5 (review 2026-09-16), second half: an admin could put LITE back into Vietnamese.
 *
 * <p>The English-only packaging gate removes {@code messages.yml} from the jar. Nothing stopped the
 * config from asking for {@code vi} anyway, and then the loader wrote a fresh Vietnamese
 * {@code messages.yml} back onto disk — the exact file the gate had just removed, and the exact
 * scenario the gate's own comment says it exists to prevent. The edition decides which message
 * files exist; the config file does not get to contradict it.
 */
class ConfigManagerLanguageTest {

    @Test
    void liteEditionCannotBeSwitchedToVietnamese() {
        assertEquals(
            "en",
            language(true, "vi"),
            "LITE ships English only; a config value must not resurrect the removed Vietnamese file"
        );
    }

    @Test
    void liteEditionDefaultsToEnglishWhenUnset() {
        assertEquals("en", language(true, null));
    }

    @Test
    void liteEditionRefusesAnUnknownLanguage() {
        assertEquals("en", language(true, "de"));
    }

    @Test
    void fullEditionStillHonoursVietnamese() {
        assertEquals(
            "vi",
            language(false, "vi"),
            "Vietnamese remains a FULL feature; this fix must not remove it"
        );
    }

    @Test
    void fullEditionDefaultsToVietnameseWhenUnset() {
        assertEquals("vi", language(false, null));
    }

    @Test
    void fullEditionAcceptsEnglish() {
        assertEquals("en", language(false, "en"));
    }

    private String language(boolean liteEdition, String configured) {
        ItemGuard plugin = mock(ItemGuard.class);
        when(plugin.isLiteEdition()).thenReturn(liteEdition);
        YamlConfiguration yaml = new YamlConfiguration();
        if (configured != null) {
            yaml.set("general.language", configured);
        }
        when(plugin.getConfig()).thenReturn(yaml);
        return new ConfigManager(plugin).getLanguage();
    }
}
