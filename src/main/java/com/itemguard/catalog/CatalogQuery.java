package com.itemguard.catalog;

public record CatalogQuery(String text, String ownerName, CatalogCategory category, String afterCode) {
    public CatalogQuery {
        validate(text, 64);
        validate(ownerName, 16);
        validate(afterCode, 16);
        java.util.Objects.requireNonNull(category, "category");
    }

    private static void validate(String value, int max) {
        java.util.Objects.requireNonNull(value, "value");
        if (value.length() > max || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid catalog input length or control characters");
        }
    }
}
