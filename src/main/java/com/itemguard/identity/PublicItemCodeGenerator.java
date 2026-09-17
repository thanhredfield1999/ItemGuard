package com.itemguard.identity;

import java.util.Random;
import java.util.Objects;

/** Public display codes only. Database reservation, not random generation, establishes uniqueness. */
public final class PublicItemCodeGenerator {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private final Random random;

    public PublicItemCodeGenerator(Random random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    public String generate() {
        var code = new StringBuilder(6);
        for (int position = 0; position < 6; position++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }
}
