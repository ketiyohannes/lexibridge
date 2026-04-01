package com.lexibridge.operations.security.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class HmacAuthService {

    private static final long MAX_AGE_SECONDS = 300;
    private static final long CLOCK_SKEW_SECONDS = 60;

    private final JdbcTemplate jdbcTemplate;

    public HmacAuthService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<String> authenticate(String clientKey,
                                         int keyVersion,
                                         long timestamp,
                                         String nonce,
                                         String signature,
                                         String method,
                                         String path,
                                         String canonicalQuery,
                                         String bodySha256) {
        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - timestamp) > (MAX_AGE_SECONDS + CLOCK_SKEW_SECONDS)) {
            return Optional.empty();
        }

        Optional<byte[]> secretBytes = findActiveSecret(clientKey, keyVersion);
        if (secretBytes.isEmpty()) {
            return Optional.empty();
        }

        String payload = method + "|" + path + "|" + canonicalQuery + "|" + bodySha256 + "|" + timestamp + "|" + nonce;
        String expected = computeHmacHex(secretBytes.get(), payload);

        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8))) {
            return Optional.empty();
        }

        if (!registerNonce(clientKey, nonce)) {
            return Optional.empty();
        }

        return Optional.of(clientKey);
    }

    public int purgeExpiredNonces() {
        return jdbcTemplate.update("delete from nonce_replay_guard where expires_at <= current_timestamp");
    }

    private Optional<byte[]> findActiveSecret(String clientKey, int keyVersion) {
        return jdbcTemplate.query(
            """
            select hs.shared_secret
            from device_client dc
            join hmac_secret hs on hs.client_ref_id = dc.id and hs.client_type = 'DEVICE'
            where dc.client_key = ?
              and hs.key_version = ?
              and hs.is_active = true
              and hs.valid_from <= current_timestamp
              and hs.valid_to >= current_timestamp
            limit 1
            """,
            rs -> {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(rs.getBytes("shared_secret"));
            },
            clientKey,
            keyVersion
        );
    }

    private boolean registerNonce(String clientKey, String nonce) {
        try {
            jdbcTemplate.update(
                """
                insert into nonce_replay_guard (client_key, nonce, expires_at)
                values (?, ?, ?)
                """,
                clientKey,
                nonce,
                LocalDateTime.ofInstant(Instant.now().plusSeconds(MAX_AGE_SECONDS), ZoneOffset.UTC)
            );
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private String computeHmacHex(byte[] keyBytes, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(keyBytes, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to compute HMAC", ex);
        }
    }
}
