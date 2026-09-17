package com.itemguard.catalog;

import java.util.List;
import java.util.Map;

/** Server-owned actions; display data never supplies commands or item identity. */
public record CatalogScreen(String title, Map<Integer, Icon> icons) {
    public CatalogScreen { icons = Map.copyOf(icons); }
    public record Icon(String material, String title, List<String> lore, Runnable action) {
        public Icon { lore = List.copyOf(lore); }
    }
}
