package com.itemguard.search;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SearchDurationParser {

    private static final Pattern FORMAT = Pattern.compile("^(\\d+)([smhd])$");
    private static final Duration MAXIMUM = Duration.ofDays(365);

    public Duration parse(String input) {
        if (input == null) {
            throw new IllegalArgumentException("Search duration is required");
        }
        Matcher matcher = FORMAT.matcher(input.trim().toLowerCase(Locale.ROOT));
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Duration must use s, m, h or d");
        }

        long amount;
        try {
            amount = Long.parseLong(matcher.group(1));
        } catch (NumberFormatException overflow) {
            throw new IllegalArgumentException("Search duration is too large", overflow);
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("Search duration must be positive");
        }

        Duration duration;
        try {
            duration = switch (matcher.group(2)) {
                case "s" -> Duration.ofSeconds(amount);
                case "m" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                case "d" -> Duration.ofDays(amount);
                default -> throw new IllegalArgumentException("Unsupported duration unit");
            };
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Search duration is too large", overflow);
        }

        if (duration.compareTo(MAXIMUM) > 0) {
            throw new IllegalArgumentException("Search duration cannot exceed 365 days");
        }
        return duration;
    }
}
