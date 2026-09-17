package com.itemguard.catalog;

public enum CatalogCategory {
    ALL, SWORD, ARMOR, TOOLS, RANGED, OTHER;

    String predicate() {
        String sword = "material GLOB '*_SWORD'";
        String armor = "(material GLOB '*_HELMET' OR material GLOB '*_CHESTPLATE' OR material GLOB '*_LEGGINGS' OR material GLOB '*_BOOTS' OR material = 'ELYTRA')";
        String tools = "(material GLOB '*_AXE' OR material GLOB '*_PICKAXE' OR material GLOB '*_SHOVEL' OR material GLOB '*_HOE' OR material IN ('SHEARS','FISHING_ROD','FLINT_AND_STEEL'))";
        String ranged = "(material IN ('BOW','CROSSBOW','TRIDENT') OR material GLOB '*_SPEAR')";
        return switch (this) {
            case ALL -> "1=1";
            case SWORD -> sword;
            case ARMOR -> armor;
            case TOOLS -> tools;
            case RANGED -> ranged;
            case OTHER -> "NOT COALESCE((" + sword + " OR " + armor + " OR " + tools + " OR " + ranged + "),0)";
        };
    }
}
