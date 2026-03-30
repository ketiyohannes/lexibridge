package com.lexibridge.operations.security.privacy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;
import java.nio.charset.StandardCharsets;

@Service
public class FieldEncryptionService {

    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;

    private final byte[] key;
    private final SecureRandom secureRandom = new SecureRandom();

    public FieldEncryptionService(@Value("${lexibridge.security.field-encryption-key:}") String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Field encryption key must be configured via FIELD_ENCRYPTION_KEY.");
        }
        if (key.length() != 32) {
            throw new IllegalArgumentException("Field encryption key must be exactly 32 characters.");
        }
        this.key = key.getBytes();
    }

    public byte[] encrypt(byte[] plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext);
            byte[] out = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(encrypted, 0, out, iv.length, encrypted.length);
            return Base64.getEncoder().encode(out);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt field", e);
        }
    }

    public byte[] decrypt(byte[] encodedPayload) {
        try {
            byte[] payload = Base64.getDecoder().decode(encodedPayload);
            if (payload.length <= IV_BYTES) {
                throw new IllegalArgumentException("Invalid encrypted payload");
            }
            byte[] iv = new byte[IV_BYTES];
            byte[] cipherText = new byte[payload.length - IV_BYTES];
            System.arraycopy(payload, 0, iv, 0, IV_BYTES);
            System.arraycopy(payload, IV_BYTES, cipherText, 0, cipherText.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return cipher.doFinal(cipherText);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt field", e);
        }
    }

    public String encryptString(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return "enc:" + new String(encrypt(value.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
    }

    public String decryptString(String encryptedValue) {
        if (encryptedValue == null || encryptedValue.isBlank()) {
            return null;
        }
        if (!encryptedValue.startsWith("enc:")) {
            return encryptedValue;
        }
        String payload = encryptedValue.substring(4);
        return new String(decrypt(payload.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
    }
}
