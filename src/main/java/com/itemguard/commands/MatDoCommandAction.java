package com.itemguard.commands;

public sealed interface MatDoCommandAction {

    enum Check implements MatDoCommandAction {
        INSTANCE
    }

    record Sos(String code) implements MatDoCommandAction {}
}
