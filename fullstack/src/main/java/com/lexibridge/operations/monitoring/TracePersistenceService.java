package com.lexibridge.operations.monitoring;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class TracePersistenceService {

    private final JdbcTemplate jdbcTemplate;

    public TracePersistenceService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void record(String traceId,
                       String spanId,
                       String method,
                       String path,
                       int statusCode,
                       long durationMs,
                       String source) {
        jdbcTemplate.update(
            """
            insert into trace_event (trace_id, span_id, method, path, status_code, duration_ms, source)
            values (?, ?, ?, ?, ?, ?, ?)
            """,
            traceId,
            spanId,
            method,
            path,
            statusCode,
            durationMs,
            source
        );
    }

    public List<Map<String, Object>> latest(int limit) {
        int effectiveLimit = limit <= 0 ? 100 : Math.min(limit, 500);
        return jdbcTemplate.queryForList(
            """
            select id, trace_id, span_id, method, path, status_code, duration_ms, source, created_at
            from trace_event
            order by id desc
            limit ?
            """,
            effectiveLimit
        );
    }
}
