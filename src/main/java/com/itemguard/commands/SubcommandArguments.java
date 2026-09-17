package com.itemguard.commands;

import java.util.Arrays;

public final class SubcommandArguments {

    private SubcommandArguments() {}

    public static String[] tail(String[] arguments) {
        if (arguments == null || arguments.length <= 1) {
            return new String[0];
        }
        return Arrays.copyOfRange(arguments, 1, arguments.length);
    }
}
