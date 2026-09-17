package com.itemguard.commands;

import com.itemguard.search.ItemSearchMode;
import com.itemguard.search.SearchDurationParser;

import java.util.Locale;

public final class FindItemCommandParser {

    private final SearchDurationParser durationParser = new SearchDurationParser();

    public FindItemCommandAction parse(String[] arguments) {
        if (arguments == null || arguments.length == 0) {
            throw new IllegalArgumentException("Missing /finditem subcommand");
        }
        String subcommand = arguments[0].toLowerCase(Locale.ROOT);
        return switch (subcommand) {
            case "startfinding" -> parseStart(arguments, ItemSearchMode.FIND);
            case "starttaking" -> parseStart(arguments, ItemSearchMode.TAKE);
            case "stopfinding" -> new FindItemCommandAction.Stop(requiredCode(arguments));
            case "listfinding" -> new FindItemCommandAction.ListActive(parsePage(arguments));
            case "removefinding" -> new FindItemCommandAction.Remove(requiredCode(arguments));
            case "clearfinding" -> {
                if (arguments.length != 2 || !arguments[1].equalsIgnoreCase("confirm")) {
                    throw new IllegalArgumentException(
                        "Use /finditem clearfinding confirm"
                    );
                }
                yield FindItemCommandAction.ClearConfirmed.INSTANCE;
            }
            default -> throw new IllegalArgumentException(
                "Unsupported or unreleased /finditem subcommand"
            );
        };
    }

    private FindItemCommandAction.Start parseStart(
        String[] arguments,
        ItemSearchMode mode
    ) {
        if (arguments.length != 3) {
            throw new IllegalArgumentException(
                "Use /finditem " + arguments[0] + " <id> <time>"
            );
        }
        return new FindItemCommandAction.Start(
            normalizeCode(arguments[1]),
            mode,
            durationParser.parse(arguments[2])
        );
    }

    private String requiredCode(String[] arguments) {
        if (arguments.length != 2) {
            throw new IllegalArgumentException("Item code is required");
        }
        return normalizeCode(arguments[1]);
    }

    private int parsePage(String[] arguments) {
        if (arguments.length == 1) {
            return 1;
        }
        if (arguments.length != 2) {
            throw new IllegalArgumentException("List page is invalid");
        }
        try {
            int page = Integer.parseInt(arguments[1]);
            if (page < 1) {
                throw new IllegalArgumentException("List page must be at least 1");
            }
            return page;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("List page must be a number", invalid);
        }
    }

    private String normalizeCode(String code) {
        return ItemCodeInput.normalize(code);
    }
}
