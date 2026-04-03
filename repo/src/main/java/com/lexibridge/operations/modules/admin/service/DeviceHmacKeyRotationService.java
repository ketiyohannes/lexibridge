package com.lexibridge.operations.modules.admin.service;

import com.lexibridge.operations.governance.AuditLogService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class DeviceHmacKeyRotationService {

    private static final int MIN_SECRET_LENGTH = 32;
    private static final int MIN_OVERLAP_MINUTES = 1;
    private static final int MAX_OVERLAP_MINUTES = 24 * 60;

    private final JdbcTemplate jdbcTemplate;
    private final AuditLogService auditLogService;

    public DeviceHmacKeyRotationService(JdbcTemplate jdbcTemplate,
                                        AuditLogService auditLogService) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditLogService = auditLogService;
    }

    public List<Map<String, Object>> keyInventory(String clientKey) {
        DeviceClient deviceClient = requireActiveDeviceClient(clientKey);
        return jdbcTemplate.queryForList(
            """
            select key_version, is_active, valid_from, valid_to, created_at
            from hmac_secret
            where client_type = 'DEVICE'
              and client_ref_id = ?
            order by key_version desc
            """,
            deviceClient.id()
        );
    }

    @Transactional
    public Map<String, Object> rotate(String clientKey,
                                      String newSharedSecret,
                                      int overlapMinutes,
                                      String reason,
                                      long actorUserId) {
        DeviceClient deviceClient = requireActiveDeviceClient(clientKey);
        validateSharedSecret(newSharedSecret);
        validateReason(reason);
        if (overlapMinutes < MIN_OVERLAP_MINUTES || overlapMinutes > MAX_OVERLAP_MINUTES) {
            throw new IllegalArgumentException("overlapMinutes must be between 1 and 1440.");
        }

        int nextVersion = jdbcTemplate.queryForObject(
            """
            select coalesce(max(key_version), 0) + 1
            from hmac_secret
            where client_type = 'DEVICE'
              and client_ref_id = ?
            """,
            Integer.class,
            deviceClient.id()
        );

        LocalDateTime overlapEndsAt = jdbcTemplate.queryForObject(
            "select timestampadd(minute, ?, current_timestamp)",
            LocalDateTime.class,
            overlapMinutes
        );

        jdbcTemplate.update(
            """
            insert into hmac_secret (client_type, client_ref_id, key_version, shared_secret, valid_from, valid_to, is_active)
            values ('DEVICE', ?, ?, ?, date_sub(current_timestamp, interval 1 second), timestampadd(year, 1, current_timestamp), true)
            """,
            deviceClient.id(),
            nextVersion,
            newSharedSecret.getBytes(StandardCharsets.UTF_8)
        );

        jdbcTemplate.update(
            """
            update hmac_secret
            set valid_to = least(valid_to, timestampadd(minute, ?, current_timestamp))
            where client_type = 'DEVICE'
              and client_ref_id = ?
              and key_version <> ?
              and is_active = true
              and valid_to > current_timestamp
            """,
            overlapMinutes,
            deviceClient.id(),
            nextVersion
        );

        auditLogService.logUserEvent(
            actorUserId,
            "DEVICE_HMAC_KEY_ROTATED",
            "device_client",
            String.valueOf(deviceClient.id()),
            deviceClient.locationId(),
            Map.of(
                "clientKey", clientKey,
                "newKeyVersion", nextVersion,
                "overlapMinutes", overlapMinutes,
                "overlapEndsAt", overlapEndsAt.toString(),
                "reason", reason.trim()
            )
        );

        return Map.of(
            "clientKey", clientKey,
            "newKeyVersion", nextVersion,
            "overlapMinutes", overlapMinutes,
            "overlapEndsAt", overlapEndsAt,
            "status", "ROTATED"
        );
    }

    @Transactional
    public Map<String, Object> cutover(String clientKey,
                                       int activeKeyVersion,
                                       String reason,
                                       long actorUserId) {
        DeviceClient deviceClient = requireActiveDeviceClient(clientKey);
        validateReason(reason);

        Integer selectedCount = jdbcTemplate.queryForObject(
            """
            select count(*)
            from hmac_secret
            where client_type = 'DEVICE'
              and client_ref_id = ?
              and key_version = ?
              and is_active = true
              and valid_from <= current_timestamp
              and valid_to >= current_timestamp
            """,
            Integer.class,
            deviceClient.id(),
            activeKeyVersion
        );
        if (selectedCount == null || selectedCount == 0) {
            throw new IllegalArgumentException("Requested activeKeyVersion is not currently active/valid.");
        }

        int deactivated = jdbcTemplate.update(
            """
            update hmac_secret
            set is_active = false,
                valid_to = least(valid_to, current_timestamp)
            where client_type = 'DEVICE'
              and client_ref_id = ?
              and key_version <> ?
              and is_active = true
            """,
            deviceClient.id(),
            activeKeyVersion
        );

        auditLogService.logUserEvent(
            actorUserId,
            "DEVICE_HMAC_KEY_CUTOVER",
            "device_client",
            String.valueOf(deviceClient.id()),
            deviceClient.locationId(),
            Map.of(
                "clientKey", clientKey,
                "activeKeyVersion", activeKeyVersion,
                "deactivatedCount", deactivated,
                "reason", reason.trim()
            )
        );

        return Map.of(
            "clientKey", clientKey,
            "activeKeyVersion", activeKeyVersion,
            "deactivatedCount", deactivated,
            "status", "CUTOVER_COMPLETE"
        );
    }

    private DeviceClient requireActiveDeviceClient(String clientKey) {
        DeviceClient deviceClient = jdbcTemplate.query(
            """
            select id, location_id
            from device_client
            where client_key = ?
              and status = 'ACTIVE'
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                return new DeviceClient(rs.getLong("id"), (Long) rs.getObject("location_id"));
            },
            clientKey
        );
        if (deviceClient == null) {
            throw new IllegalArgumentException("Active device client not found for clientKey: " + clientKey);
        }
        return deviceClient;
    }

    private void validateReason(String reason) {
        if (reason == null || reason.isBlank() || reason.trim().length() < 8) {
            throw new IllegalArgumentException("Rotation/cutover reason is required and must be at least 8 characters.");
        }
    }

    private void validateSharedSecret(String sharedSecret) {
        if (sharedSecret == null || sharedSecret.isBlank()) {
            throw new IllegalArgumentException("A non-empty shared secret is required.");
        }
        if (sharedSecret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalArgumentException("Shared secret must be at least 32 characters.");
        }
        if ("demo-device-shared-secret".equals(sharedSecret) || "local-dev-shared-secret-2026".equals(sharedSecret)) {
            throw new IllegalArgumentException("Shared secret uses a known insecure default and must be rotated.");
        }
    }

    private record DeviceClient(long id, Long locationId) {
    }
}
