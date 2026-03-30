package com.lexibridge.operations.security.privacy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DataClassificationServiceTest {

    private final DataClassificationService service = new DataClassificationService();

    @Test
    void sanitizePiiPhone_shouldStripUnsupportedCharacters() {
        assertEquals("+15551234", service.sanitizePiiPhone("+1 (555) 1234"));
    }

    @Test
    void validatePiiEnvelope_shouldRejectOversizedName() {
        String tooLong = "x".repeat(129);
        assertThrows(IllegalArgumentException.class, () -> service.validatePiiEnvelope(tooLong, "123"));
    }
}
