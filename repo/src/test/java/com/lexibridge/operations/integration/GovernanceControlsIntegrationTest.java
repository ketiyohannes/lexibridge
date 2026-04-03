package com.lexibridge.operations.integration;

import com.lexibridge.operations.governance.RetentionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "spring.task.scheduling.enabled=false",
    "lexibridge.security.antivirus.enabled=false"
})
class GovernanceControlsIntegrationTest {

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
    private RetentionService retentionService;

    @Test
    void retentionPurge_shouldEnforceSevenYearWindowAndRespectHolds() {
        long userId = createUser();

        long oldAuditId = insertAuditRow(userId, "AUDIT_OLD_DROP", LocalDateTime.now().minusYears(8));
        long heldOldAuditId = insertAuditRow(userId, "AUDIT_OLD_HELD", LocalDateTime.now().minusYears(8));
        long recentAuditId = insertAuditRow(userId, "AUDIT_RECENT_KEEP", LocalDateTime.now().minusYears(1));
        long oldRedactionId = insertAuditRedaction(oldAuditId, userId, LocalDateTime.now().minusYears(8));
        long heldOldRedactionId = insertAuditRedaction(heldOldAuditId, userId, LocalDateTime.now().minusYears(8));
        insertHold("audit_log", heldOldAuditId, userId);
        insertHold("audit_redaction_event", heldOldRedactionId, userId);

        long oldRunId = insertReconciliationRun(userId, LocalDate.now().minusYears(8).minusDays(1), LocalDateTime.now().minusYears(8));
        long heldOldRunId = insertReconciliationRun(userId, LocalDate.now().minusYears(8).minusDays(2), LocalDateTime.now().minusYears(8));
        long recentRunId = insertReconciliationRun(userId, LocalDate.now().minusYears(1), LocalDateTime.now().minusYears(1));

        long oldExceptionId = insertReconciliationException(oldRunId, LocalDateTime.now().minusYears(8));
        long heldOldExceptionId = insertReconciliationException(heldOldRunId, LocalDateTime.now().minusYears(8));
        insertHold("reconciliation_exception", heldOldExceptionId, userId);
        insertHold("reconciliation_run", heldOldRunId, userId);

        int removedRedactions = retentionService.purgeExpiredAuditRedactionEvents();
        int removedAudit = retentionService.purgeExpiredAuditLogs();
        int removedExceptions = retentionService.purgeExpiredReconciliationExceptions();
        int removedRuns = retentionService.purgeExpiredReconciliationRuns();

        assertEquals(1, removedRedactions);
        assertEquals(1, removedAudit);
        assertEquals(1, removedExceptions);
        assertEquals(1, removedRuns);

        assertEquals(0, countById("audit_redaction_event", oldRedactionId));
        assertEquals(1, countById("audit_redaction_event", heldOldRedactionId));
        assertEquals(0, countById("audit_log", oldAuditId));
        assertEquals(1, countById("audit_log", heldOldAuditId));
        assertEquals(1, countById("audit_log", recentAuditId));

        assertEquals(0, countById("reconciliation_exception", oldExceptionId));
        assertEquals(1, countById("reconciliation_exception", heldOldExceptionId));

        assertEquals(0, countById("reconciliation_run", oldRunId));
        assertEquals(1, countById("reconciliation_run", heldOldRunId));
        assertEquals(1, countById("reconciliation_run", recentRunId));
    }

    @Test
    void auditLog_shouldBeImmutableAtDatabaseLevel() {
        long userId = createUser();
        long auditId = insertAuditRow(userId, "AUDIT_IMMUTABLE", LocalDateTime.now());

        assertThrows(
            DataAccessException.class,
            () -> jdbcTemplate.update("update audit_log set event_type = 'CHANGED' where id = ?", auditId)
        );
        assertThrows(
            DataAccessException.class,
            () -> jdbcTemplate.update("delete from audit_log where id = ?", auditId)
        );

        assertEquals(1, countById("audit_log", auditId));
    }

    @Test
    void bookingStateTransition_shouldBeImmutableAtDatabaseLevel() {
        long userId = createUser();
        long bookingId = insertBookingOrder(userId, LocalDateTime.now().minusDays(1));
        long recentTransitionId = insertBookingTransition(bookingId, userId, LocalDateTime.now().minusDays(1));

        assertThrows(
            DataAccessException.class,
            () -> jdbcTemplate.update("update booking_state_transition set to_state = 'CANCELLED' where id = ?", recentTransitionId)
        );
        assertThrows(
            DataAccessException.class,
            () -> jdbcTemplate.update("delete from booking_state_transition where id = ?", recentTransitionId)
        );
        assertEquals(1, countById("booking_state_transition", recentTransitionId));

        long oldTransitionId = insertBookingTransition(bookingId, userId, LocalDateTime.now().minusYears(8));
        int deletedOld = jdbcTemplate.update("delete from booking_state_transition where id = ?", oldTransitionId);
        assertEquals(1, deletedOld);
        assertEquals(0, countById("booking_state_transition", oldTransitionId));

        long heldOldTransitionId = insertBookingTransition(bookingId, userId, LocalDateTime.now().minusYears(8));
        insertHold("booking_state_transition", heldOldTransitionId, userId);
        assertThrows(
            DataAccessException.class,
            () -> jdbcTemplate.update("delete from booking_state_transition where id = ?", heldOldTransitionId)
        );
        assertEquals(1, countById("booking_state_transition", heldOldTransitionId));
    }

