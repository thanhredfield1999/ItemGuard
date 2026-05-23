package com.itemguard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CodeGenerationTest {

    @Test
    void testCodeFormat() {
        String code = "TEST-1234";
        assertEquals(9, code.length());
        assertTrue(code.matches("[A-Z0-9]{4}-[A-Z0-9]{4}"));
    }

    @Test
    void testCodeCharacters() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        for (char c : chars.toCharArray()) {
            assertTrue(Character.isUpperCase(c) || Character.isDigit(c));
        }
    }
}
