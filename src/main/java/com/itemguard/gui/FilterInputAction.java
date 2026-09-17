package com.itemguard.gui;

public sealed interface FilterInputAction {

    FilterInputAction CANCEL = new Cancel();
    FilterInputAction SHOW_ALL = new ShowAll();

    record Cancel() implements FilterInputAction {}

    record ShowAll() implements FilterInputAction {}

    record Apply(String filter) implements FilterInputAction {}
}