    private long createUser() {
        String username = "gov-user-" + UUID.randomUUID().toString().substring(0, 8);
        jdbcTemplate.update(
            "insert into app_user (location_id, username, full_name, password_hash, is_active) values (1, ?, 'Governance User', 'x', true)",
            username
        );
        Long id = jdbcTemplate.queryForObject("select id from app_user where username = ?", Long.class, username);
        if (id == null) {
            throw new IllegalStateException("Could not create test user");
        }
        return id;
    }

    private long insertAuditRow(long actorUserId, String eventType, LocalDateTime createdAt) {
        jdbcTemplate.update(
            """
            insert into audit_log (actor_user_id, actor_type, event_type, entity_type, entity_id, location_id, payload_json, created_at)
            values (?, 'USER', ?, 'test_entity', ?, 1, json_object(), ?)
            """,
            actorUserId,
            eventType,
            UUID.randomUUID().toString(),
            createdAt
        );
        Long id = jdbcTemplate.queryForObject("select id from audit_log where event_type = ? order by id desc limit 1", Long.class, eventType);
        if (id == null) {
            throw new IllegalStateException("Could not create audit log row");
        }
        return id;
    }

    private long insertReconciliationRun(long actorUserId, LocalDate businessDate, LocalDateTime startedAt) {
        jdbcTemplate.update(
            """
            insert into reconciliation_run (location_id, business_date, status, summary_json, started_at, completed_at, created_by)
            values (1, ?, 'COMPLETED', json_object('ok', true), ?, ?, ?)
            """,
            businessDate,
            startedAt,
            startedAt.plusHours(1),
            actorUserId
        );
        Long id = jdbcTemplate.queryForObject("select id from reconciliation_run where business_date = ? and location_id = 1", Long.class, businessDate);
        if (id == null) {
            throw new IllegalStateException("Could not create reconciliation run");
        }
        return id;
    }

    private long insertAuditRedaction(long auditLogId, long requestedBy, LocalDateTime createdAt) {
        jdbcTemplate.update(
            """
            insert into audit_redaction_event (audit_log_id, requested_by, reason_text, redacted_fields_json, created_at)
            values (?, ?, 'test redaction', json_array('payload_json'), ?)
            """,
            auditLogId,
            requestedBy,
            createdAt
        );
        Long id = jdbcTemplate.queryForObject("select id from audit_redaction_event where audit_log_id = ? order by id desc limit 1", Long.class, auditLogId);
        if (id == null) {
            throw new IllegalStateException("Could not create audit redaction event");
        }
        return id;
    }

    private long insertReconciliationException(long runId, LocalDateTime createdAt) {
        jdbcTemplate.update(
            """
            insert into reconciliation_exception (run_id, exception_type, status, details_json, created_at)
            values (?, 'MISMATCH', 'OPEN', json_object('source', 'test'), ?)
            """,
            runId,
            createdAt
        );
        Long id = jdbcTemplate.queryForObject("select id from reconciliation_exception where run_id = ? order by id desc limit 1", Long.class, runId);
        if (id == null) {
            throw new IllegalStateException("Could not create reconciliation exception");
        }
        return id;
    }

    private long insertBookingOrder(long createdBy, LocalDateTime startAt) {
        LocalDateTime endAt = startAt.plusMinutes(30);
        jdbcTemplate.update(
            """
            insert into booking_order (
              location_id,
              customer_name,
              customer_phone,
              start_at,
              end_at,
              slot_count,
              status,
              order_note,
              created_by,
              expires_at,
              no_show_close_at,
              created_at,
              updated_at
            )
            values (1, 'Test Customer', '5550100', ?, ?, 2, 'RESERVED', 'test booking', ?, ?, ?, ?, ?)
            """,
            startAt,
            endAt,
            createdBy,
            startAt.plusMinutes(10),
            endAt.plusMinutes(30),
            startAt,
            startAt
        );
        Long id = jdbcTemplate.queryForObject("select id from booking_order where created_by = ? order by id desc limit 1", Long.class, createdBy);
        if (id == null) {
            throw new IllegalStateException("Could not create booking order");
        }
        return id;
    }

    private long insertBookingTransition(long bookingOrderId, long changedBy, LocalDateTime changedAt) {
        jdbcTemplate.update(
            """
            insert into booking_state_transition (booking_order_id, from_state, to_state, reason_text, changed_by, changed_at)
            values (?, 'RESERVED', 'CONFIRMED', 'test transition', ?, ?)
            """,
            bookingOrderId,
            changedBy,
            changedAt
        );
        Long id = jdbcTemplate.queryForObject("select id from booking_state_transition where booking_order_id = ? order by id desc limit 1", Long.class, bookingOrderId);
        if (id == null) {
            throw new IllegalStateException("Could not create booking state transition row");
        }
        return id;
    }

    private void insertHold(String entityType, long entityId, long createdBy) {
        jdbcTemplate.update(
            """
            insert into retention_hold (hold_ref, entity_type, entity_id, reason_text, is_active, created_by)
            values (?, ?, ?, 'Legal hold for integration test', true, ?)
            """,
            "HOLD-" + UUID.randomUUID().toString().substring(0, 10),
            entityType,
            String.valueOf(entityId),
            createdBy
        );
    }

    private int countById(String table, long id) {
        Integer count = jdbcTemplate.queryForObject("select count(*) from " + table + " where id = ?", Integer.class, id);
        return count == null ? 0 : count;
    }
}
