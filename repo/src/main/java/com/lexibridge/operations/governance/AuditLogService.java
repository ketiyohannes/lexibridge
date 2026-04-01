package com.lexibridge.operations.governance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class AuditLogService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AuditLogService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void logUserEvent(long actorUserId,
                             String eventType,
                             String entityType,
                             String entityId,
                             Long locationId,
                             Map<String, Object> payload) {
        jdbcTemplate.update(
            """
            insert into audit_log
            (actor_user_id, actor_type, event_type, entity_type, entity_id, location_id, payload_json)
            values (?, 'USER', ?, ?, ?, ?, cast(? as json))
            """,
            actorUserId,
            eventType,
            entityType,
            entityId,
            locationId,
            toJson(payload)
        );
    }

    public void logSystemEvent(String eventType,
                               String entityType,
                               String entityId,
                               Long locationId,
                               Map<String, Object> payload) {
        jdbcTemplate.update(
            """
            insert into audit_log
            (actor_user_id, actor_type, event_type, entity_type, entity_id, location_id, payload_json)
            values (null, 'SYSTEM', ?, ?, ?, ?, cast(? as json))
            """,
            eventType,
            entityType,
            entityId,
            locationId,
            toJson(payload)
        );
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Could not serialize audit payload", e);
        }
    }
}
