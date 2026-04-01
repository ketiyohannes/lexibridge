package com.lexibridge.operations.governance;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class RetentionService {

    private final JdbcTemplate jdbcTemplate;

    public RetentionService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int purgeExpiredAuditLogs() {
        return jdbcTemplate.update(
            """
            delete al
            from audit_log al
            left join retention_hold rh
              on rh.entity_type = 'audit_log' and rh.entity_id = cast(al.id as char) and rh.is_active = true
            where al.created_at < date_sub(current_timestamp, interval 7 year)
              and rh.id is null
            """
        );
    }

    public int purgeExpiredReconciliationExceptions() {
        return jdbcTemplate.update(
            """
            delete re
            from reconciliation_exception re
            left join retention_hold rh
              on rh.entity_type = 'reconciliation_exception' and rh.entity_id = cast(re.id as char) and rh.is_active = true
            where re.created_at < date_sub(current_timestamp, interval 7 year)
              and rh.id is null
            """
        );
    }
}
