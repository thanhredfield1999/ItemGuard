package com.itemguard.identity;

import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class PublicItemCodeGeneratorTest {
    @Test
    void generatesSixUppercaseAlphanumericCharactersFromInjectedRandom() {
        var generator = new PublicItemCodeGenerator(new Random(42));
        var expectedRandom = new Random(42);
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        for (int sample = 0; sample < 256; sample++) {
            var expected = new StringBuilder();
            for (int position = 0; position < 6; position++) expected.append(alphabet.charAt(expectedRandom.nextInt(36)));
            String actual = generator.generate();
            assertEquals(expected.toString(), actual);
            assertTrue(actual.matches("[A-Z0-9]{6}"));
        }
    }
}
