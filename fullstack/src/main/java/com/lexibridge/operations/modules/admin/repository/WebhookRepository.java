package com.lexibridge.operations.modules.admin.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class WebhookRepository {

    private final JdbcTemplate jdbcTemplate;

    public WebhookRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long create(long locationId,
                       String name,
                       String callbackUrl,
                       String whitelistedIp,
                       String whitelistedCidr,
                       byte[] signingSecret) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                """
                insert into webhook_endpoint
                (location_id, name, callback_url, whitelisted_ip, whitelisted_cidr, signing_secret, is_active)
                values (?, ?, ?, ?, ?, ?, true)
                """,
                Statement.RETURN_GENERATED_KEYS
            );
            ps.setLong(1, locationId);
            ps.setString(2, name);
            ps.setString(3, callbackUrl);
            ps.setString(4, whitelistedIp);
            ps.setString(5, whitelistedCidr);
            ps.setBytes(6, signingSecret);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<String> findCallbackUrl(long webhookId) {
        return jdbcTemplate.query(
            "select callback_url from webhook_endpoint where id = ? and is_active = true",
            rs -> rs.next() ? Optional.of(rs.getString(1)) : Optional.empty(),
            webhookId
        );
    }

    public Optional<Map<String, Object>> findDeliveryPolicy(long webhookId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            """
            select callback_url, whitelisted_cidr
            from webhook_endpoint
            where id = ? and is_active = true
            """,
            webhookId
        );
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(rows.getFirst());
    }

    public List<Map<String, Object>> activeWebhooks() {
        return jdbcTemplate.queryForList(
            """
            select id, location_id, name, callback_url, whitelisted_ip, whitelisted_cidr, is_active, created_at
            from webhook_endpoint
            where is_active = true
            order by id desc
            """
        );
    }

    public List<Map<String, Object>> activeWebhooksByLocation(long locationId) {
        return jdbcTemplate.queryForList(
            """
            select id, location_id, name, callback_url, whitelisted_ip, whitelisted_cidr, signing_secret
            from webhook_endpoint
            where is_active = true and location_id = ?
            order by id asc
            """,
            locationId
        );
    }

    public Optional<Map<String, Object>> activeWebhookById(long webhookId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            """
            select id, location_id, name, callback_url, whitelisted_ip, whitelisted_cidr, signing_secret
            from webhook_endpoint
            where is_active = true and id = ?
            """,
            webhookId
        );
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(rows.getFirst());
    }

    public void createDeliveryAttempt(long webhookId,
                                      String eventType,
                                      int attemptNo,
                                      String status,
                                      Integer httpStatus,
                                      String responseBody,
                                      long durationMs) {
        jdbcTemplate.update(
            """
            insert into webhook_delivery_attempt
            (webhook_id, event_type, attempt_no, status, http_status, response_body, duration_ms)
            values (?, ?, ?, ?, ?, ?, ?)
            """,
            webhookId,
            eventType,
            attemptNo,
            status,
            httpStatus,
            responseBody,
            durationMs
        );
    }
}
