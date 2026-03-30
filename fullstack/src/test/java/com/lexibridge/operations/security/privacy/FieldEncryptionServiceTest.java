package com.lexibridge.operations.security.privacy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FieldEncryptionServiceTest {

    @Test
    void encrypt_shouldReturnDifferentPayloadThanPlaintext() {
        FieldEncryptionService service = new FieldEncryptionService("0123456789abcdef0123456789abcdef");
        byte[] plaintext = "secret-value".getBytes();
        byte[] encrypted = service.encrypt(plaintext);
        assertTrue(encrypted.length > plaintext.length);
        assertNotEquals(new String(plaintext), new String(encrypted));
    }
}
