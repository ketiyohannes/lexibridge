package com.lexibridge.operations.security.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PasswordPolicyValidatorTest {

    private final PasswordPolicyValidator validator = new PasswordPolicyValidator();

    @Test
    void validateOrThrow_shouldPassForStrongPassword() {
        assertDoesNotThrow(() -> validator.validateOrThrow("StrongPass12!"));
    }

    @Test
    void validateOrThrow_shouldFailForShortPassword() {
        assertThrows(IllegalArgumentException.class, () -> validator.validateOrThrow("Aa1!short"));
    }

    @Test
    void validateOrThrow_shouldFailForMissingSpecialCharacter() {
        assertThrows(IllegalArgumentException.class, () -> validator.validateOrThrow("NoSpecial1234"));
    }
}
