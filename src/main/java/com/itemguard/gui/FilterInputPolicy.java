package com.itemguard.gui;

public final class FilterInputPolicy {

    public FilterInputAction resolve(String input) {
        String normalized = input == null ? "" : input.trim();
        if (normalized.equalsIgnoreCase("Huy")) {
            return FilterInputAction.CANCEL;
        }
        if (normalized.isEmpty() || normalized.equals("*")) {
            return FilterInputAction.SHOW_ALL;
        }
        return new FilterInputAction.Apply(normalized);
    }
}
