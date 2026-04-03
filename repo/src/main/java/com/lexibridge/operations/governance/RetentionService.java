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

    public int purgeExpiredAuditRedactionEvents() {
        return jdbcTemplate.update(
            """
            delete ared
            from audit_redaction_event ared
            left join retention_hold rh
              on rh.entity_type = 'audit_redaction_event' and rh.entity_id = cast(ared.id as char) and rh.is_active = true
            where ared.created_at < date_sub(current_timestamp, interval 7 year)
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

    public int purgeExpiredReconciliationRuns() {
        return jdbcTemplate.update(
            """
            delete rr
            from reconciliation_run rr
            left join retention_hold rh
              on rh.entity_type = 'reconciliation_run' and rh.entity_id = cast(rr.id as char) and rh.is_active = true
            where coalesce(rr.completed_at, rr.started_at) < date_sub(current_timestamp, interval 7 year)
              and rh.id is null
              and not exists (
                  select 1
                  from reconciliation_exception re
                  where re.run_id = rr.id
              )
            """
        );
    }
}
