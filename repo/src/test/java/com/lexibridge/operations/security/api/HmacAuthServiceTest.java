package com.lexibridge.operations.security.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HmacAuthServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    void authenticate_shouldRejectExpiredTimestamp() {
        HmacAuthService service = new HmacAuthService(jdbcTemplate);

        Optional<String> result = service.authenticate(
            "device-a",
            1,
            Instant.now().minusSeconds(601).getEpochSecond(),
            "nonce-1",
            "sig",
            "POST",
            "/api/v1/payments/callbacks",
            "",
            hash("")
        );

        assertTrue(result.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void authenticate_shouldRejectNonceReplay() {
        HmacAuthService service = new HmacAuthService(jdbcTemplate);
        byte[] secret = "secret-v1".getBytes(StandardCharsets.UTF_8);
        long timestamp = Instant.now().getEpochSecond();
        String nonce = "nonce-replay";
        String signature = sign(secret, "POST", "/api/v1/payments/callbacks", "", hash("{\"ok\":true}"), timestamp, nonce);

        when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class), eq("device-a"), eq(1)))
            .thenReturn(Optional.of(secret));
        when(jdbcTemplate.update(anyString(), eq("device-a"), eq(nonce), any()))
            .thenThrow(new DuplicateKeyException("duplicate nonce"));

        Optional<String> result = service.authenticate(
            "device-a",
            1,
            timestamp,
            nonce,
            signature,
            "POST",
            "/api/v1/payments/callbacks",
            "",
            hash("{\"ok\":true}")
        );

        assertTrue(result.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void authenticate_shouldAcceptRotatedActiveKeyVersions() {
        HmacAuthService service = new HmacAuthService(jdbcTemplate);
        byte[] secretV1 = "secret-v1".getBytes(StandardCharsets.UTF_8);
        byte[] secretV2 = "secret-v2".getBytes(StandardCharsets.UTF_8);
        long timestamp = Instant.now().getEpochSecond();

        when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class), eq("device-a"), eq(1)))
            .thenReturn(Optional.of(secretV1));
        when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class), eq("device-a"), eq(2)))
            .thenReturn(Optional.of(secretV2));
        when(jdbcTemplate.update(anyString(), eq("device-a"), anyString(), any())).thenReturn(1);

        String signatureV1 = sign(secretV1, "GET", "/api/v1/content/summary", "", hash(""), timestamp, "nonce-v1");
        String signatureV2 = sign(secretV2, "GET", "/api/v1/content/summary", "", hash(""), timestamp, "nonce-v2");

        Optional<String> version1 = service.authenticate(
            "device-a",
            1,
            timestamp,
            "nonce-v1",
            signatureV1,
            "GET",
            "/api/v1/content/summary",
            "",
            hash("")
        );
        Optional<String> version2 = service.authenticate(
            "device-a",
            2,
            timestamp,
            "nonce-v2",
            signatureV2,
            "GET",
            "/api/v1/content/summary",
            "",
            hash("")
        );

        assertEquals(Optional.of("device-a"), version1);
        assertEquals(Optional.of("device-a"), version2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void authenticate_shouldRejectUnknownKeyVersion() {
        HmacAuthService service = new HmacAuthService(jdbcTemplate);
        long timestamp = Instant.now().getEpochSecond();

        when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class), eq("device-a"), eq(9)))
            .thenReturn(Optional.empty());

        Optional<String> result = service.authenticate(
            "device-a",
            9,
            timestamp,
            "nonce-unknown",
            "sig",
            "GET",
            "/api/v1/content/summary",
            "",
            hash("")
        );

        assertTrue(result.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void authenticate_shouldRejectTamperedBodyHash() {
        HmacAuthService service = new HmacAuthService(jdbcTemplate);
        byte[] secret = "secret-v1".getBytes(StandardCharsets.UTF_8);
        long timestamp = Instant.now().getEpochSecond();
        String nonce = "nonce-body";
        String signature = sign(secret, "POST", "/api/v1/payments/callbacks", "", hash("{\"amount\":10}"), timestamp, nonce);

        when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class), eq("device-a"), eq(1)))
            .thenReturn(Optional.of(secret));

        Optional<String> result = service.authenticate(
            "device-a",
            1,
            timestamp,
            nonce,
            signature,
            "POST",
            "/api/v1/payments/callbacks",
            "",
            hash("{\"amount\":11}")
        );

        assertTrue(result.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void authenticate_shouldRejectTamperedQueryString() {
        HmacAuthService service = new HmacAuthService(jdbcTemplate);
        byte[] secret = "secret-v1".getBytes(StandardCharsets.UTF_8);
        long timestamp = Instant.now().getEpochSecond();
        String nonce = "nonce-query";
        String signature = sign(secret, "GET", "/api/v1/content/summary", "a=1&z=2", hash(""), timestamp, nonce);

        when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class), eq("device-a"), eq(1)))
            .thenReturn(Optional.of(secret));

        Optional<String> result = service.authenticate(
            "device-a",
            1,
            timestamp,
            nonce,
            signature,
            "GET",
            "/api/v1/content/summary",
            "a=1&z=3",
            hash("")
        );

        assertTrue(result.isEmpty());
    }

    private String sign(byte[] secret,
                        String method,
                        String path,
                        String canonicalQuery,
                        String bodySha256,
                        long timestamp,
                        String nonce) {
        try {
            String payload = method + "|" + path + "|" + canonicalQuery + "|" + bodySha256 + "|" + timestamp + "|" + nonce;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String hash(String body) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
