package com.itemguard.config;

import java.util.Locale;

/**
 * Which message file an edition is allowed to load.
 *
 * <p>LITE ships English only: the packaging gate removes {@code messages.yml} from the jar. That
 * gate cannot see a config value, and a LITE server asking for {@code vi} used to fall through to
 * writing a fresh Vietnamese {@code messages.yml} back onto disk — the exact file the gate had just
 * removed. The edition decides; the config file does not get to contradict it.
 *
 * <p>Deliberately not an error for an unknown value: both editions have degraded quietly on a
 * misspelled language since before this was written, and turning that into a startup failure would
 * take a server down over a typo in a display setting.
 */
public final class MessageLanguagePolicy {

    public static final String ENGLISH = "en";
    public static final String VIETNAMESE = "vi";

    public String resolve(String requested, boolean liteEdition) {
        if (liteEdition) {
            return ENGLISH;
        }
        if (requested == null) {
            return VIETNAMESE;
        }
        return switch (requested.trim().toLowerCase(Locale.ROOT)) {
            case ENGLISH -> ENGLISH;
            default -> VIETNAMESE;
        };
    }
}
