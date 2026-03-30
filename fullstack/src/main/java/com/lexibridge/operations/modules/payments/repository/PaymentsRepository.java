package com.lexibridge.operations.modules.payments.repository;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Repository
public class PaymentsRepository {

    private final JdbcTemplate jdbcTemplate;

    public PaymentsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long createTender(long bookingOrderId,
                             String tenderType,
                             BigDecimal amount,
                             String currency,
                             String terminalId,
                             String terminalTxnId,
                             long createdBy) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                """
                insert into tender_entry
                (booking_order_id, tender_type, amount, currency, status, terminal_id, terminal_txn_id, created_by)
                values (?, ?, ?, ?, 'PENDING', ?, ?, ?)
                """,
                Statement.RETURN_GENERATED_KEYS
            );
            ps.setLong(1, bookingOrderId);
            ps.setString(2, tenderType);
            ps.setBigDecimal(3, amount);
            ps.setString(4, currency);
            ps.setString(5, terminalId);
            ps.setString(6, terminalTxnId);
            ps.setLong(7, createdBy);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Long bookingLocationId(long bookingOrderId) {
        return jdbcTemplate.query(
            "select location_id from booking_order where id = ?",
            rs -> rs.next() ? (Long) rs.getObject(1) : null,
            bookingOrderId
        );
    }

    public Map<String, BigDecimal> splitPolicyForLocation(long locationId) {
        return jdbcTemplate.query(
            """
            select merchant_ratio, platform_ratio
            from payment_split_policy
            where location_id = ? and is_active = true
            limit 1
            """,
            rs -> {
                if (!rs.next()) {
                    return Map.of(
                        "merchantRatio", BigDecimal.valueOf(80),
                        "platformRatio", BigDecimal.valueOf(20)
                    );
                }
                return Map.of(
                    "merchantRatio", rs.getBigDecimal("merchant_ratio"),
                    "platformRatio", rs.getBigDecimal("platform_ratio")
                );
            },
            locationId
        );
    }

    public void upsertSplitPolicy(long locationId, BigDecimal merchantRatio, BigDecimal platformRatio, long actorUserId) {
        jdbcTemplate.update(
            """
            insert into payment_split_policy (location_id, merchant_ratio, platform_ratio, is_active, created_by)
            values (?, ?, ?, true, ?)
            on duplicate key update merchant_ratio = values(merchant_ratio), platform_ratio = values(platform_ratio), is_active = true
            """,
            locationId,
            merchantRatio,
            platformRatio,
            actorUserId
        );
    }

    public LocalDateTime tenderCreatedAt(long tenderEntryId) {
        return jdbcTemplate.query(
            "select created_at from tender_entry where id = ?",
            rs -> rs.next() ? rs.getTimestamp(1).toLocalDateTime() : null,
            tenderEntryId
        );
    }

    public BigDecimal tenderAmountForUpdate(long tenderEntryId) {
        return jdbcTemplate.query(
            "select amount from tender_entry where id = ? for update",
            rs -> rs.next() ? rs.getBigDecimal(1) : null,
            tenderEntryId
        );
    }

    public BigDecimal approvedRefundTotalForUpdate(long tenderEntryId) {
        BigDecimal total = jdbcTemplate.query(
            "select coalesce(sum(amount), 0) from refund_request where tender_entry_id = ? and status = 'APPROVED' for update",
            rs -> rs.next() ? rs.getBigDecimal(1) : null,
            tenderEntryId
        );
        return total == null ? BigDecimal.ZERO : total;
    }

    public void snapshotSplit(long tenderEntryId,
                              BigDecimal merchantRatio,
                              BigDecimal platformRatio,
                              BigDecimal merchantAmount,
                              BigDecimal platformAmount) {
        jdbcTemplate.update(
            """
            insert into payment_split_snapshot
            (tender_entry_id, merchant_ratio, platform_ratio, merchant_amount, platform_amount)
            values (?, ?, ?, ?, ?)
            """,
            tenderEntryId,
            merchantRatio,
            platformRatio,
            merchantAmount,
            platformAmount
        );
    }

    public boolean registerCallback(String terminalId, String terminalTxnId, String payloadJson) {
        try {
            jdbcTemplate.update(
                """
                insert into terminal_callback_log
                (terminal_id, terminal_txn_id, payload_json, processed_status)
                values (?, ?, cast(? as json), 'PROCESSED')
                """,
                terminalId,
                terminalTxnId,
                payloadJson
            );
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    public int confirmTenderByTerminalTxn(String terminalId, String terminalTxnId) {
        return jdbcTemplate.update(
            """
            update tender_entry
            set status = 'CONFIRMED', callback_received_at = current_timestamp
            where terminal_id = ? and terminal_txn_id = ?
            """,
            terminalId,
            terminalTxnId
        );
    }

    public int confirmTenderByTerminalTxnInLocation(String terminalId, String terminalTxnId, long locationId) {
        return jdbcTemplate.update(
            """
            update tender_entry te
            join booking_order bo on bo.id = te.booking_order_id
            set te.status = 'CONFIRMED', te.callback_received_at = current_timestamp
            where te.terminal_id = ?
              and te.terminal_txn_id = ?
              and bo.location_id = ?
            """,
            terminalId,
            terminalTxnId,
            locationId
        );
    }

    public Long callbackTenderLocation(String terminalId, String terminalTxnId) {
        return jdbcTemplate.query(
            """
            select bo.location_id
            from tender_entry te
            join booking_order bo on bo.id = te.booking_order_id
            where te.terminal_id = ? and te.terminal_txn_id = ?
            limit 1
            """,
            rs -> rs.next() ? (Long) rs.getObject(1) : null,
            terminalId,
            terminalTxnId
        );
    }

    public long createRefund(long tenderEntryId,
                             BigDecimal amount,
                             String currency,
                             String reason,
                             boolean requiresSupervisor,
                             long createdBy) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                """
                insert into refund_request
                (tender_entry_id, amount, currency, reason_text, status, requires_supervisor, created_by)
                values (?, ?, ?, ?, ?, ?, ?)
                """,
                Statement.RETURN_GENERATED_KEYS
            );
            ps.setLong(1, tenderEntryId);
            ps.setBigDecimal(2, amount);
            ps.setString(3, currency);
            ps.setString(4, reason);
            ps.setString(5, requiresSupervisor ? "PENDING_SUPERVISOR" : "APPROVED");
            ps.setBoolean(6, requiresSupervisor);
            ps.setLong(7, createdBy);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public void approveRefund(long refundId, long supervisorUserId) {
        jdbcTemplate.update(
            """
            update refund_request
            set status = 'APPROVED', approved_by = ?, approved_at = current_timestamp
            where id = ?
            """,
            supervisorUserId,
            refundId
        );
    }

    public long createReconciliationRun(long locationId, LocalDate businessDate, long actorUserId, String summaryJson) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                """
                insert into reconciliation_run
                (location_id, business_date, status, summary_json, started_at, completed_at, created_by)
                values (?, ?, 'COMPLETED', cast(? as json), current_timestamp, current_timestamp, ?)
                """,
                Statement.RETURN_GENERATED_KEYS
            );
            ps.setLong(1, locationId);
            ps.setObject(2, businessDate);
            ps.setString(3, summaryJson);
            ps.setLong(4, actorUserId);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public void createException(long runId, String exceptionType, String detailsJson) {
        jdbcTemplate.update(
            """
            insert into reconciliation_exception (run_id, exception_type, status, details_json)
            values (?, ?, 'OPEN', cast(? as json))
            """,
            runId,
            exceptionType,
            detailsJson
        );
    }

    public Integer countMissingTenderForBusinessDate(long locationId, LocalDate businessDate) {
        return jdbcTemplate.queryForObject(
            """
            select count(*)
            from booking_order bo
            left join tender_entry te on te.booking_order_id = bo.id and te.status = 'CONFIRMED'
            where bo.location_id = ?
              and date(bo.created_at) = ?
              and bo.status in ('CONFIRMED', 'COMPLETED')
              and te.id is null
            """,
            Integer.class,
            locationId,
            businessDate
        );
    }

    public Integer countDuplicateCallbackSignals() {
        return jdbcTemplate.queryForObject(
            """
            select count(*)
            from (
                select terminal_id, terminal_txn_id, count(*) c
                from tender_entry
                where terminal_id is not null and terminal_txn_id is not null
                group by terminal_id, terminal_txn_id
                having c > 1
            ) x
            """,
            Integer.class
        );
    }

    public Integer countMismatchForBusinessDate(long locationId, LocalDate businessDate) {
        return jdbcTemplate.queryForObject(
            """
            select count(*)
            from tender_entry te
            join booking_order bo on bo.id = te.booking_order_id
            where bo.location_id = ?
              and date(bo.created_at) = ?
              and te.status = 'CONFIRMED'
              and te.amount <= 0
            """,
            Integer.class,
            locationId,
            businessDate
        );
    }

    public List<Long> callbacksWithoutConfirmedTender() {
        return jdbcTemplate.query(
            """
            select tcl.id
            from terminal_callback_log tcl
            left join tender_entry te
              on te.terminal_id = tcl.terminal_id and te.terminal_txn_id = tcl.terminal_txn_id and te.status = 'CONFIRMED'
            where te.id is null
            """,
            (rs, rowNum) -> rs.getLong(1)
        );
    }

    public Map<String, Object> summary(Long locationId) {
        String tenderWhere = locationId == null ? "" : " and exists (select 1 from booking_order bo where bo.id = tender_entry.booking_order_id and bo.location_id = ?)";
        String refundWhere = locationId == null ? "" : " and exists (select 1 from tender_entry te join booking_order bo on bo.id = te.booking_order_id where te.id = refund_request.tender_entry_id and bo.location_id = ?)";
        String exceptionWhere = locationId == null ? "" : " and exists (select 1 from reconciliation_run rr where rr.id = reconciliation_exception.run_id and rr.location_id = ?)";
        Integer tendersToday = jdbcTemplate.queryForObject(
            "select count(*) from tender_entry where date(created_at) = current_date" + tenderWhere,
            Integer.class,
            locationId == null ? new Object[]{} : new Object[]{locationId}
        );
        Integer pendingRefunds = jdbcTemplate.queryForObject(
            "select count(*) from refund_request where status = 'PENDING_SUPERVISOR'" + refundWhere,
            Integer.class,
            locationId == null ? new Object[]{} : new Object[]{locationId}
        );
        Integer openExceptions = jdbcTemplate.queryForObject(
            "select count(*) from reconciliation_exception where status in ('OPEN','IN_REVIEW')" + exceptionWhere,
            Integer.class,
            locationId == null ? new Object[]{} : new Object[]{locationId}
        );
        Integer duplicates = locationId == null ? countDuplicateCallbackSignals() : 0;
        return Map.of(
            "tendersToday", tendersToday == null ? 0 : tendersToday,
            "refundsPendingSupervisor", pendingRefunds == null ? 0 : pendingRefunds,
            "reconExceptionsOpen", openExceptions == null ? 0 : openExceptions,
            "duplicateCallbacks", duplicates == null ? 0 : duplicates
        );
    }

    public List<Map<String, Object>> reconciliationExceptions(String status) {
        if (status == null || status.isBlank()) {
            return jdbcTemplate.queryForList(
                "select id, run_id, exception_type, status, resolution_note, created_at, resolved_at from reconciliation_exception order by id desc"
            );
        }
        return jdbcTemplate.queryForList(
            "select id, run_id, exception_type, status, resolution_note, created_at, resolved_at from reconciliation_exception where status = ? order by id desc",
            status
        );
    }

    public List<Map<String, Object>> reconciliationExceptionsByLocation(long locationId, String status) {
        if (status == null || status.isBlank()) {
            return jdbcTemplate.queryForList(
                """
                select re.id, re.run_id, re.exception_type, re.status, re.resolution_note, re.created_at, re.resolved_at
                from reconciliation_exception re
                join reconciliation_run rr on rr.id = re.run_id
                where rr.location_id = ?
                order by re.id desc
                """,
                locationId
            );
        }
        return jdbcTemplate.queryForList(
            """
            select re.id, re.run_id, re.exception_type, re.status, re.resolution_note, re.created_at, re.resolved_at
            from reconciliation_exception re
            join reconciliation_run rr on rr.id = re.run_id
            where rr.location_id = ? and re.status = ?
            order by re.id desc
            """,
            locationId,
            status
        );
    }

    public List<Map<String, Object>> recentTenders(int limit) {
        return jdbcTemplate.queryForList(
            """
            select id, booking_order_id, tender_type, amount, currency, status, terminal_id, terminal_txn_id, created_at
            from tender_entry
            order by id desc
            limit ?
            """,
            limit
        );
    }

    public List<Map<String, Object>> recentTendersByLocation(long locationId, int limit) {
        return jdbcTemplate.queryForList(
            """
            select te.id, te.booking_order_id, te.tender_type, te.amount, te.currency, te.status, te.terminal_id, te.terminal_txn_id, te.created_at
            from tender_entry te
            join booking_order bo on bo.id = te.booking_order_id
            where bo.location_id = ?
            order by te.id desc
            limit ?
            """,
            locationId,
            limit
        );
    }

    public List<Map<String, Object>> recentRefunds(int limit) {
        return jdbcTemplate.queryForList(
            """
            select id, tender_entry_id, amount, currency, reason_text, status, requires_supervisor, approved_by, approved_at, created_at
            from refund_request
            order by id desc
            limit ?
            """,
            limit
        );
    }

    public List<Map<String, Object>> recentRefundsByLocation(long locationId, int limit) {
        return jdbcTemplate.queryForList(
            """
            select rr.id, rr.tender_entry_id, rr.amount, rr.currency, rr.reason_text, rr.status, rr.requires_supervisor, rr.approved_by, rr.approved_at, rr.created_at
            from refund_request rr
            join tender_entry te on te.id = rr.tender_entry_id
            join booking_order bo on bo.id = te.booking_order_id
            where bo.location_id = ?
            order by rr.id desc
            limit ?
            """,
            locationId,
            limit
        );
    }

    public List<Map<String, Object>> recentReconciliationRuns(int limit) {
        return jdbcTemplate.queryForList(
            """
            select id, location_id, business_date, status, started_at, completed_at, created_by
            from reconciliation_run
            order by id desc
            limit ?
            """,
            limit
        );
    }

    public List<Map<String, Object>> recentReconciliationRunsByLocation(long locationId, int limit) {
        return jdbcTemplate.queryForList(
            """
            select id, location_id, business_date, status, started_at, completed_at, created_by
            from reconciliation_run
            where location_id = ?
            order by id desc
            limit ?
            """,
            locationId,
            limit
        );
    }

    public Map<String, Object> reconciliationRunById(long runId) {
        return jdbcTemplate.query(
            """
            select id, location_id, business_date, status, summary_json, started_at, completed_at, created_by
            from reconciliation_run
            where id = ?
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                return Map.of(
                    "id", rs.getLong("id"),
                    "location_id", rs.getLong("location_id"),
                    "business_date", rs.getObject("business_date"),
                    "status", rs.getString("status"),
                    "summary_json", rs.getString("summary_json"),
                    "started_at", rs.getObject("started_at"),
                    "completed_at", rs.getObject("completed_at"),
                    "created_by", rs.getLong("created_by")
                );
            },
            runId
        );
    }

    public List<Map<String, Object>> reconciliationExceptionsByRunId(long runId) {
        return jdbcTemplate.queryForList(
            """
            select id, run_id, exception_type, status, details_json, resolution_note, resolved_by, created_at, resolved_at
            from reconciliation_exception
            where run_id = ?
            order by id asc
            """,
            runId
        );
    }

    public void updateExceptionStatus(long exceptionId, String status, String note, Long actorUserId) {
        jdbcTemplate.update(
            """
            update reconciliation_exception
            set status = ?, resolution_note = ?, resolved_by = ?, resolved_at = case when ? in ('RESOLVED') then current_timestamp else resolved_at end
            where id = ?
            """,
            status,
            note,
            actorUserId,
            status,
            exceptionId
        );
    }

    public double paymentFailureRateLastHour() {
        Integer total = jdbcTemplate.queryForObject(
            "select count(*) from tender_entry where created_at >= date_sub(current_timestamp, interval 1 hour)",
            Integer.class
        );
        if (total == null || total == 0) {
            return 0.0;
        }
        Integer failed = jdbcTemplate.queryForObject(
            """
            select count(*)
            from tender_entry
            where created_at >= date_sub(current_timestamp, interval 1 hour)
              and status = 'FAILED'
            """,
            Integer.class
        );
        return ((failed == null ? 0 : failed) * 100.0) / total;
    }

    public List<Long> activeLocationIds() {
        return jdbcTemplate.query(
            "select id from location order by id asc",
            (rs, rowNum) -> rs.getLong(1)
        );
    }
}
