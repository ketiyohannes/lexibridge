package com.lexibridge.operations.storage.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Map;

@Repository
public class BinaryStorageRepository {

    private final JdbcTemplate jdbcTemplate;

    public BinaryStorageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void upsert(String storagePath, String checksumSha256, String mimeType, long sizeBytes, byte[] payload) {
        jdbcTemplate.update(
            """
            insert into binary_storage (storage_path, checksum_sha256, mime_type, file_size_bytes, payload)
            values (?, ?, ?, ?, ?)
            on duplicate key update checksum_sha256 = values(checksum_sha256),
                                    mime_type = values(mime_type),
                                    file_size_bytes = values(file_size_bytes),
                                    payload = values(payload)
            """,
            storagePath,
            checksumSha256,
            mimeType,
            sizeBytes,
            payload
        );
    }

    public Map<String, Object> find(String storagePath) {
        return jdbcTemplate.query(
            """
            select storage_path, checksum_sha256, mime_type, file_size_bytes, payload
            from binary_storage
            where storage_path = ?
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                return Map.of(
                    "storage_path", rs.getString("storage_path"),
                    "checksum_sha256", rs.getString("checksum_sha256"),
                    "mime_type", rs.getString("mime_type"),
                    "file_size_bytes", rs.getLong("file_size_bytes"),
                    "payload", rs.getBytes("payload")
                );
            },
            storagePath
        );
    }
}
