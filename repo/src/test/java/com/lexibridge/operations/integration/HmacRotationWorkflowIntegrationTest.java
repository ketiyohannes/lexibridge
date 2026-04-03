package com.lexibridge.operations.integration;

import com.lexibridge.operations.modules.admin.service.DeviceHmacKeyRotationService;
import com.lexibridge.operations.security.api.HmacAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "spring.task.scheduling.enabled=false",
    "lexibridge.security.antivirus.enabled=false"
})
class HmacRotationWorkflowIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
        .withDatabaseName("lexibridge")
        .withUsername("lexibridge")
        .withPassword("lexibridge")
        .withCommand("--log-bin-trust-function-creators=1");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DeviceHmacKeyRotationService rotationService;

    @Autowired
    private HmacAuthService hmacAuthService;

    @Test
    void rotateAndCutover_shouldSupportOverlapThenDeactivateOldKey() {
        long actorUserId = createUser();
        String clientKey = "device-" + UUID.randomUUID().toString().substring(0, 8);
        long clientId = createDeviceClient(clientKey);

        String secretV1 = "very-strong-secret-v1-000000000000";
        insertSecret(clientId, 1, secretV1, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30), true);

        assertAuthenticated(clientKey, 1, secretV1, "nonce-v1-before-1");

        String secretV2 = "very-strong-secret-v2-111111111111";
        Map<String, Object> rotateResult = rotationService.rotate(
            clientKey,
            secretV2,
            20,
            "Routine rotation window",
            actorUserId
        );
        int newVersion = ((Number) rotateResult.get("newKeyVersion")).intValue();
        assertEquals(2, newVersion);

        assertAuthenticated(clientKey, 1, secretV1, "nonce-v1-overlap-2");
        assertAuthenticated(clientKey, 2, secretV2, "nonce-v2-overlap-3");

        Map<String, Object> cutoverResult = rotationService.cutover(
            clientKey,
            2,
            "Client switched to key v2",
            actorUserId
        );
        assertEquals("CUTOVER_COMPLETE", cutoverResult.get("status"));

        Optional<String> oldAfterCutover = authenticate(clientKey, 1, secretV1, "nonce-v1-after-4");
        assertTrue(oldAfterCutover.isEmpty());

        assertAuthenticated(clientKey, 2, secretV2, "nonce-v2-after-5");

        Integer rotateAuditCount = jdbcTemplate.queryForObject(
            "select count(*) from audit_log where event_type = 'DEVICE_HMAC_KEY_ROTATED' and entity_type = 'device_client' and entity_id = ?",
            Integer.class,
            String.valueOf(clientId)
        );
        Integer cutoverAuditCount = jdbcTemplate.queryForObject(
            "select count(*) from audit_log where event_type = 'DEVICE_HMAC_KEY_CUTOVER' and entity_type = 'device_client' and entity_id = ?",
            Integer.class,
            String.valueOf(clientId)
        );
        assertEquals(1, rotateAuditCount);
        assertEquals(1, cutoverAuditCount);
    }

    @Test
    void rotate_shouldRejectWeakSharedSecret() {
        long actorUserId = createUser();
        String clientKey = "device-" + UUID.randomUUID().toString().substring(0, 8);
        long clientId = createDeviceClient(clientKey);
        insertSecret(clientId, 1, "very-strong-secret-v1-000000000000", LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30), true);

        assertThrows(
            IllegalArgumentException.class,
            () -> rotationService.rotate(
                clientKey,
                "too-short",
                15,
                "Routine rotation window",
                actorUserId
            )
        );
    }

    private Optional<String> authenticate(String clientKey, int keyVersion, String secret, String nonce) {
        long ts = Instant.now().getEpochSecond();
        String bodyHash = sha256Hex("{}");
        String signature = sign(secret, "POST", "/api/v1/payments/callbacks", "", bodyHash, ts, nonce);
        return hmacAuthService.authenticate(
            clientKey,
            keyVersion,
            ts,
            nonce,
            signature,
            "POST",
            "/api/v1/payments/callbacks",
            "",
            bodyHash
        );
    }

    private void assertAuthenticated(String clientKey, int keyVersion, String secret, String nonce) {
        Optional<String> result = authenticate(clientKey, keyVersion, secret, nonce);
        assertEquals(Optional.of(clientKey), result);
    }

    private long createUser() {
        String username = "hmac-actor-" + UUID.randomUUID().toString().substring(0, 8);
        jdbcTemplate.update(
            "insert into app_user (location_id, username, full_name, password_hash, is_active) values (1, ?, 'HMAC Actor', 'x', true)",
            username
        );
        Long id = jdbcTemplate.queryForObject("select id from app_user where username = ?", Long.class, username);
        if (id == null) {
            throw new IllegalStateException("Could not create actor user");
        }
        return id;
    }

    private long createDeviceClient(String clientKey) {
        jdbcTemplate.update(
            "insert into device_client (location_id, client_key, display_name, status) values (1, ?, 'Integration Device', 'ACTIVE')",
            clientKey
        );
        Long id = jdbcTemplate.queryForObject("select id from device_client where client_key = ?", Long.class, clientKey);
        if (id == null) {
            throw new IllegalStateException("Could not create device client");
        }
        return id;
    }

    private void insertSecret(long clientId,
                              int keyVersion,
                              String secret,
                              LocalDateTime validFrom,
                              LocalDateTime validTo,
                              boolean active) {
        jdbcTemplate.update(
            """
            insert into hmac_secret (client_type, client_ref_id, key_version, shared_secret, valid_from, valid_to, is_active)
            values ('DEVICE', ?, ?, ?, ?, ?, ?)
            """,
            clientId,
            keyVersion,
            secret.getBytes(StandardCharsets.UTF_8),
            validFrom,
            validTo,
            active
        );
    }

    private String sign(String secret,
                        String method,
                        String path,
                        String canonicalQuery,
                        String bodySha,
                        long timestamp,
                        String nonce) {
        try {
            String payload = method + "|" + path + "|" + canonicalQuery + "|" + bodySha + "|" + timestamp + "|" + nonce;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to sign HMAC payload", ex);
        }
    }

    private String sha256Hex(String body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to hash payload", ex);
        }
    }

}
