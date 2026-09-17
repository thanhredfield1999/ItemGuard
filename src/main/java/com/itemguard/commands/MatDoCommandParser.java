package com.itemguard.commands;

import java.util.Locale;

public final class MatDoCommandParser {

    public MatDoCommandAction parse(String[] arguments) {
        if (arguments == null || arguments.length == 0) {
            throw new IllegalArgumentException("Su dung /matdo <check|sos>");
        }
        return switch (arguments[0].toLowerCase(Locale.ROOT)) {
            case "check" -> {
                requireLength(arguments, 1, "Su dung /matdo check");
                yield MatDoCommandAction.Check.INSTANCE;
            }
            case "sos" -> {
                requireLength(arguments, 2, "Su dung /matdo sos <id>");
                yield new MatDoCommandAction.Sos(ItemCodeInput.normalize(arguments[1]));
            }
            default -> throw new IllegalArgumentException("Su dung /matdo <check|sos>");
        };
    }

    private void requireLength(String[] arguments, int expected, String message) {
        if (arguments.length != expected) {
            throw new IllegalArgumentException(message);
        }
    }
}
