package com.itemguard.commands;

import java.util.Locale;

public final class ItemCodeInput {

    private ItemCodeInput() {
    }

    public static String normalize(String input) {
        String normalized = input == null ? "" : input.trim();
        if (normalized.startsWith("#")) {
            normalized = normalized.substring(1).trim();
        }
        normalized = normalized.toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            // English, because this string is compiled into the LITE jar and LITE is English only.
            // It is caught by every caller today (LiteCommand shows its own message through the
            // language flag), so nothing printed it - but "caught today" is not a property a string
            // in a shipped jar should rely on, and the Vietnamese gate now scans this file for
            // exactly that reason: it sits in commands/ but LiteCommand imports it.
            throw new IllegalArgumentException("An item ID is required");
        }
        return normalized;
    }
}
