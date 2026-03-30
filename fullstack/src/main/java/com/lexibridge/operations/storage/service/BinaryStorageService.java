package com.lexibridge.operations.storage.service;

import com.lexibridge.operations.storage.repository.BinaryStorageRepository;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

@Service
public class BinaryStorageService {

    private final BinaryStorageRepository binaryStorageRepository;

    public BinaryStorageService(BinaryStorageRepository binaryStorageRepository) {
        this.binaryStorageRepository = binaryStorageRepository;
    }

    public void store(String storagePath,
                      String checksumSha256,
                      String mimeType,
                      byte[] payload) {
        String actual = sha256(payload);
        if (!actual.equalsIgnoreCase(checksumSha256)) {
            throw new IllegalArgumentException("Checksum mismatch while storing binary payload.");
        }
        binaryStorageRepository.upsert(storagePath, checksumSha256, mimeType, payload.length, payload);
    }

    public DownloadedBinary read(String storagePath) {
        Map<String, Object> row = binaryStorageRepository.find(storagePath);
        if (row == null) {
            throw new IllegalArgumentException("Binary payload not found for path: " + storagePath);
        }
        byte[] payload = (byte[]) row.get("payload");
        String expected = String.valueOf(row.get("checksum_sha256"));
        String actual = sha256(payload);
        if (!actual.equalsIgnoreCase(expected)) {
            throw new IllegalStateException("Stored payload checksum mismatch.");
        }
        return new DownloadedBinary(
            String.valueOf(row.get("storage_path")),
            String.valueOf(row.get("mime_type")),
            expected,
            payload
        );
    }

    private String sha256(byte[] payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to calculate checksum", ex);
        }
    }

    public record DownloadedBinary(String storagePath, String mimeType, String checksumSha256, byte[] payload) {
    }
}
