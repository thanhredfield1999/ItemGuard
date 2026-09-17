package com.itemguard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CodeGenerationTest {

    @Test
    void testCodeFormat() {
        String code = new com.itemguard.identity.PublicItemCodeGenerator(new java.util.Random(42)).generate();
        assertEquals(6, code.length());
        assertTrue(code.matches("[A-Z0-9]{6}"));
    }

    @Test
    void testCodeCharacters() {
        String chars = new com.itemguard.identity.PublicItemCodeGenerator(new java.security.SecureRandom()).generate();
        for (char c : chars.toCharArray()) {
            assertTrue(Character.isUpperCase(c) || Character.isDigit(c));
        }
    }
}
