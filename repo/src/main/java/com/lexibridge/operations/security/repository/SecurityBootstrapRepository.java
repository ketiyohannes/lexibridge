package com.lexibridge.operations.security.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public class SecurityBootstrapRepository {

    private final JdbcTemplate jdbcTemplate;

    public SecurityBootstrapRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<Long> findUserIdByUsername(String username) {
        return jdbcTemplate.query(
            "select id from app_user where lower(username) = lower(?)",
            rs -> rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty(),
            username
        );
    }

    public long createAdminUser(String username, String fullName, String passwordHash) {
        jdbcTemplate.update(
            "insert into app_user (location_id, username, full_name, password_hash, is_active) values (1, ?, ?, ?, true)",
            username, fullName, passwordHash
        );
        return jdbcTemplate.queryForObject(
            "select id from app_user where lower(username) = lower(?)",
            Long.class,
            username
        );
    }

    public Optional<Long> findRoleIdByCode(String roleCode) {
        return jdbcTemplate.query(
            "select id from app_role where code = ?",
            rs -> rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty(),
            roleCode
        );
    }

    public void assignRole(long userId, long roleId) {
        jdbcTemplate.update(
            "insert ignore into app_user_role (user_id, role_id) values (?, ?)",
            userId, roleId
        );
    }

    public Optional<Long> findDeviceClientIdByKey(String clientKey) {
        return jdbcTemplate.query(
            "select id from device_client where client_key = ?",
            rs -> rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty(),
            clientKey
        );
    }

    public long createDeviceClient(String clientKey, String displayName) {
        jdbcTemplate.update(
            "insert into device_client (location_id, client_key, display_name, status) values (1, ?, ?, 'ACTIVE')",
            clientKey,
            displayName
        );
        return jdbcTemplate.queryForObject("select id from device_client where client_key = ?", Long.class, clientKey);
    }

    public void createHmacSecret(long clientRefId, int keyVersion, byte[] secretBytes) {
        jdbcTemplate.update(
            """
            insert into hmac_secret (client_type, client_ref_id, key_version, shared_secret, valid_from, valid_to, is_active)
            values ('DEVICE', ?, ?, ?, ?, ?, true)
            """,
            clientRefId,
            keyVersion,
            secretBytes,
            LocalDateTime.now().minusMinutes(1),
            LocalDateTime.now().plusYears(1)
        );
    }
}
